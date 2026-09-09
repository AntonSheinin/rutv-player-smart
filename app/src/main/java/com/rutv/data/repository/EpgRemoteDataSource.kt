package com.rutv.data.repository

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.rutv.data.model.*
import com.rutv.util.Constants
import com.rutv.util.EpgConstants
import com.rutv.util.logDebug
import kotlinx.coroutines.*
import java.io.Reader
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import java.time.Instant
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import timber.log.Timber

internal data class EpgFetchResult(val programs: Map<String, List<EpgProgram>>, val updateAt: String)

@Singleton
class EpgRemoteDataSource @Inject constructor(private val gson: Gson) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(EpgConstants.EPG_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(EpgConstants.EPG_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    internal suspend fun fetch(url: String, ids: List<String>, from: Long, to: Long): EpgFetchResult = withContext(Dispatchers.IO) {
        val payload = EpgRequest(ids.map { EpgChannelRequest(it) }, TimeZone.getDefault().id,
            Instant.ofEpochMilli(from).toString(), Instant.ofEpochMilli(to).toString())
        val request = Request.Builder().url("${url.trimEnd('/')}/epg")
            .header("User-Agent", Constants.DEFAULT_USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        // Call.cancel closes the active exchange even while execute/body reading is blocked.
        val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { http ->
                if (http.code != 200) throw IOException("EPG HTTP ${http.code}")
                val body = http.body ?: throw IOException("Missing EPG body")
                val stream = when (http.header("Content-Encoding")?.lowercase()) {
                    "gzip" -> GZIPInputStream(body.byteStream())
                    "deflate" -> InflaterInputStream(body.byteStream())
                    null, "identity", "" -> body.byteStream()
                    else -> throw IOException("Unsupported EPG content encoding")
                }
                val context = currentCoroutineContext()
                val reader = object : java.io.FilterReader(stream.reader(Charsets.UTF_8)) {
                    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                        context.ensureActive()
                        return super.read(buffer, offset, length)
                    }
                }
                val response = reader.use { parseEpgResponseStreaming(it) }
                context.ensureActive()
                if (!response.epg.keys.containsAll(ids)) throw IOException("EPG response omitted requested channels")
                EpgFetchResult(response.epg, response.lastEpgUpdateAt)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancellationWatcher.cancel()
            call.cancel()
        }
    }

    private fun JsonReader.safeNextString(maxLength: Int): String {
        val value = nextString()
        if (value.length > maxLength) {
            return value.take(maxLength)
        }
        return value
    }

    internal fun parseEpgResponseStreaming(reader: Reader): EpgResponse {
        val jsonReader = JsonReader(reader)
        var updateMode = ""
        var timestamp = ""
        var lastEpgUpdateAt = ""
        var channelsRequested = 0
        var channelsFound = 0
        var totalPrograms = 0
        val epgMap = mutableMapOf<String, List<EpgProgram>>()

        try {
            var hasEpg = false
            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                when (jsonReader.nextName()) {
                    "update_mode" -> updateMode = jsonReader.safeNextString(MAX_FIELD_LENGTH_TITLE)
                    "timestamp" -> timestamp = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                    "last_epg_update_at" -> lastEpgUpdateAt = if (jsonReader.peek() == JsonToken.NULL) { jsonReader.nextNull(); "" } else jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                    "channels_requested" -> channelsRequested = jsonReader.nextInt()
                    "channels_found" -> channelsFound = jsonReader.nextInt()
                    "total_programs" -> totalPrograms = jsonReader.nextInt()
                    "epg" -> {
                        hasEpg = true
                        logDebug { "Parsing EPG map (streaming)..." }
                        var channelCount = 0
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            val channelId = jsonReader.nextName()
                            val programs = parsePrograms(jsonReader)
                            epgMap[channelId] = programs
                            channelCount++
                        }
                        jsonReader.endObject()
                        logDebug { "Finished parsing $channelCount channels" }
                    }
                    else -> jsonReader.skipValue()
                }
            }
            jsonReader.endObject()
            if (!hasEpg) throw java.io.IOException("EPG response has no program map")
            return EpgResponse(updateMode, timestamp, lastEpgUpdateAt, channelsRequested, channelsFound, totalPrograms, epgMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error in streaming JSON parser")
            throw java.io.IOException("Invalid EPG response", e)
        } finally {
            jsonReader.close()
        }
    }

    private fun parsePrograms(jsonReader: JsonReader): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            programs.add(parseProgram(jsonReader))
        }
        jsonReader.endArray()
        return programs
    }

    private fun parseProgram(jsonReader: JsonReader): EpgProgram {
        var id = ""
        var startTime = ""
        var stopTime = ""
        var title = ""
        var description = ""

        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "id" -> id = jsonReader.safeNextString(MAX_FIELD_LENGTH_ID)
                "start_time" -> startTime = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                "stop_time" -> stopTime = jsonReader.safeNextString(MAX_FIELD_LENGTH_TIME)
                "title" -> title = jsonReader.safeNextString(MAX_FIELD_LENGTH_TITLE)
                "description" -> description = when (jsonReader.peek()) {
                    JsonToken.NULL -> {
                        jsonReader.nextNull()
                        ""
                    }
                    JsonToken.STRING -> jsonReader.safeNextString(MAX_FIELD_LENGTH_DESCRIPTION)
                    else -> {
                        jsonReader.skipValue()
                        ""
                    }
                }
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()

        return EpgProgram(id, startTime, stopTime, title, description)
    }

}

private const val MAX_FIELD_LENGTH_ID = 128
private const val MAX_FIELD_LENGTH_TIME = 64
private const val MAX_FIELD_LENGTH_TITLE = 256
private const val MAX_FIELD_LENGTH_DESCRIPTION = 1_024

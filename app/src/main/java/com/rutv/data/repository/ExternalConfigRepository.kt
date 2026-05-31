package com.rutv.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports one-shot provisioning values from app-specific external storage.
 *
 * The imported values are copied into DataStore, which remains the runtime source of truth.
 */
@Singleton
class ExternalConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository
) {
    private val importMutex = Mutex()

    suspend fun importIfChanged() {
        importMutex.withLock {
            val configFile = context.getExternalFilesDir(null)?.resolve(CONFIG_FILE_NAME)
            if (configFile == null) {
                Timber.d("External config directory is unavailable")
                return
            }
            if (!configFile.exists()) {
                Timber.d("External config file not found")
                return
            }

            val rawContent = runCatching { configFile.readText() }
                .onFailure { Timber.w(it, "Failed to read external config file") }
                .getOrNull()
                ?: return

            val parsed = ExternalConfigParser.parse(rawContent)
            if (parsed.invalidMessages.isNotEmpty()) {
                parsed.invalidMessages.forEach { message ->
                    Timber.w("Invalid external config: %s", message)
                }
                return
            }
            if (parsed.values.isEmpty()) {
                Timber.d("External config contains no supported nonblank values")
                return
            }

            val normalizedHash = ExternalConfigParser.hashNormalized(parsed.values)
            val importedHash = preferencesRepository.externalConfigImportedHash.first()
            if (normalizedHash == importedHash) {
                Timber.d("External config unchanged")
                return
            }

            preferencesRepository.applyExternalConfig(parsed.values, normalizedHash)
            Timber.i("Imported external config keys: %s", parsed.values.keys.sorted().joinToString())
        }
    }

    private companion object {
        const val CONFIG_FILE_NAME = "rutv.conf"
    }
}

object ExternalConfigKeys {
    const val PLAYLIST_URL = "PLAYLIST_URL"
    const val EPG_URL = "EPG_URL"
    const val EPG_DAYS_AHEAD = "EPG_DAYS_AHEAD"
    const val EPG_DAYS_PAST = "EPG_DAYS_PAST"
    const val EPG_PAGE_DAYS = "EPG_PAGE_DAYS"

    val SUPPORTED_KEYS = setOf(
        PLAYLIST_URL,
        EPG_URL,
        EPG_DAYS_AHEAD,
        EPG_DAYS_PAST,
        EPG_PAGE_DAYS
    )
}

object ExternalConfigParser {
    fun parse(content: String): ParsedConfig {
        val values = linkedMapOf<String, String>()
        val invalidMessages = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        content.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val separatorIndex = line.indexOf('=')
            if (separatorIndex < 0) {
                val keyCandidate = normalizeKey(line)
                if (keyCandidate in ExternalConfigKeys.SUPPORTED_KEYS) {
                    invalidMessages += "line $lineNumber: $keyCandidate is missing '='"
                }
                return@forEachIndexed
            }

            val key = normalizeKey(line.substring(0, separatorIndex))
            if (key !in ExternalConfigKeys.SUPPORTED_KEYS) return@forEachIndexed

            val value = stripMatchingQuotes(line.substring(separatorIndex + 1).trim()).trim()
            if (value.isBlank()) return@forEachIndexed

            if (!seenKeys.add(key)) {
                invalidMessages += "line $lineNumber: duplicate $key"
                return@forEachIndexed
            }

            val validationError = validateValue(key, value)
            if (validationError != null) {
                invalidMessages += "line $lineNumber: $validationError"
            } else {
                values[key] = value
            }
        }

        return ParsedConfig(values = values, invalidMessages = invalidMessages)
    }

    fun hashNormalized(values: Map<String, String>): String {
        val normalizedContent = values.toSortedMap().entries.joinToString(separator = "\n") {
            "${it.key}=${it.value}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedContent.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun validateValue(key: String, value: String): String? {
        return when (key) {
            ExternalConfigKeys.PLAYLIST_URL, ExternalConfigKeys.EPG_URL -> null
            ExternalConfigKeys.EPG_DAYS_AHEAD, ExternalConfigKeys.EPG_DAYS_PAST -> {
                val days = value.toIntOrNull()
                if (days == null || days < 0) "$key must be an integer >= 0" else null
            }
            ExternalConfigKeys.EPG_PAGE_DAYS -> {
                val days = value.toIntOrNull()
                if (days == null || days < 1) "$key must be an integer >= 1" else null
            }
            else -> null
        }
    }

    private fun stripMatchingQuotes(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        val last = value.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            value.substring(1, value.lastIndex)
        } else {
            value
        }
    }

    private fun normalizeKey(key: String): String = key.trim().uppercase(Locale.US)
}

data class ParsedConfig(
    val values: Map<String, String>,
    val invalidMessages: List<String>
)

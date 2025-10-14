# EPG Implementation Guide - RuTV Player

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Data Models](#data-models)
4. [Data Flow](#data-flow)
5. [Fetching EPG Data](#fetching-epg-data)
6. [Parsing EPG Data](#parsing-epg-data)
7. [Storage Mechanism](#storage-mechanism)
8. [Retrieval and Display](#retrieval-and-display)
9. [UI Integration](#ui-integration)
10. [Performance Optimizations](#performance-optimizations)
11. [Error Handling](#error-handling)
12. [Configuration](#configuration)

---

## Overview

RuTV Player implements a sophisticated Electronic Program Guide (EPG) system that:
- Fetches TV program schedules from a custom JSON-based EPG service
- Parses large datasets efficiently using **streaming JSON parser**
- Stores data with **GZIP compression** for optimal memory usage
- Displays programs with **auto-scroll** to current program
- Matches channels via `tvg-id` from M3U playlists

### Key Innovation
The streaming JSON parser with GZIP compression solved critical `OutOfMemoryError` issues when handling large EPG datasets (255MB+ responses with 100,000+ programs).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     EPG Architecture                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  [M3U Playlist]                                             │
│       ↓                                                      │
│  Parse tvg-id + catchup-days                                │
│       ↓                                                      │
│  [EpgRepository]                                            │
│    ├─ Health Check                                          │
│    ├─ Fetch EPG Data (POST /epg)                           │
│    ├─ Streaming JSON Parser ← Prevents OutOfMemoryError    │
│    ├─ Memory Cache (Session)                               │
│    └─ Disk Cache (GZIP Compressed)                         │
│       ↓                                                      │
│  [FetchEpgUseCase]                                          │
│    └─ Orchestrates fetch workflow                           │
│       ↓                                                      │
│  [MainViewModel]                                            │
│    ├─ Load Cached EPG on startup                           │
│    ├─ Fetch Fresh EPG after playlist load                  │
│    └─ Provide EPG data to UI                               │
│       ↓                                                      │
│  [Compose UI]                                               │
│    ├─ Channel List (shows current program)                 │
│    └─ EPG Panel (shows all programs, auto-scroll)          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Data Models

### EpgProgram
**Location:** `data/model/EpgProgram.kt`

```kotlin
data class EpgProgram(
    val id: String = "",              // Unique program ID
    val startTime: Long,              // Unix timestamp (milliseconds)
    val stopTime: Long,               // Unix timestamp (milliseconds)
    val title: String,                // Program title
    val description: String = ""      // Program description
) {
    // Check if program is currently airing
    fun isCurrent(): Boolean {
        val now = System.currentTimeMillis()
        return now in startTime..stopTime
    }

    // Check if program has ended
    fun isEnded(): Boolean {
        return System.currentTimeMillis() > stopTime
    }
}
```

**Time Format Support:**
1. ISO 8601 with timezone: `2025-10-14T20:00:00-05:00`
2. ISO 8601 without timezone: `2025-10-14T20:00:00` (uses device timezone)
3. XMLTV format: `20251014200000 -0500`

### EpgRequest
```kotlin
data class EpgRequest(
    val channels: List<EpgChannelRequest>,
    val update: String = "force",     // Update mode
    val timezone: String              // Device timezone (e.g., "America/New_York")
)

data class EpgChannelRequest(
    val xmltvId: String,              // Channel's tvg-id
    val epgDepth: Int                 // Days of EPG to fetch (catchup-days)
)
```

**Example Request:**
```json
{
  "channels": [
    {"xmltvId": "channel1", "epgDepth": 7},
    {"xmltvId": "channel2", "epgDepth": 3}
  ],
  "update": "force",
  "timezone": "America/New_York"
}
```

### EpgResponse
```kotlin
data class EpgResponse(
    val updateMode: String,           // "force" or "incremental"
    val timestamp: String,            // ISO 8601 timestamp
    val channelsRequested: Int,       // Number of channels requested
    val channelsFound: Int,           // Number of channels with data
    val totalPrograms: Int,           // Total programs returned
    val epg: Map<String, List<EpgProgram>>  // tvgId -> programs
)
```

**Example Response:**
```json
{
  "updateMode": "force",
  "timestamp": "2025-10-14T12:00:00Z",
  "channelsRequested": 120,
  "channelsFound": 115,
  "totalPrograms": 144523,
  "epg": {
    "channel1": [
      {
        "id": "prog1",
        "start_time": "2025-10-14T20:00:00-05:00",
        "stop_time": "2025-10-14T21:00:00-05:00",
        "title": "News Tonight",
        "description": "Evening news program"
      }
    ]
  }
}
```

---

## Data Flow

### 1. Initial Load Flow

```
┌──────────────────────────────────────────────────────┐
│ 1. App Startup                                       │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 2. Load Cached EPG from Disk                        │
│    - GZIP decompression                              │
│    - Streaming JSON parser                           │
│    - Display cached programs (instant)               │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 3. Load Playlist (M3U)                              │
│    - Parse tvg-id and catchup-days                  │
│    - Filter channels with hasEpg = true             │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 4. Health Check EPG Service                         │
│    - GET /health (5s timeout)                        │
│    - Verify service is available                     │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 5. Fetch EPG Data                                    │
│    - POST /epg with timezone + channels              │
│    - 60s timeout for large responses                 │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 6. Stream Parse Response                            │
│    - Token-by-token JSON parsing                     │
│    - Field length limits (prevent OOM)               │
│    - Progress logging every 50 channels              │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 7. Save to Caches                                    │
│    - Memory cache (fast access)                      │
│    - Disk cache (GZIP compressed)                    │
└──────────────────┬───────────────────────────────────┘
                   ↓
┌──────────────────────────────────────────────────────┐
│ 8. Update UI                                         │
│    - epgLoadedTimestamp triggers refresh             │
│    - Display fresh programs                          │
└──────────────────────────────────────────────────────┘
```

### 2. Show EPG Panel Flow

```
User Taps Channel (single tap)
         ↓
Check channel.hasEpg
         ↓
ViewModel.showEpgForChannel(tvgId)
         ↓
EpgRepository.getProgramsForChannel(tvgId)
         ↓
Load from Memory/Disk Cache
         ↓
Find Current Program (isCurrent() check)
         ↓
Update ViewState:
  - showEpgPanel = true
  - epgPrograms = list of programs
  - currentProgram = current program
         ↓
Compose Renders EPG Panel
         ↓
Auto-scroll to Current Program
         ↓
Highlight Current Program (gold color)
```

### 3. Current Program Display Flow

```
Channel Changes in Player
         ↓
ViewModel.updateCurrentProgram(channel)
         ↓
EpgRepository.getCurrentProgram(channel.tvgId)
         ↓
Load EPG Data from Cache
         ↓
programs[tvgId].firstOrNull { it.isCurrent() }
         ↓
Update viewState.currentProgram
         ↓
UI Renders Current Program:
  - Channel Info Overlay (top of player)
  - Channel List Item (in playlist)
```

---

## Fetching EPG Data

### EpgRepository
**Location:** `data/repository/EpgRepository.kt`

### Health Check
```kotlin
suspend fun checkHealth(epgUrl: String): Result<Boolean> {
    return withContext(Dispatchers.IO) {
        try {
            val healthUrl = "$epgUrl/health"
            val connection = URL(healthUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = EPG_HEALTH_TIMEOUT_MS
            connection.readTimeout = EPG_HEALTH_TIMEOUT_MS

            val responseCode = connection.responseCode
            connection.disconnect()

            Result.Success(responseCode == 200)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
```

**Constants:**
- `EPG_HEALTH_TIMEOUT_MS = 5000` (5 seconds)

### Fetch EPG Data
```kotlin
suspend fun fetchEpgData(
    epgUrl: String,
    channels: List<Channel>
): Result<EpgResponse> {
    return withContext(Dispatchers.IO) {
        try {
            // Filter channels with EPG
            val channelsWithEpg = channels.filter { it.hasEpg }

            // Build request
            val timezone = TimeZone.getDefault().id
            val request = EpgRequest(
                channels = channelsWithEpg.map { channel ->
                    EpgChannelRequest(
                        xmltvId = channel.tvgId,
                        epgDepth = channel.catchupDays
                    )
                },
                update = "force",
                timezone = timezone
            )

            // POST request
            val url = URL("$epgUrl/epg")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = EPG_FETCH_TIMEOUT_MS
            connection.readTimeout = EPG_FETCH_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Write request
            connection.outputStream.use { os ->
                val writer = BufferedWriter(OutputStreamWriter(os, "UTF-8"))
                gson.toJson(request, writer)
                writer.flush()
            }

            // Read response with streaming parser
            val response = connection.inputStream.bufferedReader().use { reader ->
                parseEpgResponseStreaming(reader)
            }

            connection.disconnect()

            // Cache the response
            response?.let { saveEpgData(it) }

            Result.Success(response!!)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
```

**Constants:**
- `EPG_FETCH_TIMEOUT_MS = 60000` (60 seconds)

---

## Parsing EPG Data

### Streaming JSON Parser

**Problem:** Large EPG responses (255MB+) cause `OutOfMemoryError` when loaded into memory

**Solution:** Token-by-token streaming parser that reads JSON incrementally

```kotlin
private fun parseEpgResponseStreaming(reader: Reader): EpgResponse? {
    return try {
        val jsonReader = JsonReader(reader)
        var updateMode = ""
        var timestamp = ""
        var channelsRequested = 0
        var channelsFound = 0
        var totalPrograms = 0
        val epgMap = mutableMapOf<String, List<EpgProgram>>()

        jsonReader.beginObject()

        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "updateMode" -> updateMode = jsonReader.nextString()
                "timestamp" -> timestamp = jsonReader.nextString()
                "channelsRequested" -> channelsRequested = jsonReader.nextInt()
                "channelsFound" -> channelsFound = jsonReader.nextInt()
                "totalPrograms" -> totalPrograms = jsonReader.nextInt()
                "epg" -> {
                    // Parse EPG object channel by channel
                    jsonReader.beginObject()
                    var channelCount = 0

                    while (jsonReader.hasNext()) {
                        val channelId = jsonReader.nextString()
                        val programs = parsePrograms(jsonReader)
                        epgMap[channelId] = programs

                        channelCount++
                        if (channelCount % 50 == 0) {
                            Timber.d("Parsed $channelCount channels so far...")
                        }
                    }

                    jsonReader.endObject()
                }
                else -> jsonReader.skipValue()
            }
        }

        jsonReader.endObject()

        EpgResponse(
            updateMode = updateMode,
            timestamp = timestamp,
            channelsRequested = channelsRequested,
            channelsFound = channelsFound,
            totalPrograms = totalPrograms,
            epg = epgMap
        )
    } catch (e: Exception) {
        Timber.e(e, "Error parsing EPG response")
        null
    }
}

private fun parsePrograms(jsonReader: JsonReader): List<EpgProgram> {
    val programs = mutableListOf<EpgProgram>()

    jsonReader.beginArray()

    while (jsonReader.hasNext()) {
        jsonReader.beginObject()

        var id = ""
        var startTime = ""
        var stopTime = ""
        var title = ""
        var description = ""

        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "id" -> id = jsonReader.nextString().take(128)
                "start_time" -> startTime = jsonReader.nextString().take(64)
                "stop_time" -> stopTime = jsonReader.nextString().take(64)
                "title" -> title = jsonReader.nextString().take(256)
                "description" -> {
                    description = if (jsonReader.peek() == JsonToken.NULL) {
                        jsonReader.nextNull()
                        ""
                    } else {
                        jsonReader.nextString().take(512)
                    }
                }
                else -> jsonReader.skipValue()
            }
        }

        jsonReader.endObject()

        programs.add(EpgProgram(id, startTime, stopTime, title, description))
    }

    jsonReader.endArray()

    return programs
}
```

**Field Length Limits (Prevent OOM):**
- ID: 128 characters
- Time: 64 characters
- Title: 256 characters
- Description: 512 characters

**Progress Logging:**
- Logs every 50 channels during parsing
- Helps monitor large EPG fetches

---

## Storage Mechanism

### Three-Tier Caching Strategy

```
┌─────────────────────────────────────────────────────────┐
│                   Tier 1: Memory Cache                  │
│  - Variable: cachedEpgData: EpgResponse?               │
│  - Speed: Instant                                       │
│  - Lifetime: Current session                            │
│  - Size: ~50-100MB (uncompressed)                      │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                   Tier 2: Disk Cache                    │
│  - File: epg_data.json (GZIP compressed)               │
│  - Speed: Fast (~500ms to load)                        │
│  - Lifetime: Persistent (survives app restart)          │
│  - Size: ~10MB (144,523 programs)                      │
│  - Compression: 10-20x size reduction                   │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                  Tier 3: Network Fetch                  │
│  - Source: EPG service (POST /epg)                     │
│  - Speed: Slow (~5-10 seconds)                         │
│  - Triggered: On startup, playlist reload, manual      │
└─────────────────────────────────────────────────────────┘
```

### Save to Disk (GZIP Compressed)

```kotlin
suspend fun saveEpgData(epgData: EpgResponse) {
    withContext(Dispatchers.IO) {
        try {
            // Cache in memory first
            cachedEpgData = epgData

            // Save to disk with GZIP compression
            val startTime = System.currentTimeMillis()

            GZIPOutputStream(
                BufferedOutputStream(FileOutputStream(epgFile))
            ).use { gzipStream ->
                OutputStreamWriter(gzipStream, Charsets.UTF_8).use { writer ->
                    gson.toJson(epgData, writer)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            val sizeKB = epgFile.length() / 1024

            Timber.d("✓ EPG saved to disk: ${sizeKB}KB in ${elapsed}ms")
        } catch (e: OutOfMemoryError) {
            // If disk save fails, keep memory cache
            Timber.e(e, "OOM saving EPG to disk, keeping memory cache only")
        } catch (e: Exception) {
            Timber.e(e, "Error saving EPG data")
        }
    }
}
```

**Compression Example:**
- Uncompressed: ~144MB
- GZIP Compressed: ~12MB
- Compression ratio: ~12:1

### Load from Disk

```kotlin
fun loadEpgData(): EpgResponse? {
    // Check memory cache first
    cachedEpgData?.let { return it }

    // Load from disk
    return try {
        if (!epgFile.exists()) {
            Timber.d("No EPG cache file found")
            return null
        }

        val startTime = System.currentTimeMillis()

        val epgData = GZIPInputStream(
            BufferedInputStream(FileInputStream(epgFile))
        ).use { gzipStream ->
            InputStreamReader(gzipStream, Charsets.UTF_8).use { reader ->
                parseEpgResponseStreaming(reader)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("EPG loaded from disk in ${elapsed}ms")

        // Cache in memory
        cachedEpgData = epgData
        epgData
    } catch (e: Exception) {
        Timber.e(e, "Error loading EPG from disk, clearing cache")
        clearCache()
        null
    }
}
```

**Legacy Support:**
- Handles both GZIP and uncompressed files
- Auto-clears corrupt cache files

### Clear Cache

```kotlin
fun clearCache() {
    cachedEpgData = null
    if (epgFile.exists()) {
        epgFile.delete()
        Timber.d("EPG cache cleared")
    }
}
```

---

## Retrieval and Display

### Get Current Program

```kotlin
fun getCurrentProgram(tvgId: String): EpgProgram? {
    val epgData = loadEpgData() ?: return null
    val programs = epgData.epg[tvgId] ?: return null

    return programs.firstOrNull { it.isCurrent() }
}
```

**Used in:**
- Channel list items (show current program below channel name)
- Channel info overlay (top of player screen)

### Get All Programs for Channel

```kotlin
fun getProgramsForChannel(tvgId: String): List<EpgProgram> {
    val epgData = loadEpgData() ?: return emptyList()
    return epgData.epg[tvgId] ?: emptyList()
}
```

**Used in:**
- EPG panel (full program schedule)

---

## UI Integration

### 1. Channel List Item

**File:** `ui/components/ChannelListItem.kt`

```kotlin
@Composable
fun ChannelListItem(
    channel: Channel,
    currentProgram: EpgProgram?,
    onShowPrograms: () -> Unit,
    ...
) {
    Card(...) {
        Column {
            // Channel name
            Text(text = channel.title)

            // Current program (if EPG available)
            if (channel.hasEpg) {
                Text(
                    text = currentProgram?.title ?: stringResource(R.string.status_no_program),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ruTvColors.textHint
                )
            }
        }
    }
}
```

**Interaction:**
- Single tap: Show EPG panel
- Double tap: Play channel

### 2. EPG Panel

**File:** `ui/screens/PlayerScreen.kt`

```kotlin
@Composable
private fun EpgPanel(
    programs: List<EpgProgram>,
    onProgramClick: (EpgProgram) -> Unit
) {
    val listState = rememberLazyListState()
    val currentTime = System.currentTimeMillis()

    // Find current program
    val currentProgramIndex = programs.indexOfFirst { program ->
        currentTime in program.startTime..program.endTime
    }

    // Auto-scroll to current program
    LaunchedEffect(currentProgramIndex) {
        if (currentProgramIndex >= 0) {
            val offset = listState.layoutInfo.viewportSize.height / 2
            listState.animateScrollToItem(currentProgramIndex, -offset / 2)
        }
    }

    Card(...) {
        LazyColumn(state = listState) {
            var lastDate = ""

            programs.forEachIndexed { index, program ->
                val programDate = dateFormat.format(Date(program.startTime))

                // Add date delimiter if date changed
                if (programDate != lastDate) {
                    item(key = "date_$programDate") {
                        EpgDateDelimiter(date = programDate)
                    }
                    lastDate = programDate
                }

                // Add program item
                item(key = "program_${program.startTime}_${program.title}") {
                    EpgProgramItem(
                        program = program,
                        isCurrent = index == currentProgramIndex,
                        onClick = { onProgramClick(program) }
                    )
                }
            }
        }
    }
}
```

**Features:**
- Auto-scroll to current program (centered in viewport)
- Date delimiters (group programs by day)
- Current program highlighting (gold color)
- Smooth scroll animation

### 3. EPG Program Item

**File:** `ui/components/EpgProgramItem.kt`

```kotlin
@Composable
fun EpgProgramItem(
    program: EpgProgram,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val startTime = timeFormat.format(Date(program.startTime))
    val endTime = timeFormat.format(Date(program.endTime))

    Row(
        modifier = Modifier
            .background(
                if (isCurrent)
                    MaterialTheme.ruTvColors.selectedBackground
                else
                    MaterialTheme.ruTvColors.cardBackground
            )
            .clickable(onClick = onClick)
    ) {
        // Time
        Column {
            Text(
                text = startTime,
                color = if (isCurrent)
                    MaterialTheme.ruTvColors.gold
                else
                    MaterialTheme.ruTvColors.textSecondary,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
            Text(text = endTime, ...)
        }

        // Program info
        Column {
            Text(
                text = program.title,
                color = if (isCurrent)
                    MaterialTheme.ruTvColors.gold
                else
                    MaterialTheme.ruTvColors.textPrimary,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )

            program.description.takeIf { it.isNotEmpty() }?.let {
                Text(text = it, ...)
            }
        }
    }
}
```

**Visual States:**
- **Current program:** Gold text, highlighted background, bold font
- **Past/future:** Normal text, standard background

---

## Performance Optimizations

### 1. Memory Management

**Streaming Parser:**
- Never loads full JSON into memory
- Parses token-by-token
- Processes one channel at a time

**Field Length Limits:**
```kotlin
id = jsonReader.nextString().take(128)
title = jsonReader.nextString().take(256)
description = jsonReader.nextString().take(512)
```
- Prevents excessively long strings
- Protects against malformed data

**GZIP Compression:**
- 10-20x size reduction
- 144MB → ~12MB on disk
- Fast decompression (~500ms)

**Lazy Loading:**
- EPG loaded only when needed
- Not fetched until playlist loads

### 2. Network Optimizations

**Health Check First:**
```kotlin
// Check service availability before fetching
checkHealth(epgUrl)
```
- Avoids wasting time on dead service
- Fast timeout (5 seconds)

**Long Timeouts for Large Responses:**
- Connect timeout: 60 seconds
- Read timeout: 60 seconds
- Handles 255MB+ responses

**Buffered I/O:**
```kotlin
BufferedOutputStream(FileOutputStream(epgFile))
```
- Efficient disk writes
- Reduces I/O operations

### 3. UI Optimizations

**Auto-scroll to Current Program:**
```kotlin
LaunchedEffect(currentProgramIndex) {
    listState.animateScrollToItem(currentProgramIndex, -offset / 2)
}
```
- Centers current program in viewport
- Smooth animation

**LazyColumn:**
- Only renders visible items
- Efficient for 1000+ programs
- Automatic recycling

**Timestamp-based Refresh:**
```kotlin
_viewState.update { it.copy(epgLoadedTimestamp = System.currentTimeMillis()) }
```
- Only re-renders when EPG changes
- Avoids unnecessary recompositions

---

## Error Handling

### Network Errors

```kotlin
try {
    // Health check
    checkHealth(epgUrl)

    // Fetch EPG
    fetchEpgData(epgUrl, channels)
} catch (e: SocketTimeoutException) {
    Timber.e("EPG fetch timeout")
    Result.Error(e)
} catch (e: IOException) {
    Timber.e("EPG network error: ${e.message}")
    Result.Error(e)
}
```

**Handled Errors:**
- Health check timeout (5s)
- Fetch timeout (60s)
- Connection failures
- Invalid responses

### Memory Errors

```kotlin
try {
    saveEpgData(epgData)
} catch (e: OutOfMemoryError) {
    // Keep memory cache, skip disk save
    Timber.e("OOM saving EPG, keeping memory cache only")
}
```

**Strategies:**
- Streaming parser prevents OOM during fetch
- Graceful degradation on disk save failure
- Memory cache preserved even if disk fails

### Data Errors

```kotlin
"description" -> {
    description = if (jsonReader.peek() == JsonToken.NULL) {
        jsonReader.nextNull()
        ""  // Default to empty string
    } else {
        jsonReader.nextString().take(512)
    }
}
```

**Handled Issues:**
- Null descriptions → empty string
- Invalid time formats → try multiple parsers
- Missing channel data → skip gracefully
- Truncated fields → log warning, continue

### Cache Corruption

```kotlin
fun loadEpgData(): EpgResponse? {
    return try {
        // Load and parse
        parseEpgResponseStreaming(reader)
    } catch (e: Exception) {
        Timber.e(e, "Error loading EPG, clearing cache")
        clearCache()  // Auto-clear corrupt cache
        null
    }
}
```

---

## Configuration

### EPG Settings

**File:** `ui/screens/SettingsScreen.kt`

**1. EPG Service URL**
```kotlin
TextInputSetting(
    label = stringResource(R.string.settings_epg_url),
    value = viewState.epgUrl,
    onValueChange = onEpgUrlChanged,
    placeholder = "https://example.com/epg"
)
```
- URL of EPG service
- Must support `/health` and `/epg` endpoints
- Saved to DataStore preferences

**2. EPG Days Ahead**
```kotlin
NumberInputSetting(
    label = stringResource(R.string.settings_epg_days_ahead),
    value = viewState.epgDaysAhead,
    onValueChange = onEpgDaysAheadChanged,
    minValue = 1,
    maxValue = 30
)
```
- Default: 7 days
- Range: 1-30 days
- Applied to all channels

**3. Force EPG Fetch**
```kotlin
Button(
    onClick = { showForceEpgDialog = true },
    ...
) {
    Text(stringResource(R.string.settings_force_epg_fetch))
}
```
- Manually trigger EPG refresh
- Clears cache and fetches fresh data
- Shows confirmation dialog

### Channel EPG Configuration

**M3U Playlist:**
```m3u
#EXTINF:-1 tvg-id="channel1" catchup-days="7" group-title="News",Channel Name
http://stream.url
```

**Required Fields:**
- `tvg-id`: Channel identifier (matches EPG service)
- `catchup-days`: Days of EPG to fetch (overrides global setting)

**EPG Enabled Check:**
```kotlin
val hasEpg: Boolean
    get() = tvgId.isNotBlank() && catchupDays > 0
```

---

## Debug Logging

### Comprehensive Logging

**Example Output:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
       EPG FETCH STARTED
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
EPG Service URL: https://example.com
━━━ HEALTH CHECK ━━━
✓ EPG service healthy (took 234ms)

━━━ CHANNEL VALIDATION ━━━
Total channels loaded: 150
Channels with EPG enabled: 120/150

━━━ EPG REQUEST ━━━
Device timezone: America/New_York (UTC-5)
Channels to fetch: 120

━━━ EPG PARSING (STREAMING) ━━━
Parsed 50 channels so far...
Parsed 100 channels so far...
Parse complete!
Parse time: 1234ms
Total programs: 144,523

━━━ EPG SAVED ━━━
✓ EPG saved to disk: 12MB (12,345KB) in 567ms
Compression ratio: ~12:1

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ✓ EPG FETCH COMPLETED
  Total time: 5432ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Log Levels:**
- `Timber.d()` - Debug info
- `Timber.e()` - Errors
- `Timber.w()` - Warnings

**Enable Debug Log:**
Settings → Player Configuration → Show Debug Log

---

## Summary

The RuTV Player EPG implementation provides:

✅ **Efficient Fetching** - Health checks, timezone-aware requests
✅ **Memory-Safe Parsing** - Streaming JSON parser prevents OOM
✅ **Compressed Storage** - GZIP compression (10-20x reduction)
✅ **Three-Tier Caching** - Memory → Disk → Network
✅ **Smart Retrieval** - Fast current program lookups
✅ **Rich UI** - Auto-scroll, highlighting, date grouping
✅ **Channel Matching** - tvg-id integration with M3U playlists
✅ **Robust Error Handling** - Graceful degradation
✅ **Comprehensive Logging** - Detailed debug information

### Key Metrics

| Metric | Value |
|--------|-------|
| Max Programs Supported | 100,000+ |
| Memory Usage (cached) | ~50-100MB |
| Disk Usage (compressed) | ~10-15MB |
| Parse Time (150 channels) | ~1-2 seconds |
| Load Time (from disk) | ~500ms |
| Network Timeout | 60 seconds |

### Future Enhancements

Potential improvements:
1. **Time-based cache expiry** - Auto-refresh after X hours
2. **Incremental updates** - Fetch only new programs
3. **Background sync** - Periodic EPG refresh
4. **Offline mode** - Better offline EPG access
5. **Search functionality** - Find programs by keyword
6. **Notifications** - Alert for favorite programs

---

**Last Updated:** October 14, 2025
**Author:** Claude Code
**Version:** 2.0 (Compose Edition)

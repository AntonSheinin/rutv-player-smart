# EPG Architecture - Visual Diagrams

## Complete EPG System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│                          EPG DATA FLOW ARCHITECTURE                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  1. DATA SOURCE                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────┐                    ┌─────────────────────┐          │
│  │  M3U Playlist    │                    │  EPG Service API    │          │
│  │  ─────────────   │                    │  ───────────────    │          │
│  │  tvg-id: "ch1"   │────────────────────│  GET /health        │          │
│  │  catchup-days: 7 │   Matches via      │  POST /epg          │          │
│  └──────────────────┘   tvg-id           └─────────────────────┘          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│  2. FETCH & PARSE LAYER                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────┐         │
│  │  FetchEpgUseCase                                              │         │
│  │  ───────────────                                              │         │
│  │  1. Health Check (5s timeout)                                │         │
│  │  2. Build Request (timezone + channels)                      │         │
│  │  3. POST /epg (60s timeout)                                  │         │
│  │  4. Trigger Streaming Parser                                 │         │
│  └───────────────────────────────────────────────────────────────┘         │
│                                    ↓                                        │
│  ┌───────────────────────────────────────────────────────────────┐         │
│  │  Streaming JSON Parser                                        │         │
│  │  ─────────────────────                                        │         │
│  │  • Reads token-by-token (prevents OutOfMemoryError)          │         │
│  │  • Field length limits (id: 128, title: 256, desc: 512)      │         │
│  │  • Progress logging (every 50 channels)                      │         │
│  │  • Parses one channel at a time                              │         │
│  │                                                               │         │
│  │  Example:                                                     │         │
│  │  { "epg": {                                                   │         │
│  │      "channel1": [...]  ← Parse this                         │         │
│  │      "channel2": [...]  ← Then this                          │         │
│  │      "channel3": [...]  ← Then this                          │         │
│  │  }}                                                           │         │
│  │                                                               │         │
│  │  Never loads entire 255MB response into memory!              │         │
│  └───────────────────────────────────────────────────────────────┘         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│  3. STORAGE LAYER (3-Tier Caching)                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │  Tier 1: Memory Cache                                       │           │
│  │  ─────────────────────                                      │           │
│  │  Variable: cachedEpgData: EpgResponse?                      │           │
│  │  Speed: Instant (no I/O)                                    │           │
│  │  Size: ~50-100MB (uncompressed)                             │           │
│  │  Lifetime: Current session only                             │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │  Tier 2: Disk Cache (GZIP)                                 │           │
│  │  ──────────────────────────                                 │           │
│  │  File: app/files/epg_data.json                              │           │
│  │  Speed: Fast (~500ms)                                       │           │
│  │  Size: ~10-15MB (GZIP compressed)                           │           │
│  │  Compression: 10-20x reduction                              │           │
│  │  Lifetime: Persistent (survives app restart)                │           │
│  │                                                              │           │
│  │  Example:                                                    │           │
│  │  144MB uncompressed → 12MB compressed                       │           │
│  │  144,523 programs for 120 channels                          │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │  Tier 3: Network Fetch                                      │           │
│  │  ──────────────────────                                     │           │
│  │  Speed: Slow (~5-10 seconds)                                │           │
│  │  Size: 50-255MB raw JSON                                    │           │
│  │  Triggered:                                                  │           │
│  │    • App startup (after loading cache)                      │           │
│  │    • Playlist reload                                        │           │
│  │    • Manual "Force EPG Fetch"                               │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│  4. RETRIEVAL LAYER                                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │  EpgRepository                                              │           │
│  │  ─────────────                                              │           │
│  │                                                              │           │
│  │  getCurrentProgram(tvgId: String): EpgProgram?              │           │
│  │  ├─ Load from cache (memory → disk → null)                 │           │
│  │  ├─ Get programs for channel                                │           │
│  │  └─ Return first where isCurrent() = true                   │           │
│  │                                                              │           │
│  │  getProgramsForChannel(tvgId: String): List<EpgProgram>     │           │
│  │  ├─ Load from cache                                         │           │
│  │  └─ Return all programs for channel                         │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│  5. VIEWMODEL LAYER                                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │  MainViewModel                                              │           │
│  │  ─────────────                                              │           │
│  │                                                              │           │
│  │  init {                                                      │           │
│  │      loadCachedEpg()      // Load from disk on startup     │           │
│  │      loadPlaylist()       // Then fetch fresh EPG          │           │
│  │  }                                                           │           │
│  │                                                              │           │
│  │  showEpgForChannel(tvgId) {                                 │           │
│  │      programs = epgRepository.getProgramsForChannel(tvgId)  │           │
│  │      currentProgram = epgRepository.getCurrentProgram(tvgId)│           │
│  │      _viewState.update { ... }                              │           │
│  │  }                                                           │           │
│  │                                                              │           │
│  │  updateCurrentProgram(channel) {                            │           │
│  │      currentProgram = epgRepository.getCurrentProgram(...)  │           │
│  │      _viewState.update { ... }                              │           │
│  │  }                                                           │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│  6. UI LAYER (Jetpack Compose)                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────────┐  ┌───────────────────┐  ┌──────────────────┐   │
│  │  Channel List         │  │  Channel Info     │  │  EPG Panel       │   │
│  │  ────────────         │  │  ────────────     │  │  ─────────       │   │
│  │  Shows:               │  │  Shows:           │  │  Shows:          │   │
│  │  • Channel name       │  │  • #1 • Channel   │  │  • All programs  │   │
│  │  • Channel logo       │  │  • Current prog   │  │  • Date groups   │   │
│  │  • Current program ←──┼──┤    title          │  │  • Times         │   │
│  │  • Favorite star      │  │                   │  │  • Descriptions  │   │
│  │                       │  │  Location:        │  │                  │   │
│  │  Interaction:         │  │  Top of player    │  │  Highlights:     │   │
│  │  • Single tap → EPG   │  │                   │  │  • Current prog  │   │
│  │  • Double tap → Play  │  │                   │  │    in gold       │   │
│  └───────────────────────┘  └───────────────────┘  │  • Auto-scroll   │   │
│                                                     │    to current    │   │
│                                                     └──────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## EPG Panel Auto-Scroll Logic

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│                    EPG PANEL AUTO-SCROLL                        │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. User Opens EPG Panel (single tap on channel)               │
│                                                                 │
│  2. Find Current Program Index:                                │
│                                                                 │
│     programs.indexOfFirst { program ->                         │
│         currentTime in program.startTime..program.endTime      │
│     }                                                           │
│                                                                 │
│  3. LaunchedEffect (triggers on panel open):                   │
│                                                                 │
│     ┌─────────────────────────────────────┐                    │
│     │  Viewport                           │                    │
│     │  ┌───────────────────────┐          │                    │
│     │  │                       │          │                    │
│     │  │   (above current)     │          │                    │
│     │  │                       │          │                    │
│     │  ├───────────────────────┤ ← Offset │                    │
│     │  │  🟡 CURRENT PROGRAM   │ ← Center │                    │
│     │  ├───────────────────────┤          │                    │
│     │  │                       │          │                    │
│     │  │   (below current)     │          │                    │
│     │  │                       │          │                    │
│     │  └───────────────────────┘          │                    │
│     └─────────────────────────────────────┘                    │
│                                                                 │
│  4. Calculation:                                                │
│                                                                 │
│     val offset = viewportHeight / 2 - itemHeight / 2           │
│     listState.animateScrollToItem(                             │
│         index = currentProgramIndex,                           │
│         scrollOffset = -offset / 2                             │
│     )                                                           │
│                                                                 │
│  5. Result: Current program centered in viewport               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Data Model Relationships

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                     EPG DATA MODEL STRUCTURE                     │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  EpgResponse                                                     │
│  ────────────                                                    │
│  ┌────────────────────────────────────────────────────┐         │
│  │ updateMode: "force"                                │         │
│  │ timestamp: "2025-10-14T12:00:00Z"                  │         │
│  │ channelsRequested: 120                             │         │
│  │ channelsFound: 115                                 │         │
│  │ totalPrograms: 144,523                             │         │
│  │ epg: Map<String, List<EpgProgram>>                 │         │
│  │      ├─ "channel1" → [program1, program2, ...]     │         │
│  │      ├─ "channel2" → [program1, program2, ...]     │         │
│  │      └─ "channel3" → [program1, program2, ...]     │         │
│  └────────────────────────────────────────────────────┘         │
│                          ↓                                       │
│  EpgProgram (one program)                                        │
│  ────────────────────────                                        │
│  ┌────────────────────────────────────────────────────┐         │
│  │ id: "prog123"                                      │         │
│  │ startTime: 1728936000000  (Long - Unix timestamp) │         │
│  │ stopTime:  1728939600000  (Long - Unix timestamp) │         │
│  │ title: "News Tonight"                              │         │
│  │ description: "Evening news program..."             │         │
│  │                                                     │         │
│  │ Methods:                                            │         │
│  │ ├─ isCurrent(): Boolean                            │         │
│  │ │   └─ now in startTime..stopTime                  │         │
│  │ └─ isEnded(): Boolean                              │         │
│  │     └─ now > stopTime                              │         │
│  └────────────────────────────────────────────────────┘         │
│                          ↓                                       │
│  Channel (from M3U)                                              │
│  ───────────────────                                             │
│  ┌────────────────────────────────────────────────────┐         │
│  │ tvgId: "channel1"  ← Maps to EPG data             │         │
│  │ catchupDays: 7     ← Days of EPG to fetch         │         │
│  │ title: "Channel Name"                              │         │
│  │ logo: "https://..."                                │         │
│  │ group: "News"                                      │         │
│  │                                                     │         │
│  │ Computed:                                           │         │
│  │ └─ hasEpg: Boolean                                 │         │
│  │     └─ tvgId.isNotBlank() && catchupDays > 0       │         │
│  └────────────────────────────────────────────────────┘         │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

## Memory Optimization - Streaming Parser

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│          TRADITIONAL PARSER vs STREAMING PARSER                  │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ❌ TRADITIONAL PARSER (OutOfMemoryError)                        │
│  ────────────────────────────────────────                        │
│                                                                  │
│  1. Read entire response into String                            │
│     ↓ 255MB in memory                                           │
│  2. Parse entire String to JSON object                          │
│     ↓ 500MB in memory (doubled!)                                │
│  3. Convert JSON to EpgResponse                                 │
│     ↓ 300MB in memory                                           │
│                                                                  │
│  Total Memory: 500MB+                                           │
│  Result: OutOfMemoryError on Android TV (512MB RAM)             │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ✅ STREAMING PARSER (Memory Efficient)                          │
│  ───────────────────────────────────────                        │
│                                                                  │
│  1. Open stream to response                                     │
│     ↓ ~0MB (buffered reader)                                    │
│  2. Read token by token:                                        │
│     ┌────────────────────────────┐                              │
│     │ Read: "epg"                │ 0.001MB                      │
│     │ Read: "{"                  │ 0.001MB                      │
│     │ Read: "channel1"           │ 0.001MB                      │
│     │ Read: "["                  │ 0.001MB                      │
│     │ Read program object        │ 0.01MB                       │
│     │ Add to map                 │ (accumulates slowly)         │
│     │ Read next program          │ 0.01MB                       │
│     │ ...                        │                              │
│     │ Read: "]"                  │                              │
│     │ Move to next channel       │                              │
│     └────────────────────────────┘                              │
│  3. Build EpgResponse incrementally                             │
│     ↓ 50MB final object                                         │
│                                                                  │
│  Peak Memory: ~50-100MB                                         │
│  Result: Success! ✅                                             │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

## Cache Strategy Decision Tree

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│              EPG CACHE STRATEGY DECISION TREE               │
│                                                             │
└─────────────────────────────────────────────────────────────┘

            Need EPG Data?
                 ├─ Yes
                 │
                 ↓
         Check Memory Cache
                 │
         ┌───────┴────────┐
         │                │
       Found?           Not Found
         │                │
         ↓                ↓
     Return Data    Check Disk Cache
     (Instant)            │
                   ┌──────┴──────┐
                   │             │
                 Found?      Not Found
                   │             │
                   ↓             ↓
              Load & Parse   Fetch from Network
              (~500ms)          (~5-10s)
                   │             │
                   ↓             ↓
              Cache in      Parse with Streaming
              Memory        Parser
                   │             │
                   ↓             ↓
              Return Data   Cache in Memory
                                │
                                ↓
                           Save to Disk
                           (GZIP compressed)
                                │
                                ↓
                           Return Data


┌─────────────────────────────────────────────────────────────┐
│  Cache Hit Scenarios:                                       │
│                                                              │
│  1. Same session, multiple requests                         │
│     → Memory cache hit (instant)                            │
│                                                              │
│  2. App restart within same day                             │
│     → Disk cache hit (~500ms)                               │
│                                                              │
│  3. Manual "Force EPG Fetch"                                │
│     → Network fetch (clears cache first)                    │
│                                                              │
│  4. Playlist reload                                         │
│     → Network fetch (new channels may need EPG)             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## EPG Service API Contract

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│                  EPG SERVICE API CONTRACT                   │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Endpoint 1: Health Check                                  │
│  ─────────────────────────                                  │
│                                                             │
│  GET {epgUrl}/health                                        │
│                                                             │
│  Response:                                                  │
│  {                                                          │
│    "status": "healthy",                                     │
│    "timestamp": "2025-10-14T12:00:00Z"                      │
│  }                                                          │
│                                                             │
│  Timeout: 5 seconds                                         │
│  Expected: 200 OK                                           │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Endpoint 2: Fetch EPG                                     │
│  ───────────────────────                                    │
│                                                             │
│  POST {epgUrl}/epg                                          │
│  Content-Type: application/json                             │
│                                                             │
│  Request Body:                                              │
│  {                                                          │
│    "channels": [                                            │
│      {                                                      │
│        "xmltvId": "channel1",                               │
│        "epgDepth": 7                                        │
│      },                                                     │
│      {                                                      │
│        "xmltvId": "channel2",                               │
│        "epgDepth": 3                                        │
│      }                                                      │
│    ],                                                       │
│    "update": "force",                                       │
│    "timezone": "America/New_York"                           │
│  }                                                          │
│                                                             │
│  Response Body:                                             │
│  {                                                          │
│    "updateMode": "force",                                   │
│    "timestamp": "2025-10-14T12:00:00Z",                     │
│    "channelsRequested": 2,                                  │
│    "channelsFound": 2,                                      │
│    "totalPrograms": 336,                                    │
│    "epg": {                                                 │
│      "channel1": [                                          │
│        {                                                    │
│          "id": "prog1",                                     │
│          "start_time": "2025-10-14T20:00:00-05:00",         │
│          "stop_time": "2025-10-14T21:00:00-05:00",          │
│          "title": "News Tonight",                           │
│          "description": "Evening news"                      │
│        },                                                   │
│        ...                                                  │
│      ],                                                     │
│      "channel2": [...]                                      │
│    }                                                        │
│  }                                                          │
│                                                             │
│  Timeout: 60 seconds                                        │
│  Expected: 200 OK                                           │
│  Size: 50MB - 255MB (large responses supported)            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

**Generated by:** Claude Code
**Date:** October 14, 2025
**Version:** 2.0

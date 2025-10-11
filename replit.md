# Android IPTV Player

## Overview
The Android IPTV Player is a native Android application designed for robust IPTV playlist playback, offering extensive codec support via FFmpeg and seamless interaction with Flussonic Media Server using token authentication. Its primary purpose is to provide a high-performance, user-friendly video player capable of handling various IPTV stream formats and offering advanced features such as channel caching, favorites management, per-channel aspect ratio persistence, and an integrated Electronic Program Guide (EPG). The project aims to deliver a top-tier media consumption experience on Android devices.

## User Preferences
- I prefer simple language and clear explanations.
- I like an iterative development approach.
- Ask me before making any major architectural changes or decisions.
- Do not make changes to the folder `Z`.
- Do not make changes to the file `Y`.

## Recent Changes
- **October 11, 2025**: Added "🔄 Reload Current Playlist" button in Settings:
  - Gold button that clears cache and reloads playlist from original source (file or URL)
  - Fixes issue where old cached playlists don't have tvg-id/catchup-days saved
  - Shows confirmation dialog explaining the reload will refresh all EPG data
  - Essential for users upgrading from versions before the cache preservation fix
- **October 11, 2025**: Comprehensive EPG debugging logs:
  - Added detailed logging throughout EPG fetch, parse, and display pipeline
  - Shows which channels have tvg-id vs which don't
  - Logs EPG service status, program lookups, and display updates
  - Helps diagnose EPG issues with specific error messages
- **October 11, 2025**: EPG Out of Memory crash fix:
  - **Stream JSON parsing**: Changed from loading entire 180MB+ response into memory to streaming JSON parsing
  - **Memory-first caching**: EPG data cached in memory first, disk save is optional with graceful OOM handling
  - **Specific OOM handling**: Added OutOfMemoryError catch blocks with helpful error messages
- **October 11, 2025**: Critical EPG fixes - crash, null handling, and cache preservation:
  - **EPG null crash fix**: Changed EpgProgramsAdapter to use `.isNullOrBlank()` instead of `.isNotBlank()` for description field to handle null values from EPG API
  - **Cache preservation fix**: Added `catchupDays` field to VideoItem data class and updated ChannelStorage to save/load both `tvgId` and `catchupDays` in JSON cache, preventing EPG data loss after loading from cache
  - **Threading fix**: Added runOnUiThread wrapper for programsAdapter.updatePrograms() to ensure RecyclerView updates happen on main thread
  - **Auto-reload fix**: Removed destructive prefs.edit().clear() in error handler that was wiping all settings on errors
- **October 11, 2025**: CRITICAL EPG performance fix:
  - **Fixed crash/freeze bug**: EPG data is now cached in memory instead of loading from disk for EVERY channel on EVERY UI update
  - **Before**: 100 channels = 100 file reads + JSON parses on every scroll/update (caused freezes and crashes)
  - **After**: Data loaded once and cached in memory for instant access
  - **Impact**: Massive performance improvement, especially with large playlists (100+ channels)
- **October 11, 2025**: EPG fetching optimization:
  - **Single request**: EPG fetches ALL channels in ONE request to the server (no batching)
  - **Background thread**: Uses Dispatchers.IO coroutine to prevent UI freezing
  - **Memory cache**: EPG data cached in memory after loading to prevent repeated disk I/O
  - **60s timeout**: Extended timeout for large channel lists
- **October 11, 2025**: EPG UI/UX enhancements:
  - **EPG sync with playlist**: EPG window now hides automatically when channel list closes or when switching channels
  - **Background indicator**: Channel with EPG open shows dark gold background (#3A3A00) instead of icon for cleaner look
  - **No program fallback**: Channels with tvg-id but no current EPG data now display "no program" text
  - **Centered current program**: When EPG opens, current program is auto-scrolled to center of screen for better visibility
  - **Fixed auto-close bug**: EPG now stays open when player controls auto-hide, only closes on explicit user action
  - **Improved colors**: Updated EPG with modern dark theme - gold (#FFD700) date headers, better text contrast, cleaner styling
  - **Selectable programs**: EPG items now clickable with gold highlight (#2A2500 background) when selected, ready for future functionality
  - **Auto-select current**: When opening EPG, current/live program is automatically selected and scrolled into view with green indicator
  - **Distinguished dates**: Date delimiters now use gold uppercase text with letter spacing on darker background for better visibility
  - **Faded ended programs**: Past/ended programs now display at 40% opacity to visually distinguish them from current and upcoming programs
- **October 11, 2025**: Performance and UX improvements:
  - **Toast removal**: Removed all ~30 Toast messages across the app (were causing UI slowness)
  - **EPG fresh install fix**: Added fetchEpgData() call in settingsLauncher callback so EPG works after loading playlist from settings
  - **Decoder optimization**: Only release/reinit player when decoder settings actually change (tracks audio/video FFmpeg state)
  - **Playlist visibility fix**: Channel/favorite list now ONLY hides on channel switch or close button click, NOT when player controls auto-hide
- **October 11, 2025**: Critical EPG fixes - crash, null handling, and cache preservation:
  - **EPG null crash fix**: Changed EpgProgramsAdapter to use `.isNullOrBlank()` instead of `.isNotBlank()` for description field to handle null values from EPG API
  - **Cache preservation fix**: Added `catchupDays` field to VideoItem data class and updated ChannelStorage to save/load both `tvgId` and `catchupDays` in JSON cache, preventing EPG data loss after loading from cache
  - **Threading fix**: Added runOnUiThread wrapper for programsAdapter.updatePrograms() to ensure RecyclerView updates happen on main thread
  - **Auto-reload fix**: Removed destructive prefs.edit().clear() in error handler that was wiping all settings on errors
- **October 11, 2025**: Dependency cleanup:
  - Removed unused Media3 modules (DASH, RTSP, SmoothStreaming) and unused imports to reduce APK size

## System Architecture

### UI/UX Decisions
The application features a phone-optimized touch interface with tap-to-toggle controls and fullscreen playback with auto-hiding UI elements. The channel list is compact, displaying numbers first and 40dp logos. All player controls (playlist, navigation, aspect ratio, orientation, settings) are integrated into a single, horizontally aligned row. The channel list automatically closes with control hiding and requires explicit user action to reopen. A "▶ Playing" indicator is persistently displayed for the current channel, and the app uses a redesigned RuTV logo and branding. The EPG programs panel features a dark background with date delimiters and green dot indicators for current programs. Touch gestures differentiate between single tap (show programs panel) and double tap (play channel).

### Technical Implementations
The core player is a native Android application built with Kotlin and Media3 (ExoPlayer), targeting Min SDK 24 and Target SDK 34. It utilizes NextLib FFmpeg for extensive software decoding of both audio (Vorbis, Opus, FLAC, MP2, MP3, AAC, AC3) and video (H.264, HEVC, VP8, VP9), with independent toggles for each and hardware decoding as a default option. Playlists (M3U/M3U8) are supported from local files or URLs, with channel caching via `ChannelStorage.kt` and content-based hash detection. Features include favorites management, per-channel aspect ratio persistence, last channel resume, and configurable buffering settings (5-60 seconds). The player supports video rotation (0° and 270°) using TextureView. Robust error handling, including automatic clearing of corrupted playlists and detailed logging, is implemented. HLS streams are optimized with specific HlsMediaSource.Factory and aggressive TS flags. The architecture leverages lifecycle-aware coroutines for asynchronous operations and proper resource management. The app has been rebranded as "RuTV".

### EPG (Electronic Program Guide) Features
The EPG integrates with a configurable service URL, supporting health checks and forced updates. Program data is fetched via POST requests using channel `tvg-id` and `catchup-days` parameters, including start/stop times, titles, and descriptions. This data is saved locally as JSON for offline access. Users can single-tap a channel to view the programs panel or double-tap to play the channel. The right-side panel displays date-delimited program lists with start times and a green dot indicator for the current program. Current program information is also shown in the channel list and player controls in gold text. EPG data is automatically fetched on app startup after a health check.

### Feature Specifications
The player supports M3U/M3U8 IPTV playlist loading from files or URLs. All audio formats are supported via FFmpeg. Auto-advance to the next video and repeat all modes are available. Subtitles are disabled by default. An on-screen, toggleable debug log provides decoder diagnostics. Channel information (number and name) is displayed and hides with controls. A playlist close button dismisses the channel list. Smart playlist reloading uses content hash detection for URL playlists. Direct channel navigation is possible via a numeric keypad dialog. Toggle switches (FFmpeg, Debug Log) use a gold color (#FFD700) when enabled for consistent branding.

## External Dependencies
- **AndroidX Media3**: `androidx.media3:media3-exoplayer`, `androidx.media3:media3-ui`, `androidx.media3:media3-common`, `androidx.media3:media3-exoplayer-hls`
- **NextLib FFmpeg**: `io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0` (for audio and video FFmpeg decoding)
- **Gson**: `com.google.code.gson:gson:2.10.1` (for JSON serialization)
- **Flussonic Media Server**: For IPTV streaming, using built-in token authentication.
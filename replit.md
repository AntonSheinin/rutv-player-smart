# Android IPTV Player

## Overview
The Android IPTV Player is a native Android application built with Media3 (ExoPlayer) for robust IPTV playlist playback. It offers extensive codec support via FFmpeg and is designed for seamless interaction with Flussonic Media Server using token authentication. The project aims to provide a high-performance, user-friendly video player capable of handling various IPTV stream formats and offering advanced features like channel caching, favorites management, and per-channel aspect ratio persistence.

## Recent Changes
- **October 07, 2025**: Fixed critical UI bugs:
  - Buffer value persistence: Changed from TextWatcher to onFocusChangeListener with onPause() backup to ensure settings save reliably
  - Channel number dialog: Added IME_ACTION_DONE handler so keyboard "Done" button properly switches channels
  - Debug log improvements: Wrapped TextView in 150dp ScrollView with auto-scroll to bottom, increased message limit to 50, and fixed visibility control to hide entire container when toggled off

## User Preferences
- I prefer simple language and clear explanations.
- I like an iterative development approach.
- Ask me before making any major architectural changes or decisions.
- Do not make changes to the folder `Z`.
- Do not make changes to the file `Y`.

## System Architecture

### UI/UX Decisions
- Phone-optimized touch interface with tap-to-toggle controls.
- Fullscreen playback with auto-hiding UI elements.
- Compact channel list design: number-first layout, 40dp logos.
- Redesigned control buttons with circular backgrounds and Material Design icons.
- All controls (playlist, navigation, aspect ratio, orientation, settings) integrated into a single, horizontally aligned row in the player controller.
- Channel list automatically closes when controls hide and requires explicit user action to reopen.
- Persistent display of "▶ Playing" indicator for the current channel.
- Updated RuTV logo for branding.

### Technical Implementations
- **Core Player**: Native Android application using Kotlin, Media3 (ExoPlayer), targeting Min SDK 24 (Android 7.0) and Target SDK 34 (Android 14).
- **Codec Support**: Utilizes `media3-ffmpeg-decoder` for comprehensive audio format support (e.g., MP2, ADTS, AAC). Hardware decoders handle all video formats.
- **Playlist Management**: Supports M3U/M3U8 playlists from local files or URLs. Playlists are stored permanently (local) or reloaded automatically (URL).
- **Channel Caching**: Implemented `ChannelStorage.kt` with JSON serialization and content-based hash detection to avoid redundant playlist parsing.
- **Favorites Functionality**: Allows users to mark and filter favorite channels, with persistence across app restarts.
- **Per-Channel Aspect Ratio Persistence**: Saves and restores aspect ratio preferences for individual channels.
- **Last Channel Resume**: Remembers and auto-plays the last watched channel on startup.
- **FFmpeg Toggle**: User-configurable option to enable/disable software decoding via FFmpeg. Uses EXTENSION_RENDERER_MODE_PREFER to prioritize FFmpeg audio renderer over hardware decoders when enabled, ensuring FFmpeg actually handles audio decoding.
- **Configurable Buffering**: Users can adjust buffer duration (5-60 seconds) for playback stability versus startup speed. Minimum 5s prevents rebuffering stutter. Focus-based validation allows free typing without keystroke interference. Player automatically restarts when buffer settings change.
- **Video Rotation**: Orientation button toggles between horizontal (0°) and vertical (270°) display, with scaling for full visibility in portrait.
- **Error Handling**: Robust error recovery, including automatic clearing of corrupted playlists and detailed logging.
- **HLS Optimization**: Specific HlsMediaSource.Factory with DefaultHlsExtractorFactory and aggressive TS flags for reliable MPEG audio detection in HLS streams.
- **Modern Architecture**: Leverages lifecycle-aware coroutines for async operations and proper resource cleanup.
- **Rebranding**: App name changed to "RuTV" with corresponding logo and APK filename updates.

### Feature Specifications
- M3U/M3U8 IPTV playlist loading (manual upload or URL).
- Support for all audio formats via FFmpeg (MP2, AAC, ADTS, etc.).
- Auto-advance to next video and repeat all mode.
- All subtitles disabled by default.
- On-screen debug log with decoder diagnostics (toggleable).
- Channel info display (channel number and name) that hides with controls.
- Playlist close button to dismiss the channel list without changing channels.
- Smart playlist reload based on content hash detection for URL playlists.
- **Channel number input**: Direct channel navigation via numeric keypad dialog with proper styling (white text, gray hint, dark background) and SOFT_INPUT_STATE_ALWAYS_VISIBLE for immediate keyboard display.
- **Yellow toggle indicator**: All toggle switches (FFmpeg, Debug Log) show gold color (#FFD700) when enabled for consistent RuTV branding.

## External Dependencies
- **AndroidX Media3**:
    - `androidx.media3:media3-exoplayer`
    - `androidx.media3:media3-ui`
    - `androidx.media3:media3-common`
    - `androidx.media3:media3-exoplayer-dash`
    - `androidx.media3:media3-exoplayer-hls`
- **FFmpeg Decoder**: `org.jellyfin.media3:media3-ffmpeg-decoder` (specifically for MP2/mp2a audio codec support).
- **Flussonic Media Server**: Integrated for IPTV streaming, utilizing its built-in token authentication.
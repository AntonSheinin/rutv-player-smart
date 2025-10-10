# Android IPTV Player

## Overview
The Android IPTV Player is a native Android application built with Media3 (ExoPlayer) for robust IPTV playlist playback. It offers extensive codec support via FFmpeg and is designed for seamless interaction with Flussonic Media Server using token authentication. The project aims to provide a high-performance, user-friendly video player capable of handling various IPTV stream formats and offering advanced features like channel caching, favorites management, and per-channel aspect ratio persistence.

## Recent Changes
- **October 10, 2025**: Complete EPG (Electronic Program Guide) integration:
  - **EPG Service**: Full service with health check endpoint, POST request for fetching program data, local JSON storage, and program lookup by channel tvg-id
  - **Settings UI**: Added EPG service URL configuration field in settings
  - **Data Models**: Created EpgProgram, EpgRequest, EpgResponse models with Gson serialization
  - **Channel parsing**: Updated M3U8Parser to extract `tvg-id` and `catchup-days` parameters from playlist
  - **Single/Double tap**: Channel list items now support single tap (show programs) and double tap (play channel) using GestureDetector
  - **Programs side panel**: New right-side panel showing EPG programs with date delimiters and current program indicator (green dot)
  - **Current program display**: Shows current program title below group/category in channel list items (gold text) and below channel name in player controls
  - **EPG layouts**: Created program item layout with time/title/description and date delimiter layout matching reference design
  - **Auto-fetch on startup**: App checks EPG service health and fetches program data automatically on launch with comprehensive logging
- **October 08, 2025**: NextLib FFmpeg integration for audio and video decoding:
  - **Replaced Jellyfin**: Migrated to NextLib (`io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0`) with proper package path (`io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory`)
  - **True independent control**: Overrides `buildAudioRenderers()` and `buildVideoRenderers()` to set EXTENSION_RENDERER_MODE_PREFER (FFmpeg) or EXTENSION_RENDERER_MODE_OFF (hardware) independently for audio and video. Each codec type can use different decoders simultaneously.
  - **Factory switching fix**: When both FFmpeg decoders are disabled, uses clean DefaultRenderersFactory (basic config with decoder fallback only) instead of NextRenderersFactory to prevent MediaCodec initialization errors (ERROR_CODE_DECODER_INIT_FAILED) when switching from FFmpeg to hardware decoders. Proper cleanup sequence: detach PlayerView, stop playback, release player, clear reference. When switching from FFmpeg video→hardware video specifically, forces activity restart to completely clear NextLib's system-level MediaCodec corruption that survives normal player cleanup.
  - **Decoder fallback**: Disabled to prevent cascade failures where FFmpeg fails then tries broken hardware decoders
  - **Kotlin 2.0.21**: Upgraded from 1.9.25 for NextLib compatibility
  - **Playlist indicators**: Added filename/URL display next to "Load Playlist" and "Load from URL" buttons
- **October 08, 2025**: Critical frame drop fix for stuttering channels with 24kHz AAC audio:
  - **HLS timestamp fix**: Simplified HLS extractor flags to FLAG_ALLOW_NON_IDR_KEYFRAMES only (removed FLAG_DETECT_ACCESS_UNITS and FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS) to fix known ExoPlayer bug with AAC timestamp discontinuities causing 50-frame drops every 2-3 seconds
  - **Timestamp adjuster**: Increased timeout to 30s (from 10s) to handle HLS segment-based timestamp discontinuities with non-standard 24kHz AAC audio
  - **Frame rate strategy**: Changed to VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF to disable display refresh rate changes that could trigger frame drops
  - **Audio sink**: Standard PCM mode (disabled float output and AudioTrack playback params) for clean timing
  - **Enhanced diagnostics**: Added dropped frame monitoring and frame processing offset tracking to identify decoder bottlenecks
  - **Rotation fix**: Switched from SurfaceView to TextureView rendering to enable actual video rotation (SurfaceView composites in separate layer that ignores transforms). Updated orientation toggle to properly find and transform TextureView in the content frame hierarchy with correct aspect-ratio scaling
- **October 07, 2025**: Major improvements and fixes:
  - **Video stuttering fix**: Increased buffer startup thresholds from 2.5s to 7.5s (bufferForPlaybackMs) and 10s (bufferForPlaybackAfterRebufferMs), with min buffer at 15s and max at 50s. Previous settings caused constant rebuffering cycles creating "shaking" effect on 1080p HLS streams.
  - **Channel smoothness improvements**: Implemented shared bandwidth meter with 2.8 Mbps initial estimate to maintain bandwidth learning across channel switches. Added setPrioritizeTimeOverSizeThresholds for time-based buffer optimization. Enabled asynchronous MediaCodec mode (dedicated thread with async queueing) to significantly improve rendering performance and eliminate stuttering on streams that play smoothly in other players. Fixed memory leak by using applicationContext.
  - **Comprehensive decoder stability fixes**:
    - **TextureView rendering**: Uses TextureView for video rendering to support rotation transforms (enables video rotation button functionality)
    - **PTS jitter tolerance**: Added 10s timestamp adjuster initialization timeout in HLS source to handle timestamp discontinuities gracefully
    - **Conservative ABR**: Disabled seamless video/audio adaptiveness and mixed MIME type switching to prevent stutter from bitrate changes
    - **Video joining time**: Increased to 10s (from 5s default) for seamless decoder initialization without frame drops
    - **Bitrate cap**: Set max video bitrate to 10 Mbps to prevent aggressive quality switching
  - **ExoPlayer enhancements**: Added DefaultTrackSelector for adaptive bitrate and enabled decoder fallback for improved reliability
  - **UI improvements**: Enlarged favorite star from 24sp to 40sp for better visibility, added rewind/forward buttons (10-second seeks) to player controls
  - **Settings enhancements**: Added "Current Playlist" display showing loaded playlist source (file/URL) with URL visibility. Reduced button sizes to 48dp height following Material Design best practices.
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
- **Codec Support**: Utilizes NextLib FFmpeg for both audio (Vorbis, Opus, FLAC, MP2, MP3, AAC, AC3, etc.) and video (H.264, HEVC, VP8, VP9) software decoding with separate toggles for each.
- **Playlist Management**: Supports M3U/M3U8 playlists from local files or URLs. Playlists are stored permanently (local) or reloaded automatically (URL).
- **Channel Caching**: Implemented `ChannelStorage.kt` with JSON serialization and content-based hash detection to avoid redundant playlist parsing.
- **Favorites Functionality**: Allows users to mark and filter favorite channels, with persistence across app restarts.
- **Per-Channel Aspect Ratio Persistence**: Saves and restores aspect ratio preferences for individual channels.
- **Last Channel Resume**: Remembers and auto-plays the last watched channel on startup.
- **FFmpeg Toggles**: Separate user-configurable options for audio and video FFmpeg decoding. When enabled, uses EXTENSION_RENDERER_MODE_PREFER to prioritize FFmpeg decoders over hardware.
- **Configurable Buffering**: Users can adjust buffer duration (5-60 seconds) for playback stability versus startup speed. Minimum 5s prevents rebuffering stutter. Focus-based validation allows free typing without keystroke interference. Player automatically restarts when buffer settings change.
- **Video Rotation**: Orientation button toggles between horizontal (0°) and vertical (270°) display, with scaling for full visibility in portrait.
- **Error Handling**: Robust error recovery, including automatic clearing of corrupted playlists and detailed logging.
- **HLS Optimization**: Specific HlsMediaSource.Factory with DefaultHlsExtractorFactory and aggressive TS flags for reliable MPEG audio detection in HLS streams.
- **Modern Architecture**: Leverages lifecycle-aware coroutines for async operations and proper resource cleanup.
- **Rebranding**: App name changed to "RuTV" with corresponding logo and APK filename updates.

### EPG (Electronic Program Guide) Features
- **EPG service integration**: Configurable EPG service URL with health check and forced update mode
- **Program data fetching**: POST request with channel tvg-id and catchup-days parameters, returns programs with start/stop times, titles, and descriptions
- **Local storage**: EPG data saved as JSON on device for offline access
- **Single/double tap navigation**: Single tap on channel name shows programs panel, double tap plays channel
- **Programs display**: Right-side panel with date-delimited program list, shows start times and current program indicator
- **Current program info**: Displayed in channel list (below group name) and player controls (below channel name) in gold text
- **Automatic updates**: EPG data fetched on app start after health check verification

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

### UI/UX Design
- **Programs panel**: Dark background (#0d0d0d) with date delimiters (#1a1a1a), matching reference screenshot design
- **Program indicators**: Green dot (12dp circle) for currently airing programs, time displayed in HH:mm format
- **Touch gestures**: GestureDetector integration for single tap (programs) vs double tap (play) differentiation
- **Auto-hide behavior**: Programs panel hidden when controls are hidden or when switching channels

## External Dependencies
- **AndroidX Media3**:
    - `androidx.media3:media3-exoplayer`
    - `androidx.media3:media3-ui`
    - `androidx.media3:media3-common`
    - `androidx.media3:media3-exoplayer-dash`
    - `androidx.media3:media3-exoplayer-hls`
- **NextLib FFmpeg**: `io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0` (provides audio and video FFmpeg decoding - H.264, HEVC, VP8, VP9, MP2, AAC, AC3, etc.).
- **Gson**: `com.google.code.gson:gson:2.10.1` (JSON serialization for EPG data storage and API communication).
- **Flussonic Media Server**: Integrated for IPTV streaming, utilizing its built-in token authentication.
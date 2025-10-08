# Android IPTV Player

## Overview
The Android IPTV Player is a native Android application built with Media3 (ExoPlayer) for robust IPTV playlist playback. It offers extensive codec support via FFmpeg and is designed for seamless interaction with Flussonic Media Server using token authentication. The project aims to provide a high-performance, user-friendly video player capable of handling various IPTV stream formats and offering advanced features like channel caching, favorites management, and per-channel aspect ratio persistence.

## Recent Changes
- **October 08, 2025**: Critical frame drop fix for stuttering channels with 24kHz AAC audio:
  - **HLS timestamp fix**: Simplified HLS extractor flags to FLAG_ALLOW_NON_IDR_KEYFRAMES only (removed FLAG_DETECT_ACCESS_UNITS and FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS) to fix known ExoPlayer bug with AAC timestamp discontinuities causing 50-frame drops every 2-3 seconds
  - **Timestamp adjuster**: Increased timeout to 30s (from 10s) to handle HLS segment-based timestamp discontinuities with non-standard 24kHz AAC audio
  - **Frame rate strategy**: Changed to VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF to disable display refresh rate changes that could trigger frame drops
  - **Audio sink**: Standard PCM mode (disabled float output and AudioTrack playback params) for clean timing
  - **Enhanced diagnostics**: Added dropped frame monitoring and frame processing offset tracking to identify decoder bottlenecks
  - **Rotation fix**: Switched from SurfaceView to TextureView rendering to enable actual video rotation (SurfaceView composites in separate layer that ignores transforms). Updated orientation toggle to properly find and transform TextureView in the content frame hierarchy with correct aspect-ratio scaling
  - **Vertical orientation UI redesign**: Complete layout reorganization for 270° vertical mode:
    - **Logo**: Top-left corner (16dp margins) - visible and properly positioned in portrait view
    - **Channel info**: Above video (70dp from top) - centered horizontally for clear channel identification
    - **Playback controls** (prev/rewind/play/forward/next): Centered on screen at normal size (no rotation)
    - **Navigation buttons** (playlist/favorites/channel number): Bottom-left corner (60dp bottom margin)
    - **Settings buttons** (aspect ratio/orientation/settings): Bottom-right corner (60dp bottom margin)
    - All dp values properly converted to pixels using displayMetrics.density for correct multi-density screen support
    - Opens channel list in vertical orientation automatically rotates back to horizontal first
  - **Playlist persistence**: Channel list and favorites list remain visible without auto-hiding. They only close when switching channels or pressing the close button
  - **Debug log optimization**: Constrained debug log window to 400dp max width to prevent excessive screen usage
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
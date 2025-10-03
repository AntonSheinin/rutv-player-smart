# Android IPTV Player

## Overview
Native Android video player application using Media3 (ExoPlayer) for IPTV playlist playback with advanced codec support.

## Project Structure

### Android App (`/app`)
- **Native Android application** with Media3/ExoPlayer
- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Key Features**:
  - M3U/M3U8 IPTV playlist loading (manual upload or URL)
  - ALL audio/video formats via FFmpeg (MP2, ADTS, AAC, H264, etc.)
  - Phone-optimized touch interface (single tap to play)
  - Fullscreen playback with auto-hiding UI
  - Auto-advance to next video
  - Repeat all mode
  - Vertical scrolling playlist with logos
  - All subtitles disabled
  - On-screen debug log with decoder diagnostics

## Dependencies

### Android (Media3)
- `androidx.media3:media3-exoplayer:1.8.0`
- `androidx.media3:media3-ui:1.8.0`
- `androidx.media3:media3-common:1.8.0`
- `androidx.media3:media3-exoplayer-dash:1.8.0`
- `androidx.media3:media3-exoplayer-hls:1.8.0`
- `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` (for MP2/mp2a audio codec support)

## Building the Android App

**GitHub Actions**: This project uses GitHub Actions for automated APK builds. Push to GitHub and download the APK from the Actions artifacts.

**Local Build** (requires Android SDK):
1. Open project in Android Studio
2. Sync Gradle files
3. Run on emulator or physical device

Alternatively, use command line with Android SDK:
```bash
./gradlew assembleDebug
```

## Playlist Support

The player supports M3U/M3U8 format playlists with:
- Channel names and metadata
- Channel logos
- Group/category organization
- HLS (HTTP Live Streaming) video sources
- IPTV playlists

### Loading Playlists
- App starts with empty playlist
- **Upload File**: Tap "Load M3U/M3U8" to select playlist from device storage
- **URL**: Tap "Load URL" to enter playlist URL
- Supports 300+ channel playlists with logos and categories

## Recent Changes

### October 3, 2025 (Latest - HLS-Specific Audio Fix)
- **CRITICAL FIX: HLS Media Source for MPEG Audio Detection**
  - Switched from DefaultMediaSourceFactory to HlsMediaSource.Factory for HLS-specific handling
  - Configured DefaultExtractorsFactory with aggressive TS (Transport Stream) flags
  - FLAG_DETECT_ACCESS_UNITS: Forces detection of all audio access units in TS
  - FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS: Enables detection of additional audio formats
  - TsExtractor.MODE_HLS: Optimized HLS transport stream parsing
  - Extended timestamp search to 1500 packets for better audio track detection
  - Disabled chunkless preparation to force full playlist parsing
  - Targets "NO AUDIO TRACKS IN STREAM" issue for MPEG audio layers 1/2 in HLS
- **ALL AUDIO & VIDEO VIA FFMPEG**: Software decoding for maximum compatibility
  - FfmpegAudioRenderer: ALL audio formats (MP2, ADTS, AAC, etc.)
  - FfmpegVideoRenderer: ALL video formats (H.264, H.265, VP8, VP9, MPEG-2, etc.) via reflection loading
  - Falls back to hardware decoders only if FFmpeg video renderer unavailable
  - Zero MediaCodec usage when FFmpeg available - pure software rendering
- **Modern Architecture**: 
  - Lifecycle-aware coroutines (lifecycleScope) for playlist loading
  - Proper cleanup in onStop() to prevent handler leaks
  - Dedicated FfmpegRenderersFactory class with EXTENSION_RENDERER_MODE_PREFER
  - Enhanced decoder diagnostics showing which renderer is actually used
- **FFmpeg Integration**: Runtime availability check with error logging if FFmpeg library not loaded
- **Phone-only UI**: Single tap to play, grey background for currently playing channel
- **Float audio output**: 32-bit float PCM for high-quality audio
- **Subtitles disabled**: Text renderer completely removed
- **Buffering timeout**: 30s detection with user notification
- **Debug diagnostics**: FFmpeg version, library status, and actual decoder names logged on-screen
- **Enhanced decoder logging**: Shows both audio and video decoder names, plus format details (resolution, framerate, sample rate, etc.)

### October 1, 2025
- Initial Android project with Media3/ExoPlayer integration
- M3U8 playlist parser for IPTV support
- Manual playlist loader (upload file or load from URL)
- Channel logos and categories support
- Enhanced error recovery and network handling

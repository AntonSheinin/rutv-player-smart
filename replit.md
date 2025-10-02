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
  - MP2/ADTS audio codec support via FFmpeg extension (all bitrates)
  - Fullscreen playback with auto-hiding UI
  - Android STB remote control support (D-pad navigation)
  - Previous/Next navigation
  - Auto-advance to next video
  - Repeat all mode
  - Vertical scrolling playlist with logos
  - Custom player controls
  - All subtitles disabled
  - On-screen debug log

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

### October 2, 2025 (Latest)
- **ALL FORMATS VIA FFMPEG**: Routed ALL audio and video through FFmpeg for maximum compatibility (VLC-like approach). No MediaCodec complexity - pure software decoding for all formats including MP2, ADTS, AAC, H264, etc.
- **Phone-only app**: Removed ALL STB D-pad navigation code, focus handling, and related UI complexity. App is now optimized for phone touch input only.
- **FFmpeg-only rendering**: Audio uses FfmpegAudioRenderer exclusively. Video uses FFmpeg when available. No MediaCodec filtering needed.
- **Extension mode**: EXTENSION_RENDERER_MODE_ON ensures FFmpeg handles everything
- **Simplified UI**: Single tap to play with grey background for currently playing channel. No STB focus states.
- **Float audio output**: Enabled 32-bit float PCM support for advanced audio codecs
- **Disabled all subtitles**: Text/subtitle renderer completely removed
- **Buffering timeout**: 30s timeout detection with user notification
- **Custom HTTP data source**: 15s connect/read timeouts, cross-protocol redirects
- **On-screen debug log**: Shows FFmpeg status and playback state
- **Fullscreen mode**: Auto-hiding UI during playback

### October 1, 2025
- Initial Android project with Media3/ExoPlayer integration
- M3U8 playlist parser for IPTV support
- Manual playlist loader (upload file or load from URL)
- Channel logos and categories support
- Enhanced error recovery and network handling

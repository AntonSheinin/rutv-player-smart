# Android IPTV Player + Flussonic Auth Backend

## Overview
Native Android video player application using Media3 (ExoPlayer) for IPTV playlist playback with advanced codec support, plus HTTP authorization backend for Flussonic Media Server.

## Project Structure

### Auth Backend Server (`/server`)
- **HTTP authorization backend** for Flussonic Media Server
- **Language**: Node.js (Express)
- **Port**: 3000
- **Key Features**:
  - Token validation for stream access
  - RESTful API for token management
  - Session duration and max concurrent sessions control
  - Per-stream access control
  - Request logging and monitoring

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

### Auth Backend (Node.js)
- `express` - Web server framework

### Android (Media3)
- `androidx.media3:media3-exoplayer:1.8.0`
- `androidx.media3:media3-ui:1.8.0`
- `androidx.media3:media3-common:1.8.0`
- `androidx.media3:media3-exoplayer-dash:1.8.0`
- `androidx.media3:media3-exoplayer-hls:1.8.0`
- `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` (for MP2/mp2a audio codec support)

## Running the Auth Backend

The auth backend runs automatically on port 3000. It provides HTTP authorization for Flussonic Media Server.

### API Endpoints

**Authorization Endpoint** (called by Flussonic):
```
GET/POST /auth?token=xxx&ip=xxx&name=stream_name
```

**Token Management**:
```bash
# List all tokens
GET /tokens

# Add new token
POST /tokens
{
  "token": "abc123",
  "userId": "user1",
  "maxSessions": 3,
  "duration": 3600,
  "allowedStreams": ["*"]
}

# Delete token
DELETE /tokens/{token}

# Health check
GET /health
```

### Flussonic Configuration

Add to your Flussonic config:
```conf
auth_backend myauth {
  backend http://YOUR_SERVER_IP:3000/auth;
}

stream example {
  input udp://239.255.0.1:1234;
  on_play auth://myauth;
}
```

### Pre-configured Tokens

- `wLaPEFi23KFwI0` - Default token for testing (user1, 3 sessions, 1 hour)

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

### October 3, 2025 (Latest - HLS Audio Detection + Codec Priority Fix)
- **CRITICAL FIX: HLS Media Source for MPEG Audio Detection** ✅ WORKING
  - Root cause: MPEG audio tracks not detected in HLS transport streams
  - Solution: HlsMediaSource.Factory with DefaultHlsExtractorFactory
  - Aggressive TS flags: FLAG_DETECT_ACCESS_UNITS, FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
  - Disabled chunkless preparation to force full playlist parsing
  - Fixed "NO AUDIO TRACKS IN STREAM" issue for MPEG audio layers 1/2
- **Codec Priority: Built-in First, FFmpeg Fallback**
  - Hardware/MediaCodec decoders used by default (optimal performance)
  - FFmpeg audio renderer available for unsupported formats (MP2, etc.)
  - EXTENSION_RENDERER_MODE_ON: Built-in preferred, FFmpeg as fallback
  - Battery-efficient hardware acceleration when available
- **Modern Architecture**: 
  - Lifecycle-aware coroutines (lifecycleScope) for playlist loading
  - Proper cleanup in onStop() to prevent handler leaks
  - Dedicated FfmpegRenderersFactory with smart codec selection
  - HLS-specific media source factory for IPTV streams
- **Phone-only UI**: Single tap to play, grey background for currently playing channel
- **Float audio output**: 32-bit float PCM for high-quality audio
- **Subtitles disabled**: Text renderer completely removed
- **Buffering timeout**: 30s detection with user notification
- **Enhanced diagnostics**: Track detection, codec names, format details (resolution, framerate, sample rate, bitrate)
- **Aspect ratio control**: On-screen button to cycle through display modes (FIT/FILL/ZOOM) for 4:3 and 16:9 content
- **HTTP headers**: User-Agent and proper headers (Accept, Accept-Encoding) for IPTV server compatibility
- **Enhanced error reporting**: Detailed error codes and causes logged for debugging

### October 1, 2025
- Initial Android project with Media3/ExoPlayer integration
- M3U8 playlist parser for IPTV support
- Manual playlist loader (upload file or load from URL)
- Channel logos and categories support
- Enhanced error recovery and network handling

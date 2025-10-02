# Video Player Project

## Overview
This project contains an Android video player application using Media3 (ExoPlayer) with playlist support, plus a web-based demo that demonstrates the same functionality.

## Project Structure

### Android App (`/app`)
- **Native Android application** with Media3/ExoPlayer
- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Key Features**:
  - M3U/M3U8 IPTV playlist loading (manual upload)
  - MP2/mp2a audio codec support via FFmpeg extension
  - Fullscreen playback with auto-hiding UI
  - Previous/Next navigation
  - Auto-advance to next video
  - Repeat all mode
  - Horizontal scrolling playlist UI
  - Custom player controls

### Web Demo (`/`)
- **HTML/CSS/JavaScript** video player with HLS.js
- **Purpose**: Demonstrates playlist functionality in browser
- **Running**: Python proxy server on port 5000
- **Key Features**:
  - M3U/M3U8 playlist support (IPTV compatible)
  - Upload local playlist files
  - Load playlists from URLs
  - HLS streaming with MPEG-TS support
  - HTTP to HTTPS proxy for mixed content
  - Enhanced error recovery and buffering

## Dependencies

### Android (Media3)
- `androidx.media3:media3-exoplayer:1.8.0`
- `androidx.media3:media3-ui:1.8.0`
- `androidx.media3:media3-common:1.8.0`
- `androidx.media3:media3-exoplayer-dash:1.8.0`
- `androidx.media3:media3-exoplayer-hls:1.8.0`
- `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` (for MP2/mp2a audio codec support)

## Building the Android App

**Note**: The Android app requires Android SDK and cannot be built/run directly in Replit. To build and run:

1. Download the project
2. Open in Android Studio
3. Sync Gradle files
4. Run on emulator or physical device

Alternatively, use command line with Android SDK:
```bash
./gradlew assembleDebug
```

## Web Demo

The web demo runs in Replit and demonstrates the same playlist functionality:
- Accessible at port 5000
- Uses sample videos from Google Cloud Storage
- Full playlist navigation and auto-advance

## Playlist Support

The player supports M3U/M3U8 format playlists with:
- Channel names and metadata
- Channel logos
- Group/category organization
- HLS (HTTP Live Streaming) video sources
- IPTV playlists

### Loading Playlists (Android App)
- App starts with empty playlist
- **Upload File**: Click "Load M3U/M3U8" to select playlist from device storage
- **URL**: Click "Load URL" to enter playlist URL

## Recent Changes

### October 2, 2025 (Latest)
- Removed auto-skip on playback errors - users manually select channels
- Added buffering timeout detection (30s) with user notification
- Configured DefaultLoadControl with finite buffer durations (3s min, 15s max)
- Added custom HTTP data source with 15s connect/read timeouts
- Enabled cross-protocol redirects for IPTV compatibility
- Added RTSP and SmoothStreaming protocol support
- Set FFmpeg extension mode to PREFER for better codec fallback
- Disabled subtitle/text renderer to prevent Teletext (telx) errors
- On-screen debug log shows FFmpeg status, playback state, and errors

### Earlier October 2, 2025
- Added FFmpeg extension for MP2/mp2a audio codec support (Jellyfin pre-built AAR)
- Implemented fullscreen mode with auto-hiding UI (buttons/playlist hide during playback)
- Fixed button visibility with elevation layering

### October 1, 2025
- Created Android project with Media3/ExoPlayer integration
- Implemented M3U8 playlist parser for IPTV support
- Added HLS.js with MPEG-TS support and enhanced buffering
- Created HTTP proxy server for mixed content handling
- Added manual playlist loader (upload file or load from URL)
- Implemented channel logos and categories
- Enhanced error recovery and network handling
- Created web-based demo for in-browser testing

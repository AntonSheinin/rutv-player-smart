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
  - Video playlist loading and playback
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

### Loading Playlists
- **Default**: Loads `playlist.m3u8` on startup
- **Upload File**: Click "Upload M3U/M3U8 File" to load local playlist
- **URL**: Enter a playlist URL and click "Load URL"

## Recent Changes (October 1, 2025)
- Created Android project with Media3/ExoPlayer integration
- Implemented M3U8 playlist parser for IPTV support
- Added HLS.js with MPEG-TS support and enhanced buffering
- Created HTTP proxy server for mixed content handling
- Added playlist loader (upload file or load from URL)
- Implemented channel logos and categories
- Enhanced error recovery and network handling
- Created web-based demo for in-browser testing

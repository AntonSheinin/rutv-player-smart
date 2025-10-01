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
- **HTML/CSS/JavaScript** video player
- **Purpose**: Demonstrates playlist functionality in browser
- **Running**: Python HTTP server on port 5000
- **Same feature set** as Android app

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

## Sample Playlist

Both versions use the same sample videos:
1. Big Buck Bunny
2. Elephant Dream
3. For Bigger Blazes
4. For Bigger Escape
5. For Bigger Fun
6. Sintel

All videos are publicly available from Google's sample video repository.

## Recent Changes (October 1, 2025)
- Created Android project with Media3/ExoPlayer integration
- Implemented playlist functionality with RecyclerView
- Added custom player controls
- Created web-based demo for in-browser testing
- Updated Kotlin plugin to modern ID
- Removed cleartext traffic permission (all videos use HTTPS)

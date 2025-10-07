# Android IPTV Player

## Overview
Native Android video player application using Media3 (ExoPlayer) for IPTV playlist playback with advanced codec support. Designed to work with Flussonic Media Server using built-in token authentication.

## Project Structure

### Android App (`/app`)
- **Native Android application** with Media3/ExoPlayer
- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Key Features**:
  - M3U/M3U8 IPTV playlist loading (manual upload or URL)
  - ALL audio/video formats via FFmpeg (MP2, ADTS, AAC, H264, etc.)
  - Phone-optimized touch interface (tap screen to show/hide controls)
  - Fullscreen playback with auto-hiding UI
  - Independent playlist toggle button (bottom-left, 80dp from bottom)
  - Channel info display (hides with controls)
  - Auto-advance to next video
  - Repeat all mode
  - Compact channel list (number-first layout, 40dp logos)
  - All subtitles disabled
  - On-screen debug log with decoder diagnostics (toggle in Settings)
  - Video rotation toggle (Horizontal ↔ Vertical)
  - **Channel caching** - avoid re-parsing playlists on startup (content-based change detection)
  - **Favorites** - mark channels with ★ and filter to show only favorites
  - **Per-channel aspect ratio** - app remembers your choice for each channel

## Dependencies

### Android (Media3)
- `androidx.media3:media3-exoplayer:1.8.0`
- `androidx.media3:media3-ui:1.8.0`
- `androidx.media3:media3-common:1.8.0`
- `androidx.media3:media3-exoplayer-dash:1.8.0`
- `androidx.media3:media3-exoplayer-hls:1.8.0`
- `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` (for MP2/mp2a audio codec support)

## Building the Android App

**GitHub Actions**: This project uses GitHub Actions for automated APK builds. 
- Push to GitHub and the workflow will automatically build `rutv-debug-1.4.apk`
- Download the APK from GitHub Actions artifacts under "rutv-debug-apk"
- **Updatable APK**: Uses consistent debug.keystore for all builds - update without uninstalling!

**Local Build** (requires Android SDK):
1. Open project in Android Studio
2. Sync Gradle files
3. Build APK: `./gradlew assembleDebug`
4. Output: `app/build/outputs/apk/debug/rutv-debug-1.4.apk`

Or run directly on emulator/physical device from Android Studio.

**For Future Updates**: 
- Increment `versionCode` in app/build.gradle (currently: 5)
- Update `versionName` (currently: "1.4")
- Update APK filename in GitHub Actions workflow
- Keep `debug.keystore` file for consistent signing

## Playlist Support

The player supports M3U/M3U8 format playlists with:
- Channel names and metadata
- Channel logos
- Group/category organization
- HLS (HTTP Live Streaming) video sources
- IPTV playlists

### Loading Playlists
- **Settings Button**: Tap the ⚙ icon (bottom right) to access settings
- **Upload File**: Select M3U/M3U8 file from device - stored permanently on device
- **Playlist URL**: Enter M3U8 URL - reloads automatically on app start
- Auto-loads last configured playlist on startup
- Supports 300+ channel playlists with logos and categories

## Recent Changes

### October 7, 2025 (Latest - Version 1.4 - Channel Caching, Favorites & Aspect Ratio Persistence)
- **🚀 Channel Caching System**: Intelligent caching to avoid re-parsing playlists on every app start
  - Created ChannelStorage.kt persistence layer with JSON serialization
  - Channels cached with content-based hash detection (not URL-based)
  - Auto-reloads from cache when playlist content unchanged
  - Only re-parses when playlist content actually changes
  - Shows "✓ Loading X channels from cache" or "Parsing playlist (content changed)" messages
  - Works for both file uploads and URL playlists
- **⭐ Favorites Functionality**: Mark and filter favorite channels
  - Yellow asterisk button (☆/★) for each channel in playlist
  - Tap to toggle favorite status (empty star = not favorite, solid star = favorite)
  - Favorites persist across app restarts via ChannelStorage
  - Filter button (⭐) next to playlist button shows only favorited channels
  - Channel numbers always show original position (not filtered position)
  - Playing indicator works correctly in both full and filtered views
- **🎬 Per-Channel Aspect Ratio Persistence**: App remembers aspect ratio choice for each channel
  - Aspect ratio (FIT/FILL/ZOOM) saved per channel URL
  - Automatically restored when returning to a channel
  - Resets when new playlist loaded (all aspect ratios cleared)
  - Seamless experience when browsing channels
- **🔧 Smart Diff for URL Playlists**: Content-based change detection
  - CRITICAL FIX: Changed from URL hash to content hash
  - Fetches playlist content and compares actual content (not URL string)
  - Only updates when remote playlist file changes
  - Avoids unnecessary re-parsing when content unchanged
- **Version**: versionCode 5, versionName "1.4"

### October 6, 2025 (Version 1.3 - UI/UX Polish & Bug Fixes)
- **Control Buttons in One Line**: All control buttons now integrated into player controller in one horizontal row:
  - Left: Playlist button (48dp)
  - Center: Previous, Play/Pause, Next buttons (truly centered using FrameLayout)
  - Right: Aspect Ratio, Orientation, Settings buttons (all 48dp - uniform sizing)
  - Auto-show/hide with controller
- **Channel List Smart Behavior**: Channel list now properly closes when controls hide (tap or timeout) and does NOT automatically reopen when controls show - user must explicitly click playlist button to reopen
- **Channel List Z-Order**: Channel list now covers RuTV logo when opened (logo elevation reduced to 5dp, appears behind playlist)
- **Channel Info Auto-Hide**: Now disappears with controls (tap screen to toggle visibility)
- **Smart Playlist Reload**: Only reloads when playlist changes in settings (hash-based detection)
- **Simplified Video Rotation**: Orientation button now toggles between only 2 positions:
  - Horizontal (0°) ↔ Vertical (270°)
  - Shows clear "Horizontal" / "Vertical" toast messages
  - No longer cycles through 4 degrees (0°/90°/180°/270°)
- **Fixed Vertical Rotation Display**: Vertical rotation now shows full video without cutting edges:
  - Applies scale transformation (scaleFactor = height/width) when rotated 90° or 270°
  - Full video visible in portrait orientation
  - Resets to 1x scale when horizontal
- **Refactored Channel List**:
  - Channel number on left (20sp, bold, 40dp min width)
  - Smaller logo (40dp instead of 56dp)
  - Narrower items (2dp margin, 8dp padding)
  - Group/category text shown below title (11sp, gray)
- **Settings Scroll Fix**: Settings page now uses ScrollView - debug log toggle accessible on all screen sizes (was previously hidden below fold on small screens)

### October 5, 2025 (Version 1.3 - CRASH FIX)
- **🔥 CRITICAL FIX: Theme Crash Resolved** - Fixed MaterialCardView crash by changing app theme from `Theme.AppCompat` to `Theme.MaterialComponents` (root cause: MaterialCardView requires MaterialComponents theme)
- **Updated RuTV Logo**: New square RuTV logo with yellow/white/black design implemented as launcher icon and in-app UI (48x48dp)
- **Logo Files Updated**: All mipmap densities and drawable updated with new logo (18KB PNG files)
- **Version Incremented**: versionCode 4, versionName "1.3", APK output: rutv-debug-1.3.apk

### October 5, 2025 (Version 1.2 - Bug Fixes & Logo Update)
- **CRITICAL FIX: Playlist loading crash** - Fixed RecyclerView update timing to prevent crashes with all playlist sizes
- **Error Recovery**: App now clears corrupted playlists automatically on startup with proper try-catch handling
- **Logo Display**: Logo only appears on app start screen - automatically hides during channel playback
- **Logo Sizing**: Reduced in-app logo to 48dp for better screen proportion
- **Version Incremented**: versionCode 3, versionName "1.2", APK output: rutv-debug-1.2.apk
- **Launcher Icon Fix**: Removed adaptive icon to show full logo without cropping
- **Video Rotation Only**: Orientation toggle now rotates only video playback (0°/90°/180°/270°), UI stays landscape
- **Auto-play Channels**: Selecting a channel from list automatically starts playback (no play button needed)
- **Instant Playlist Reload**: Adding playlist in settings auto-reloads it immediately (no app restart required)
- **Debug Log Toggle**: New setting to show/hide on-screen debug logs
- **App Rebranding**: Changed app name from "Video Player" to "RuTV" with updated APK output filename (rutv-*.apk)
- **RuTV Logo**: Added RuTV logo in upper left corner (120x60dp) and as launcher icon
- **Channel Info Display**: Shows "#[number] • [channel name]" at top center when playlist is open
- **Material Design Icons**: Redesigned all control buttons (aspect ratio, orientation, settings) with circular backgrounds and white borders
- **Improved Channel List**: 
  - MaterialCardView with rounded corners (16dp) and elevation
  - Channel numbers displayed (#1, #2, etc.)
  - Better spacing and padding
  - "▶ Playing" indicator for current channel
- **Settings Page**: Dedicated settings screen accessible via ⚙ button (bottom right)
- **Persistent Storage**: 
  - File upload mode: Playlist content stored locally in SharedPreferences
  - URL mode: Playlist URL stored and reloaded automatically on app start
- **Auto-load**: App automatically loads saved playlist on startup

### October 4, 2025 (Project Cleanup)
- Removed Node.js auth backend server (using Flussonic built-in authentication instead)
- Cleaned up Node.js dependencies and server files
- Project now consists of Android app only
- Fixed GitHub Actions workflow to use correct APK filename (rutv-debug-1.0.apk)

### October 3, 2025 (HLS Audio Detection + Codec Priority Fix)
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

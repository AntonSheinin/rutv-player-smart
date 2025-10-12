# Build Configuration Fixes

## Issues Identified

### 1. Kotlin Version Compatibility
**Problem:** Kapt doesn't fully support Kotlin 2.0+
**Solution:** Downgraded Kotlin from 2.0.21 to 1.9.24

### 2. Native Library Packaging
**Problem:** Unable to strip FFmpeg native libraries
**Solution:** Added `packagingOptions` with `useLegacyPackaging = true`

---

## Changes Made

### File: `build.gradle` (root)
```gradle
// Changed from:
id 'org.jetbrains.kotlin.android' version '2.0.21' apply false

// Changed to:
id 'org.jetbrains.kotlin.android' version '1.9.24' apply false
```

### File: `app/build.gradle`
```gradle
// Added packaging options:
packagingOptions {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

---

## Why These Changes

### Kotlin 1.9.24
- Full Kapt support
- Stable and tested with Hilt
- Works perfectly with Room compiler
- Compatible with all our dependencies

### Legacy Packaging for Native Libs
- Required for FFmpeg libraries (libavcodec.so, libavutil.so, etc.)
- Prevents stripping issues during build
- Ensures libraries are packaged correctly

---

## Build Command

```bash
# Clean and rebuild
gradlew clean assembleDebug

# Or just build
gradlew assembleDebug
```

---

## Expected Result

Build should now complete successfully with:
- ✅ Kapt processing without warnings
- ✅ Native libraries packaged correctly
- ✅ APK generated successfully

---

**Status:** Fixes applied, ready to build

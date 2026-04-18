# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android TV / mobile IPTV player. **Kotlin + Jetpack Compose**, **Media3/ExoPlayer** playback, **Hilt** DI, **Room** + **DataStore** persistence. Single-module Gradle project (`:app`), applicationId `com.rutv` (debug variant suffixes `.dev`). `minSdk 24`, `compileSdk`/`targetSdk 35`, JVM target 17.

Dual-target: declared `android.software.leanback` (non-required) plus `android.hardware.touchscreen` (non-required) — same code path serves TV remotes and phones. Both `MainActivity` intent filter categories `LAUNCHER` and `LEANBACK_LAUNCHER` are registered.

## Build & deploy

This repo has `gradlew` but **no `gradlew.bat`**. From PowerShell, invoke the Gradle Wrapper main class via Java directly rather than calling `./gradlew`:

```powershell
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' `
  -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain `
  :app:compileDebugKotlin :app:assembleDebug --console=plain
```

Debug APKs land in `app/build/outputs/apk/debug/` named `rutv-debug-<versionName>.apk`. Debug signing uses the checked-in `debug.keystore` at repo root.

Remote install to STB over ADB-TCP — use the explicit adb path since `adb` is usually not in PATH:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect 10.100.102.10:5555
& $adb -s 10.100.102.10:5555 install -r app/build/outputs/apk/debug/rutv-debug-1.4.apk
& $adb -s 10.100.102.10:5555 shell am start -n com.rutv/.presentation.MainActivity
```

Cold-start perf measurement and logcat crash scraping are in [AGENTS.md](AGENTS.md). Full profiling: `scripts/profile-adb.ps1` (writes reports to `build/perf/`).

No tests, no lint task wired — there is no `test` target to run.

## Architecture

Layered `data → domain → presentation → ui`. Dependency edges strictly inward: `presentation/ui` → `domain` → `data`. Hilt wires everything from [`di/AppModule.kt`](app/src/main/java/com/rutv/di/AppModule.kt) (Room, Gson, ExoPlayer HTTP stack) and [`di/RepositoryModule.kt`](app/src/main/java/com/rutv/di/RepositoryModule.kt) (interface→impl bindings).

**[`MainViewModel`](app/src/main/java/com/rutv/presentation/main/MainViewModel.kt) is the central orchestrator** — playlist load, player lifecycle, EPG windowing/paging, UI state, and reactions to day/timezone changes all flow through it. UI state is a single `MutableStateFlow<MainViewState>` updated atomically via `.update {}`. Network/IO on `Dispatchers.IO`; expensive filtering on `Dispatchers.Default`.

**Playback** ([`presentation/player/PlayerManager.kt`](app/src/main/java/com/rutv/presentation/player/PlayerManager.kt)) wraps Media3 `ExoPlayer` and handles both live playlist playback and archive (catch-up) playback, with buffering timeouts and debug telemetry surfaced via `PlayerState`/`DebugMessage`.

**Playlist pipeline**: [`LoadPlaylistUseCase`](app/src/main/java/com/rutv/domain/usecase/LoadPlaylistUseCase.kt) picks `PlaylistSource.File` vs `Url`, hashes content to detect change, and persists `Channel` rows via Room. Cold-start fast path: when a URL source has a stored hash + non-empty DB, `skipNetworkIfCacheAvailable=true` returns cached channels immediately and a background refresh updates later.

**EPG** uses a windowed model — programs parsed to epoch millis up front (`EpgProgram`), fetched via [`FetchEpgProgramsUseCase`](app/src/main/java/com/rutv/domain/usecase/FetchEpgProgramsUseCase.kt) using the window from [`ComputeEpgWindowUseCase`](app/src/main/java/com/rutv/domain/usecase/ComputeEpgWindowUseCase.kt), cached in [`EpgRepositoryImpl`](app/src/main/java/com/rutv/data/repository/EpgRepositoryImpl.kt) with a streaming JSON parser. **Cache invalidation reacts to both day change and timezone/clock changes** — `MainActivity` observes system time broadcasts and the repo drops stale windows accordingly. Don't bypass this by adding naive time math in UI.

**Persistence split**: channels → Room (`AppDatabase`, schemas exported to `app/schemas/` via KSP). User prefs (favorites, playlist source, language, aspect ratio) → DataStore via [`PreferencesRepository`](app/src/main/java/com/rutv/data/repository/PreferencesRepository.kt). Room uses `fallbackToDestructiveMigration()` as a safety net — favorites live in DataStore so this won't wipe user data, but prefer writing real migrations for non-trivial schema changes.

**UI layering**: `ui/shared/` holds remote-friendly primitives (focus helpers, remote dialogs, EPG toast, custom control buttons) consumed by both `ui/mobile/screens/` (PlayerScreen, SettingsScreen) and the dialog layer in `presentation/MainDialogs.kt`. Focus management for D-pad navigation is non-trivial — see `PlayerFocusManager`, `CustomControlFocusCoordinator`, and `DialogFocusPolicy` before touching focus behavior. Compose stability is helped by `kotlinx.collections.immutable` — use `persistentListOf` / `toImmutableList` for state lists rather than `List<T>`.

**Logging**: Timber is initialized in `RuTvApplication`; helpers in [`util/LogExt.kt`](app/src/main/java/com/rutv/util/LogExt.kt). Feature constants split across [`EpgConstants`](app/src/main/java/com/rutv/util/EpgConstants.kt), [`PlayerConstants`](app/src/main/java/com/rutv/util/PlayerConstants.kt), and the general [`Constants`](app/src/main/java/com/rutv/util/Constants.kt).

## Coding rules

- **No tests, no backward-compat shims** unless explicitly requested.
- **Modern idioms** — follow current Android/Kotlin best practices
  (Coroutines/Flow over callbacks, Compose state over LiveData, etc.).
- **Keep it simple** — SOLID, YAGNI, KISS. No speculative abstractions,
  no wrapper classes that only delegate, no interfaces with a single implementation
  unless Hilt demands it.

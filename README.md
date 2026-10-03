# RuTV Player (Smart)

This repository contains an Android TV / mobile IPTV player implemented in **Kotlin + Jetpack Compose**, using **Media3/ExoPlayer** for playback and **Hilt** for dependency injection.

Current release: **1.3.0**.

### Audio tracks

- The custom player controls show the current stream-provided audio track label.
- The audio chooser is available for both live and archive playback, including streams with a single labeled track.
- The selected track is remembered independently for each channel and restored after channel changes or app restarts.

### Archive completion

The player can automatically continue with the next available archive program, return directly to live TV, or show the existing confirmation dialog when an archive program ends. Choose the behavior under **Settings > Player Configuration > When an archive program ends**; **Ask every time** remains the default.

### EPG description language

Choose Russian, English, or Hebrew under **Settings > EPG Configuration > Preferred EPG description language**. Russian is the default. When the requested language is unavailable or empty, the EPG service supplies another available description.

## Code tour (where to start)

- **App entrypoints**
  - `app/src/main/AndroidManifest.xml`: declares `RuTvApplication`, `MainActivity`, `SettingsActivity`.
  - `app/src/main/java/com/rutv/RuTvApplication.kt`: app-level setup (locale + logging) and Coil `ImageLoader`.
  - `app/src/main/java/com/rutv/presentation/MainActivity.kt`: Compose host + key/remote handling + dialogs.

- **Presentation (state + orchestration)**
  - `app/src/main/java/com/rutv/presentation/main/MainViewModel.kt`: the “traffic controller”.
    - Loads playlist, starts player, manages UI state, caches EPG, and reacts to time/timezone changes.

- **Playback**
  - `app/src/main/java/com/rutv/presentation/player/PlayerManager.kt`: wraps Media3 `ExoPlayer`.
    - Handles live playlist playback, archive (catch-up) playback, labeled audio-track selection, buffering timeouts, and debug telemetry.

- **Playlist**
  - `app/src/main/java/com/rutv/domain/usecase/LoadPlaylistUseCase.kt`: source selection + caching policy.
  - `app/src/main/java/com/rutv/data/remote/PlaylistLoader.kt`: downloads URL playlists with size limits.
  - `app/src/main/java/com/rutv/data/remote/PlaylistParser.kt`: parses M3U/M3U8 into `Channel` models.

- **EPG**
  - `app/src/main/java/com/rutv/domain/usecase/ComputeEpgWindowUseCase.kt`: computes the time window to request.
  - `app/src/main/java/com/rutv/domain/usecase/FetchEpgProgramsUseCase.kt`: loads windowed programs.
  - `app/src/main/java/com/rutv/data/repository/EpgRepositoryImpl.kt`: caching + streaming JSON parser.

- **Persistence**
  - `app/src/main/java/com/rutv/data/local/AppDatabase.kt` + `ChannelDao.kt`: Room database for channels.
  - `app/src/main/java/com/rutv/data/repository/PreferencesRepository.kt`: DataStore preferences (+ sync language).

## Architectural notes

- The project mostly follows a simple layered approach:
  - **UI (Compose)** renders state and sends user intents
  - **ViewModel** orchestrates state + long-running work
  - **Use cases** encapsulate reusable domain logic/policies
  - **Repositories** talk to network/db and provide caching

- **Time handling**
  - EPG programs are parsed into epoch millis early (`EpgProgram`) so downstream code can compare times cheaply.
  - Cache invalidation reacts to both **day change** and **timezone/clock changes** (`EpgRepository` + `MainActivity`).



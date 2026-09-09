# Refactor implementation and validation

Branch: `refactor/robustness-and-performance`. Baseline: `4f0b011`. Updated: 2026-09-07.

The correctness and cleanup changes below are implemented. Automated verification is recorded below; device acceptance belongs to the user. The original [plan](REFACTOR_PLAN.md) is not fully closed: peak EPG input limits, device/performance evidence, and the remaining static-analysis/toolchain work are explicitly outstanding.

## Implemented changes

| Area | Result | Regression evidence |
| --- | --- | --- |
| Playlist persistence | Download and validate before replacement. Channels and source/hash metadata commit in one Room transaction. Shared request revisions prevent obsolete loads/source changes from committing. | File reload failure preserves the snapshot; source switch cannot reuse unrelated cached rows; obsolete revisions are rejected. |
| Favorites/aspect ratio | Replacement preserves committed Room edits by URL, then nonblank TVG ID. Backup is recovery input only for an empty database; serialized writes update it from committed rows. | Concurrent favorite toggles converge; stale backup cannot resurrect favorites; aspect ratio survives replacement. |
| Upgrade | Room 3-to-4 migration creates snapshot metadata without deleting channels. | Real SQLite version-3 fixture opens through Room version 4 and retains channel state. |
| EPG ownership | One mutex owns generation, cache publication, and shared request bookkeeping. Network work runs outside it. Last-waiter cancellation stops work; invalidated or older responses cannot publish. | Shared waiters, individual/last cancellation, noncooperative stale response, and backend-version tests. |
| EPG semantics | Explicit empty results replace overlapping data; missing/malformed responses fail. Current program derives from retained channel data and expires at the exclusive stop boundary. Archive pages preserve other intervals. Partial cache hits fetch a complete batch to avoid mixing backend versions. | Empty-versus-error, archive/current, cached batch reuse, mixed-version batch, and atomic page-merge tests. |
| Time | ISO/XMLTV parsing uses `java.time`; local fallback uses the current timezone. Requests use half-open windows ending at next local midnight. | Parsing, timezone-change, and program-boundary tests. |
| Transport | Streaming parser extracted behind a test seam; EPG HTTP calls use cancellable OkHttp exchanges; gzip/deflate decoding preserved. | Loopback HTTP gzip and stalled-body cancellation tests (five attempts per variant), plus parser fixtures. |
| Playback | Release closes command admission, invalidates old submissions, cancels child work, and settles pending DVR requests. Delayed seek checks the same player/channel/program. | Release/reprepare and queued/rejected DVR completion tests. Real decoder behavior remains a device check. |
| UI publication | Latest playlist/EPG panel generations guard updates; page merges use current immutable state; cancellation propagates and loading state settles. | Page merge/closed-panel tests; full compilation. Rapid screen/device interactions remain live checks. |
| Preferences | File replacement uses AtomicFile; file operations share a lock. Playlist source reads run on IO and legacy flow reads have no write side effect. | Playlist persistence tests exercise file source storage/loading. |
| Kotlin/build | Typed compiler options, correct Media3 opt-ins, removal of deprecated global BuildConfig flag. CI runs both unit-test/lint variants and release assembly. | Gradle compilation, tests, lint, and minified release build. |

The implementation retains the existing app module, Hilt, Room, DataStore, Media3, and playback command processor. No runtime dependency version upgrades, release identity changes, or new general coordination framework were introduced. OkHttp 4.12.0, already present through Coil, is now explicitly declared for EPG transport. The stalled-body test exposed intermittent HttpURLConnection cancellation failure, justifying this scoped transport change.

## Cleanup and retained candidates

- Removed unreferenced `TimeFormatter.formatDate`/`formatDuration`, obsolete playlist hash/cache APIs, duplicate favorite backup writes, and the separate EPG current-program cache.
- Removed five unused string keys in both languages: `favorite_filled`, `favorite_empty`, `lock_indicator`, `cd_locked_channel`, `cd_open_epg`. Existing UI accessibility labels remain in place.
- Playlist parsing uses a line sequence and reused regexes; SHA-256 replaces collision-prone `String.hashCode` for content identity. Tests include CRLF/group metadata and a known hash collision.
- Corrected README's EPG repository path. Generated `.kotlin/` output is ignored.
- Retained Hilt providers/bindings and framework entry points despite declaration-only text references. Retained `coil-gif`: application code uses its decoder.
- Retained five identical 300x300 launcher PNGs (17,821 bytes each). They occupy different density configurations; consolidation requires visual checks. No unused-resource lint findings remain in the checked variants.
- Player/preview construction remains separate because ownership and behavior differ. No speculative request debounce, interval index, Compose stability annotations, or module split was added.

Reference searches, compiler/lint checks, and release shrinking improve confidence; they do not prove absence of all dead or duplicate code. A dedicated Kotlin-aware clone/IDE inspection pass remains unperformed.

## Evidence and compatibility boundaries

The adjacent `../epg_service` implementation establishes the controlled backend contract: requested channels are included even when their list is empty; query predicates select overlapping intervals (`start < to`, `stop > from`); `last_epg_update_at` represents import completion and may be null. Other providers that omit requested channels fail without advancing coverage. Parseable update timestamps are ordered; absent timestamps do not invent a backend version.

Existing version-3 rows have no verifiable source identity. The migration preserves them, but automatic URL-cache reuse requires one successful revalidation. Consequently, the first offline start after this upgrade may not display the preserved playlist until connectivity returns. Historical pre-v3 fallback is unchanged because shipped schema evidence was unavailable. Downgrading an already migrated installation is not a supported rollback: preserve app data and fix forward, or explicitly export settings before reinstalling.

Retained EPG caches are bounded to 128 channels and 32 channel/window entries, each retained list at most 512 programs. Oversized successful windows are returned without retaining the full window. These are retention limits, **not a peak parser/response memory guarantee**. Representative largest production payloads were unavailable; byte/program rejection thresholds and boundary tests remain pending that evidence. Existing playlist download limits remain unchanged.

Production toolchain/dependency versions were preserved as a working compatibility set. This is not certification against the latest available versions. The typed compiler DSL follows [Kotlin compiler guidance](https://kotlinlang.org/docs/gradle-compiler-options.html); cancellation/dispatcher ownership follows [Android coroutine guidance](https://developer.android.com/kotlin/coroutines/coroutines-best-practices); Media3 opt-ins follow [official troubleshooting guidance](https://developer.android.com/media/media3/exoplayer/troubleshooting).

| Kotlin practice | Assessment |
| --- | --- |
| Typed compiler DSL and Media3 opt-ins | Addressed; enforced by compilation/lint. |
| Cancellation and shared mutable ownership | Addressed in the changed playlist, EPG, and player paths; focused tests cover the documented races. This does not certify every coroutine in the app. |
| Immutable UI publication | Preserved; EPG page publication now merges atomically. |
| Main safety | Playlist IO/source reads and EPG network/parser work run off main. Remaining synchronous locale preference access is a separate startup concern. |
| Compose API conventions/deprecations | Remaining gaps appear in lint/compiler warnings; no broad signature/formatting rewrite performed. |
| Latest toolchain compatibility | Deferred as a separate upgrade series; current versions pass the selected checks. |
| Whole-project clone/IDE analysis | Not assessed by a dedicated tool; semantic/reference review completed only for the documented candidates. |

## Automated verification

The final debug and release suites each pass **27 tests**, with zero failures/errors/skips. This includes five stalled-response cancellation attempts in each variant. Both lint variants pass with **zero errors**, retaining 67 debug and 50 release warnings. The full command completed successfully in 4m 11s; release minification/resource shrinking and APK assembly passed. `git diff --check` passed. The command is:

```text
GradleWrapperMain :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --max-workers=2 --console=plain
```

Candidate: [rutv-release-1.1.0.apk](../app/build/outputs/apk/release/rutv-release-1.1.0.apk), **13,906,687 bytes**. `apksigner verify --verbose` passed (v2 signature, one signer). Existing repository signing configuration and version are unchanged. Build log: `%TEMP%/rutv-refactor-final.log`; test and lint reports: `app/build/reports/`. This local candidate was not uploaded, installed, committed, or pushed.

The baseline had four passing tests and a successful release build, but lint failed with 73 errors and 72 warnings. No blanket lint baseline was added. Remaining warning categories include dependency age, existing Compose API conventions/deprecations, locale packaging, inflation without a parent, typography/plural suggestions, HTTP compatibility configuration, and launcher density duplicates. These remain review items; lint success must not be interpreted as zero warnings or complete Kotlin compliance.

## User live acceptance checklist

Use the candidate minified release on the target STB; also check a supported mobile device where available. Keep provider, device, and settings fixed when comparing with the baseline.

1. Upgrade an existing version-3 installation online. Verify playlist, favorites, aspect ratios, parental locks, settings, and last-channel behavior. Restart offline after successful revalidation.
2. Reload successfully, then with an unreachable/invalid source. Verify failure preserves saved channels. Change URL/file sources and reload rapidly; verify the final source wins and loading indicators settle.
3. Switch live channels rapidly; toggle preview; enter/leave settings; background/resume and recreate the activity. Verify no old playback/seek/retry takes over and no audio continues after exit.
4. Open/close EPG rapidly and page both directions. Check empty days, offline retry, current-program rollover, midnight/timezone changes, and source changes. Verify no missing boundary program, lost page, or stuck spinner.
5. Play archive, seek, enter/exit timeshift, and return to live. Check decoder options, remote focus/back actions, English/Russian labels, launcher appearance, and parental access prompts.
6. Run the planned two-hour playback/preview/switch soak. Record crashes, memory trend, active requests/decoders, startup/switch timings, and frame behavior. Compare against the baseline before making performance claims.

No device installation, live playback validation, performance measurement, or soak was performed by the agent. The user elected to perform live checks. No numerical speedup or memory reduction is claimed; the baseline APK size was not captured, so an APK-size delta cannot be established.

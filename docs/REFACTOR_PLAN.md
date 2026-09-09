RuTV refactor plan — robustness, concurrency, performance, and maintainability

Prepared 2026-09-06 against commit `4f0b011`. Status: implementation in progress on `refactor/robustness-and-performance`; see [implementation results and remaining gates](REFACTOR_IMPLEMENTATION.md).

Reviewed for implementation readiness on 2026-09-06. Required fixes, conditional improvements, defaults, and evidence gates are distinguished below. This is ready to start with baseline verification; runtime and backend checks remain explicit implementation tasks, not assumed facts.

**Scope and evidence**

This plan is based on repository inspection and current official Kotlin/Android documentation. The source inventory contains 78 Kotlin files and 19,785 lines, including one test file with four external-config parser tests. The initial working tree was clean. These inventory and baseline statements describe the planning snapshot, before implementation.

The review inspected build/CI configuration, core playlist/EPG persistence and networking, playback ownership, ViewModel orchestration, UI lifecycle collection, and resource/symbol references. No Gradle build, lint, device profiling, or runtime race reproduction was performed. Findings below distinguish source-confirmed behavior from risks that require tests. A text-reference scan cannot certify the absence of dead code or unused resources.

Preserve existing strengths: `collectAsStateWithLifecycle` at both Activity entry points; read-only state exposure and immutable collections; playlist request IDs; a serialized playback command processor; EPG sharing for identical request keys; transactional channel replacement; streaming EPG parsing; playlist download limits; preview lifecycle handling; and release minification/resource shrinking.

**Execution order**

| Stage | Priority | Deliverable | Completion gate |
| --- | --- | --- | --- |
| 1 | P0 | Reproducible baseline and focused regression harness | Existing behavior recorded; release build configuration validated; critical scenarios reproducible |
| 2 | P0 | Safe playlist persistence and latest-request ownership | Failed/cancelled refresh preserves usable data; stale loads cannot commit |
| 3 | P0 correctness; P1 optimization | Correct EPG concurrency, cancellation, and cache semantics | Deterministic race tests pass; bounded requests and memory; request optimization evaluated separately |
| 4 | P1 | Explicit playback/session ownership | Release/recreate and rapid-switch tests pass without stale work or player leaks |
| 5 | P1 | Targeted simplification and justified performance improvements | Behavioral parity; measured gains for performance changes; documented no-change decisions otherwise |
| 6 | P2 | Verified removal of redundancy and unused material | Reviewed deletion inventory; debug/release checks and device smoke tests pass |
| 7 | P1/P2 | Kotlin/toolchain modernization and remaining static checks | Compatible dependency matrix; CI enforces selected quality rules |

Apply small, independently reviewable changes. Introduce test seams when their corresponding regression tests need them. Toolchain modernization should be a separate series from playback and persistence changes; move forward an individual upgrade only if it blocks a required fix.

P0 means correctness/data-preservation work to do before broad refactoring, not a verified production incident severity. P1 covers lifecycle hardening and measured performance; P2 covers cleanup. Add each regression test to CI with its fix, and establish lint/release-build feedback in stage 1 rather than waiting for stage 7. Start static inventories early; defer deletions until their evidence and regression coverage are ready.

**Implementation rules and explicit defaults**

- Fix a demonstrated failure in the existing class first. Add a helper/class only to give shared mutable work one owner, enable a necessary test, or remove proven duplicate policy. File size alone is not a reason to split a class.
- Keep the single app module, Hilt, Room, DataStore, Media3, existing playback command processor, and current UI. This plan does not require new architecture layers, a generic coordinator framework, a global event bus, a networking-library replacement, or a full state-machine rewrite.
- Distinguish investigation from implementation. For a suspected race/leak, add a focused reproducer or trace; if it does not establish a problem, retain the behavior and record the evidence. Static proof of a deterministic issue is sufficient to justify its regression test and fix.
- Preserve provider request formats, current freshness behavior, supported playlist formats, live/archive/timeshift behavior, and preview-specific settings by default. Changes to those contracts need evidence in the relevant work item, not an assumption that generic best practice is compatible.
- Use existing `Result` and small feature-specific data types where sufficient. Inject only the clock/dispatchers or transport needed by the affected tests. Do not introduce a general error framework, production byte-accounting service, or benchmark framework for this refactor.
- Source changes retain the previous snapshot in storage for recovery but must not initialize playback from that snapshot as if it belonged to the newly selected source. Keep any already-running playback behavior unchanged. Use the existing error/empty UI on failed loading of the selected source; do not add an automatic provider fallback. Explicit playlist removal continues to clear the selected playlist.
- Room is authoritative for committed channels and their current favorite/aspect-ratio values. DataStore favorites remain a recovery backup, and the playlist hash is an optimization hint. A missing/inconsistent hint causes safe revalidation, never acceptance of a mismatched source snapshot. An imported playlist file is source input, not a downloaded URL cache; treat file-save recovery separately from URL reload.
- Performance targets, payload limits, supported historical schemas, and backend response completeness are not yet established. Resolve them using the evidence gates below before their dependent changes. Missing evidence blocks that change only; independent fixes continue.

**1. Establish the baseline before structural changes**

The current `.github/workflows/ci.yml` runs debug compilation and unit tests. It does not run lint or validate a minified release in that job. The separate tag-triggered `.github/workflows/release.yml` already runs release unit tests and assembles the release APK before publishing. Add non-publishing release validation before tagging; do not duplicate its deployment steps. `app/build.gradle` references `app/proguard-rules.pro`, which is absent from this checkout. Validate the release task and resolve that configuration explicitly; the missing reference is a configuration finding, not a reproduced build failure.

Record cold/warm startup to first usable UI and first video frame, channel-switch latency, EPG request counts, playlist parse/load duration, frame timing, heap/PSS, decoder instances, and APK size. Use a representative constrained STB plus a mobile device/emulator, with preview enabled and disabled. Keep device, playlist, network conditions, build type, and sample count alongside measurements. Separate network latency from local processing.

Start with existing unit tests, lint reports, and non-publishing release assembly. Capture a small baseline for the affected behavior before each change; completing every device scenario is not a prerequisite to fixing data loss or cancellation. Add coroutine test scheduling, injectable clock/dispatchers, fake repositories/transport, and targeted Room tests only as corresponding fixes require them. The final regression matrix covers offline startup, malformed/large input, rapid channel changes, archive/timeshift transitions, parental locks, favorites, settings return, Activity recreation, background/foreground, and clock/timezone changes. If target hardware is unavailable, continue JVM/static work and mark device-dependent validation pending; do not claim device performance readiness.

**2. Make playlist updates failure-safe and ordered**

Source-confirmed: `LoadPlaylistUseCase.reload()` at line 243 clears the preference cache and channel database before fetching/parsing the replacement. A network failure therefore loses the persisted offline channel snapshot. The DAO's replacement transaction cannot protect data already cleared before it starts.

Plan: fetch, validate, and prepare the replacement first; publish it through the existing transaction only after success. Preserve favorites and aspect ratios within that operation. Keep the last known good snapshot on failure. `clearPlaylistCache()` only removes the stored hash; it does not delete the imported file. URL reload does not write that file. Test Room/DataStore interruption for URL reload and file/DataStore interruption separately for file import.

Persist the committed source identity with the Room snapshot in the same transaction, using the smallest schema addition that supports that invariant. Use an in-memory request revision for obsolete-result rejection; a durable revision journal is unnecessary. Update secondary preference hints after the database commit and make a interrupted hint update recover through revalidation. Treat preexisting snapshots without source identity as unverified until the selected source has been successfully validated; do not silently label old data with the current source. Test interruption immediately before/after the database commit and before preference writes, plus file import publication failure. This schema change requires a tested migration from version 3 and the historical-version evidence gate below.

`MainViewModel.loadPlaylist()` launches an IO coroutine per invocation; startup also launches a delayed refresh. Request IDs guard several UI operations, but `LoadPlaylistUseCase` writes persistence before returning to those guards. Risk: an older request can finish later and overwrite stored data even when its UI result is rejected.

Give refresh ordering one shared owner: default to a Hilt singleton around the existing load use case with a request revision and a short commit mutex. Capture the source once, and validate its identity/revision at the serialized commit boundary. Source-selection and commit validation must participate in the same ordering so a source edit cannot race the final check. Keep network work outside the commit lock. Cancellation reduces obsolete work; revision validation provides correctness when cancellation arrives too late. Do not add request coalescing unless duplicate traffic is measured. Check ownership again after suspensions and before publishing errors or playback changes.

That owner must be shared by main-screen startup/reload and `SettingsViewModel.reloadPlaylist()`, which also calls the use case; a job or mutex local to MainViewModel is insufficient. Both reload callers currently select IO explicitly. Audit main-safety at the use-case/repository boundary so synchronous parsing and preference cache-file operations do not depend on caller dispatching, including the separate file-save path. Add failure/cancellation handling to the settings reload path, including resetting loading state.

Also make favorite toggling atomic: `ChannelRepositoryImpl.toggleFavorite()` currently reads a row and then writes its inverse separately. Concurrent toggles can lose an update. Default to a Room transaction for read/toggle/result, preserving the existing repository API. Serialize its backup publication with other favorite backup writes and derive that backup from committed Room values rather than an old caller snapshot.

Test favorites and aspect-ratio edits during an in-flight playlist refresh as well as repeated toggles. A refresh must preserve edits made after its initial read; transactionally inserting a stale snapshot alone does not guarantee this. Publish corresponding UI updates from the current state, and keep the DataStore favorite backup from restoring an older value.

Acceptance: failed same-source forced reload retains the prior playable snapshot after restart; out-of-order source A/B responses cannot commit A over B; repeated favorite actions have deterministic results; preferences survive refresh and migration. Before changing schema, inventory versions actually shipped: only schema 3 is checked in. Recover historical schema evidence where needed and define the supported upgrade matrix; do not invent migrations from unavailable schemas. Replace destructive migration fallback for supported paths with tested migrations (`AppModule.kt` currently uses `fallbackToDestructiveMigration()`).

**3. Correct EPG concurrency and cache behavior**

The following issues are visible in `EpgRepositoryImpl.kt` and `MainViewModel.kt`:

| Evidence | Impact or risk | Planned change |
| --- | --- | --- |
| Separate `windowInFlight` and `batchInFlight` registries (lines 73–74) | A single-channel request and a batch containing it do not share work; overlapping batches also bypass exact-key sharing | Keep exact-key sharing initially. Measure overlap; consolidate identical logical channel/window requests only if material duplicate traffic is shown. Arbitrary interval coverage/coalescing is outside this plan |
| Windows overlapping now bypass cache | Intentional freshness policy causes repeated sequential network work | Preserve this behavior through correctness fixes. Evaluate a bounded freshness policy separately, with an explicit maximum staleness and guaranteed refresh trigger |
| One `currentProgramsCacheTime` for the whole map (lines 266–276, 344) | Refreshing one channel renews unrelated stale entries; a missing key in a fresh map returns null without consulting channel programs | Track freshness per entry, distinguish absent from cached-empty, and expire at program boundaries |
| `rememberProgramsForChannel()` returns immediately for an empty result (line 354) | An authoritative empty window leaves old programs in the merged channel cache | Replace the covered range even when successful results are empty |
| Single fetch converts transport failure into an empty list | Failure can be cached as valid empty data | Model success-empty separately from transport/parse failure; keep stale data explicitly on failure |
| Channel/current caches use only `tvgId`, while window keys include `epgUrl` | Source changes or overlapping providers risk mixing data | Include source/generation identity consistently across caches and responses |
| Cache clear and response publication span different locks | An already-returned response may repopulate cleared state | Use a repository-local generation and short cache-state mutex for invalidation and result publication; keep fetch/parse outside that mutex |
| In-flight cleanup acquires a suspending mutex in `finally` | A cancelled waiter can miss cleanup if lock acquisition suspends | Make the short bookkeeping cleanup cancellation-safe; never hold that protection over network work |
| Past/future paging use separate mutexes and compute merged lists before `_viewState.update` | Both completions can read the same old list and one overwrite the other | Merge from the current state inside a pure atomic update; validate panel generation inside it |
| Both paging methods return on `added.isEmpty()` before updating loaded bounds | A successful empty page is repeatedly fetched and prevents progress beyond the gap | Advance coverage for successful empty pages; keep failed windows retryable; test empty gaps followed by populated pages |
| `cacheCurrentProgramSnapshot()` runs for past/future-only fetches too | A page that does not cover now writes null over a valid current-program entry | Only update this snapshot from a successful response whose requested window covers now |
| `updateCurrentProgram()` catches `Exception` at line 2135 after suspending calls | Cancellation is logged as failure and can clear current-program state | Rethrow cancellation; audit every broad catch and suspending `runCatching` path |

Source-confirmed memory limitation: the parser collects every program before later cache clamping. `safeNextString()` calls `nextString()` before truncation, so truncation does not prevent allocation of a huge incoming string. Bound decompressed response bytes, channel/program counts, and requested windows. Retain tolerant handling where intended, and report malformed payloads separately from empty schedules.

Cache capacity is not a full memory budget: `windowCache` retains lists separately from the 512-program channel clamp, and the current-program map can accumulate keys beyond channel-cache eviction. Keep the existing cache structure initially; bound response bytes/counts before accumulation, apply bounded entry/program retention to window lists, and evict associated current entries with channel eviction. Derive limits from representative fixtures and verify peak/retained memory with profiling. Count bounds plus measured memory are sufficient; do not build exact object-heap accounting. An oversized or truncated response must not be recorded as complete coverage.

Define the backend contract before changing window replacement or freshness. Establish whether a response is complete for each requested channel/window, what an omitted channel means, and whether `last_epg_update_at` is an ordered version or only an opaque change token. That token is available only after a fetch, so it cannot by itself invalidate cached data when all network requests are skipped. Preserve nightly-refresh behavior and test concurrent old/new backend responses. Specify interval boundary semantics once for filtering, merging, paging, and current-program selection; avoid assuming arbitrary window canonicalization preserves the provider contract.

Audit time normalization in `EpgProgram.kt`: `startUtcMillis`/`stopUtcMillis` call parsers on every access despite stored epoch fields; local-time parsing retains the timezone captured when each thread-local calendar was created; `isCurrent` includes both start and stop boundaries. Test adjacent programs at the exact boundary, timezone change on a reused worker thread, malformed timestamps, and the provider's actual timestamp forms. Normalize timestamps once with an explicit zone policy, and use one consistent interval convention after verifying backend compatibility. Use monotonic elapsed time for TTL/timeout age and wall-clock time for broadcast scheduling.

Verify transport cancellation with a deliberately stalled server. `HttpURLConnection` completion callbacks and cancellation of a coroutine around blocking reads are not evidence that the socket aborts promptly. Retain the current transport if a small cancellation fix plus its existing timeouts meets the test; use a narrow adapter only if necessary. Add cooperative cancellation checks to substantial parsing loops. Audit gzip/deflate handling consistently across playlist and EPG transports.

Acceptance: concurrent identical requests produce one underlying fetch; single/batch overlap follows the documented sharing policy; cancelling one waiter preserves remaining callers; cancelling the last waiter releases work; old-generation responses cannot publish; simultaneous past/future paging retains both pages; valid empty responses remove obsolete programs; fresh A does not prolong stale B; oversized payloads fail within a defined memory budget. Test clock rollback, midnight, timezone/DST changes, and backend regeneration ordering.

For request-sharing tests, coordinate callers while transport is still in flight; a later intentional refresh is not a duplicate-request failure. Also assert that shared results are committed once per request generation, not repeatedly by each waiter. Include close/reopen of the same EPG channel while an old page is loading, since matching `tvgId` alone does not identify the current panel session.

**4. Clarify playback and preview ownership**

`PlayerManager.kt` is 1,883 lines. It combines command processing, construction, retry classification, FFmpeg fallback, DVR progress, buffering checks, manifest probes, and logging. It already serializes commands: strengthen that owner instead of adding a second competing playback abstraction.

Keep `PlayerManager` as the session owner responsible for ExoPlayer access, listeners, command results, and release. Extract a pure retry or DVR calculation only when a focused test needs it, or a common construction helper when equivalent duplicated settings are confirmed. No mandatory four-component split is required. Its application-looper requirement should be explicit and tested, consistent with [Media3 guidance](https://developer.android.com/media/media3/exoplayer/hello-world).

Audit both independently created supervisor scopes and all anonymous delayed jobs. `release()` cancels named jobs but does not cancel the supervisor scopes themselves. This is an ownership/recreation risk to test, not proof of a leak. Define reset versus final disposal, ensure pending command responses complete, and prevent delayed work from an old session acting on a new player.

Main and preview construction repeat renderer, HLS extractor, track-selector, and load-control setup. Share equivalent configuration through a small helper only where that reduces duplicated policy; retain explicit separate values for differing behavior. Preserve preview-specific bitrate, mute, lifecycle, and decoder-resource policies. Two player instances are intentional here; their existence alone is not redundant code. Retain graceful preview shutdown when decoder resources are unavailable.

Acceptance: rapid live/archive/live commands, retries during channel changes, release during initialization, repeated recreation, and preview opening/closing cause no wrong-thread access, stale playback restart, hanging command result, or retained decoder instance. Test software decoder fallback on representative hardware.

**5. Reduce orchestration complexity and improve measured performance**

`MainViewModel.kt` has 2,299 lines; `PlayerScreen.kt` 2,000; `SettingsScreen.kt` 1,819. These are review hotspots, not mandatory rewrite targets. Extract only the responsibilities already touched by fixes when doing so removes duplicated policy or makes state ownership/testability clearer. EPG paging is a likely boundary because it already has independent state and races. Leave unrelated parental-access, focus, and settings structure intact unless the audit establishes a concrete issue. Keep the current single app module.

Document the existing owner of each shared mutable state; correct conflicting ownership discovered by tests. Review overlapping playback flags in ViewModel/player/UI state and introduce a mode type only if it fixes a demonstrated invalid combination. Preserve existing focus and remote-key behavior with interaction tests before changing that code. Separate frequently changing playback progress from expensive list-level state only where measurements show a benefit. Follow [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations).

Concrete performance candidates:

- `PlaylistParser.parse()` creates `content.lines()` and multiple regular expressions per EXTINF entry. Reuse compiled expressions and evaluate incremental line processing, preserving supported playlist syntax/encoding.
- Channel replacement restores aspect ratios with per-row updates. Prepare complete entities before insertion where semantics allow, avoiding repeated SQL round trips.
- EPG caches repeatedly copy maps, merge, and sort lists. Batch publication and reduce allocations after correcting cache semantics; evaluate interval lookup only if profiling justifies it.
- Inspect Compose compiler reports already configured in `app/build.gradle`, list keys/content types, effect keys, and state-read scope. Use release frame measurements to validate changes; avoid speculative stability annotations. See [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance/bestpractices).
- Measure preview bandwidth/decoder/heap overhead, logo cache behavior, and hidden-screen EPG activity. Lifecycle-aware UI collection does not automatically stop independently launched ViewModel work.

Acceptance: preserve feature behavior and show before/after results. Set numerical budgets from baseline variance before evaluating an optimization; there is no universal percentage gate. For startup/switch optimizations, alternate baseline/candidate runs with identical timing endpoints, device, and fixture. Start with 30 runs as an investigation sample, report median and spread, and increase the sample when necessary; a stable p95 claim requires enough tail observations beyond that initial sample. Cold-start runs must actually reset the app process. Use a deterministic fixture where available and separate real-provider validation. Require a measured benefit in the target metric for performance changes; correctness/simplification changes require no unexplained regression. Run one two-hour playback/preview/channel-switch soak for the final playback/concurrency candidate, plus accelerated clock/midnight tests. Smaller unrelated cleanup does not require repeating the soak. Record memory trend and active requests/decoders. If no optimization is justified by measurements, record that outcome and close the candidate without a code change.

**6. Verify redundant code, dead code, assets, and dependencies**

Initial reference-scan candidates are `TimeFormatter.formatDate()` and `formatDuration()`, plus `favorite_filled`, `favorite_empty`, `lock_indicator`, `cd_locked_channel`, and `cd_open_epg` in both default/Russian strings. Confirm semantic references before removal. For accessibility strings, determine whether they should be wired into the UI instead. The initial scan found no unreferenced non-values resource candidate; that is not a complete packaged-asset audit.

Use IDE/compiler inspections and Android Lint across source sets, then review R8/resource shrinker output and release APK contents. Account for Hilt/Room generation, manifest roots, overrides, XML references, reflection, library resources, and dynamic `getIdentifier` calls in `PlayerScreen.kt`. DI providers and framework callbacks found only at their declaration are not dead code. Theme names containing dots also produce false positives in simple reference matching.

Run a Kotlin-aware duplicate-code scan and review semantic overlap in player construction, startup/manual playlist publication, EPG merge/window policies, archive actions, and settings setters. Record each candidate as remove, consolidate, retain intentionally, or unresolved, with evidence. Avoid collapsing superficially similar functions with different lifecycle or failure contracts.

Inspect resolved dependencies and APK sizes before removing libraries. For example, `coil-gif` supplies the `ImageDecoderDecoder` used in `RuTvApplication`; it is not unused merely because the app is a video player. Review native ABI libraries, image densities, manifests, and debug-only diagnostics with device coverage in mind. Fix the stale EPG repository path in README during documentation cleanup.

Acceptance: a reviewed inventory with no unexplained candidates, no missing resource crashes or accessibility regressions, working Russian/default UI and remote controls in a minified release, and APK/dependency size deltas recorded. Do not claim a mathematical proof that no dead code exists; establish repeatable checks and justified exceptions.

**7. Align with current Kotlin practices and keep checks permanent**

The project uses Kotlin/Compose compiler plugin 2.0.21, AGP 8.7.1, KSP 2.0.21-1.0.25, coroutines 1.8.1, and Compose BOM 2024.10.00. Version age alone does not establish incorrect code. Select supported stable versions during implementation using official compatibility/release documentation, including the Media3/Nextlib pairing and Hilt/Room/KSP generated code. Validate upgrades incrementally rather than setting every component independently to latest.

| Practice | Current assessment | Planned enforcement |
| --- | --- | --- |
| Typed compiler configuration | `android.kotlinOptions` remains | Migrate to `kotlin.compilerOptions`; Kotlin documents deprecation since 2.0.0 in its [compiler options guide](https://kotlinlang.org/docs/gradle-compiler-options.html) |
| Structured concurrency and cancellation | Existing ViewModel scopes and cancellation rethrows are good, but coverage/ownership has gaps | Inject dispatchers and long-lived scope ownership; main-safe suspend APIs; targeted cancellation tests, following [coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) |
| Immutable public state | StateFlow and immutable collections already present | Preserve them; keep atomic update lambdas pure; prevent mutable collection aliasing |
| Explicit failures | Custom Result and nullable EPG failures have inconsistent meanings | Use clear domain outcomes for failure, valid-empty, and stale data; map messages at presentation boundary |
| Readability and API boundaries | Large files, broad visibility, scattered helpers/imports | Apply focused lint/formatting/static-analysis rules based on [Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html); narrow visibility and remove unused declarations only with evidence |

Extend the CI gates established in stage 1 with the remaining focused static checks. Use explicit verification tasks such as `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintRelease`, and non-publishing `:app:assembleRelease`, confirming available tasks/toolchain during implementation. Add instrumentation/migration checks where they exercise platform behavior. Introduce a baseline for reviewed existing issues and fail on new issues; avoid blanket suppressions or an unreviewed zero-warning mandate. Correct the build comment implying the Compose BOM controls compiler versions: compiler plugin and library BOM have separate responsibilities.

Record Kotlin practice compliance as compliant, gap, justified exception, or not assessed, with source evidence and the enforcing check. The present review establishes several gaps but is not a complete compliance certification. Inspect actual main-safety, cancellation, mutability, null handling, equality/stability, visibility, and API deprecations across the remaining files; do not treat a compiler upgrade or formatter pass as evidence for all of these.

**Evidence gates and bounded work items**

These checks are part of implementation, with explicit outcomes. They do not require a new general planning round.

| Gate | Evidence to collect | Decision/default if evidence is missing |
| --- | --- | --- |
| Build baseline | Existing tests, lint output, and release assembly on this checkout | Record an actual failure before changing build configuration; fix only the blocking configuration first |
| EPG response completeness | Backend implementation/docs or controlled fixtures establishing empty versus omitted channels, window inclusion, and regeneration token meaning | Treat omitted/ambiguous data as unknown; preserve old entries without marking coverage complete. Defer authoritative deletion/empty-page advancement for ambiguous responses. Never order an opaque token as a timestamp without evidence |
| Source identity and upgrades | Version-3 schema, actually shipped earlier versions, URL/file source-switch behavior | Migrate 3 to the new schema explicitly. Do not change historical migration fallback without evidence for those paths. Existing source-unidentified data needs successful revalidation before automatic reuse; document and test the one-time offline-upgrade limitation |
| Input bounds | Representative largest valid playlist/EPG responses and existing configured limits | Keep established playlist limits; select explicit EPG byte/count limits before implementing rejection, record fixture sizes and headroom, and test just below/at/above each limit. Do not invent provider-specific window restrictions |
| Optional optimization | Duplicate-request traces, allocation profile, frame timing, or parser benchmark | Retain current architecture/behavior when benefit is not established; correctness fixes proceed independently |
| Device support | Available target STB/API/ABI and one supported mobile environment | Record tested coverage and missing coverage; never change minSdk, ABI packaging, decoder pairing, release signing, or update identity as incidental cleanup |

Implement in the following reviewable work items. Each includes its focused regression test and the relevant CI check. Work item names describe scope, not a requirement to add classes with those names.

| Item | Depends on | Files/area | Concrete result and stopping point |
| --- | --- | --- | --- |
| A. Baseline | None | Build scripts, CI, test setup | Existing checks and early lint/release feedback recorded; build configuration fixed only if necessary |
| B. Non-destructive reload | A | `LoadPlaylistUseCase`, settings reload | Fetch/validate before replacement; failures/cancellation preserve stored channels and settle loading state |
| C. Refresh ordering and source identity | B, migration gate | Load use case, source preferences, Room metadata, both ViewModels | Shared ordering and transactional source identity reject obsolete commits; restart/source-switch tests pass |
| D. Favorite/aspect-ratio consistency | B; integrate with C | Channel DAO/repository, backup writes, state publication | Atomic edits survive concurrent refresh; Room/UI/backup converge to committed values |
| E. EPG cancellation and publication | A | EPG repository, MainViewModel | Cancellation propagates, waiter cleanup completes, invalidated results cannot publish, simultaneous pages merge safely |
| F. EPG empty/current/time semantics | E, backend gate for completeness-dependent parts | EPG result contract, cache/paging, `EpgProgram`, window calculations | Errors differ from valid-empty; current cache and boundary/timezone tests pass; ambiguous backend responses follow the safe gate outcome |
| G. Bounded input/cache and transport | E, input-bounds gate | EPG parser/transport/cache; playlist cancellation if needed | Tested limits, eviction, and stalled-request behavior; retain transport if it passes |
| H. Playback lifecycle | A; integrate with C/E | PlayerManager, preview integration | Reproduce/fix session-lifetime issues; tests pass without mandatory architecture extraction |
| I. Simplification and cleanup | Relevant fixed behavior covered | Reviewed duplicate/unused candidates | Small proven removals/consolidations; retain intentional differences and generated/dynamic roots |
| J. Kotlin alignment and optional performance | A; separate from active correctness changes | Affected Kotlin/build/dependency files | Compiler DSL/static gaps addressed; compatible versions selected before upgrades; optimize only measured hotspots |
| K. Final validation | Completed required items | CI reports and representative devices | Minified build, feature smoke tests, applicable soak/performance comparison, and documented unresolved evidence gates |

Items E and the static inventory can proceed without waiting for C. A blocking backend or historical-schema question affects only the changes named in its gate. No change is considered complete merely because it compiles: run its behavioral acceptance checks. Revert or simplify an abstraction that does not remove a demonstrated problem. If a required gate remains unresolved, report partial completion of that item rather than declaring the entire refactor finished.

**Completion criteria**

All required correctness scenarios pass deterministic regression tests and their evidence gates are resolved. Device validation covers core live/EPG/archive/timeshift flows, focus, parental controls, preview, offline recovery, and lifecycle transitions. Performance changes meet budgets recorded from the baseline. Unused/duplicate candidates are resolved with evidence, including justified retention. Dependency/toolchain compatibility and Kotlin checks are recorded in CI. Each implementation change is independently reviewable; persistence migrations include an explicit compatibility/recovery strategy rather than assuming a code revert can reverse a database migration. Conditional optimizations can be closed without implementation when evidence does not justify them.

This document is the planning deliverable. Full runtime robustness, performance, and dead-code clearance remain validation work for the implementation stages above.

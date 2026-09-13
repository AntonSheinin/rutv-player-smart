# EPG playing-program focus refactor plan

Prepared and reviewed 2026-09-13. Status: ready for implementation. This document changes no application behavior by itself.

## Intended behavior

When the EPG opens for the channel being watched, initial focus and scroll position identify the program whose media is playing.

| Playback and EPG context | Initial focused program |
| --- | --- |
| Live playback on the EPG channel | The program airing at wall-clock time. |
| Archive playback on the EPG channel | The `programDvrProgram` archive entry. |
| Current-program timeshift on the EPG channel | The `programDvrProgram` entry, including after wall-clock time advances into the next program. |
| EPG for a channel other than the playing channel | That EPG channel's wall-clock current program. |
| Playback target cannot be found after its one required load finishes | The wall-clock current program, or the first program when none is current. |
| No programs are available | The existing loading or empty state, with no selected item index. |

The current-airing styling continues to represent wall-clock time. Assigning initial focus must not invoke a program or start playback. After the first directional key, program click, date-picker action, or program activation, the EPG must never jump back to the opening target.

## Current implementation and root cause

- `programDvrProgram` identifies the program used by archive and current-program timeshift playback.
- `PlayerUiState` carries the playback mode, playing channel, EPG channel, and DVR program, but `PlayerScreen` does not pass an opening target to `EpgPanel`.
- `EpgPanel` derives its initial index, list position, and focused item solely from wall-clock time.
- A normal EPG opening loads today's window, so an older playing archive program may not be present.
- `currentProgram` is refreshed to the wall-clock entry during EPG loading and therefore is not a playback target.

## Minimal state contract

Add one nullable `epgOpeningState: EpgOpeningState?` field to `MainViewState` and `PlayerUiState`. It belongs to one accepted opening request, including live openings, and is consumed once the panel is shown:

```text
EpgOpeningState(
    session: Long,
    channelTvgId: String,
    target: EpgProgram?,
    targetLoadPending: Boolean
)
```

Create the state synchronously when an EPG opening is accepted. Populate `target` only when all these conditions hold:

1. Playback is archive or current-program timeshift.
2. `programDvrProgram` is non-null.
3. The requested nonblank `tvgId` equals the current playing channel's nonblank `tvgId`.
4. The target has a usable identity: either a nonblank ID or positive timestamps with `stopUtcMillis > startUtcMillis`.

The session is the new value of `epgPanelGeneration`. The opening job may clear an unusable ID-only target while choosing the window; after that choice, the target is fixed for the session and does not follow later playback-state changes. A playback-mode change while the EPG remains open does not restart initial focus. Closing or replacing the request clears its state. The value may exist briefly before the asynchronous cache read makes the panel visible, but it must not survive a cancelled or closed request.

For an opening without an eligible target, set `target = null` and `targetLoadPending = false`. For an eligible target, initialize `targetLoadPending = true` while the opening job checks cache and chooses its single request window. If an ID-only target matches a cached row with valid timestamps, keep the captured identity and use the cached row's day. If neither the captured target nor its cached match provides valid timestamps, clear the target, set pending to false, and use today; the application cannot display or request a meaningful day for that entry. Keep the chosen window local to the ViewModel job because Compose does not consume it.

Use one canonical program identity everywhere focus must survive refreshed objects or list prepends:

- A nonblank program ID is the identity.
- Otherwise, the identity is the valid `(startUtcMillis, stopUtcMillis)` pair.
- Programs without either form have no restorable identity.

Represent the identity as a small value type rather than a concatenated string, so an ID cannot collide with timestamp text. Matching is exact; do not add title, description, fuzzy-time, or object-equality alternatives. If malformed provider data contains multiple matches, choose the first chronologically sorted entry.

`targetLoadPending` describes only whether another request can still provide the opening target. It is not tied to `isEpgLoading`:

- Set it to `false` immediately when cached programs already contain the target.
- Keep it `true` only while a required target-day request is running.
- Set it to `false` after that request succeeds, returns no match, or fails.
- If the target has an ID but no valid time range and is absent from cache, set it to `false`; there is no date from which to request more data.

## Loading flow

Use exactly one continuous opening window. Live playback and EPGs for another channel use today. Archive and timeshift playback on the EPG channel use the local calendar day containing the playing program's start. Do not combine the archive day with today and do not insert a detached page into a today-based list.

For each accepted opening:

1. Read repository-cached programs and determine the opening window using the rules above.
2. Filter cached programs to entries overlapping that window and show the filtered list immediately.
3. If the filtered cache contains the target, set `targetLoadPending = false`; the background refresh may replace metadata but must preserve focus by identity.
4. Fetch the opening window once, replace that window's programs through the existing page merge, and set continuous loaded coverage to that window.
5. Finish `targetLoadPending` after that request succeeds or fails.

At the start of every session, including a same-channel reopen, replace `epgPrograms` with the cache entries filtered to the chosen window and reset `epgLoadedFromUtc`/`epgLoadedToUtc` to zero. Set those bounds to the selected window only after its request succeeds, including an empty successful response. Never carry coverage from the previous session merely because its `tvgId` is unchanged.

Preserve the current loading indicator behavior: a nonempty filtered cache opens without the empty-list spinner; an empty cache keeps `isEpgLoading = true` until the opening request finishes. `targetLoadPending` controls only delayed target selection and must not replace the existing spinner flag.

Keep the existing `Mode.Today` path unchanged. Add a non-suspending `dayContaining(utcMillis, zoneId = ZoneId.systemDefault()): EpgWindow` function to `ComputeEpgWindowUseCase` for the target day, using local start-of-day boundaries so daylight-saving changes are handled correctly. Fetch today through the existing `FetchEpgProgramsUseCase`; fetch a target-day window through the existing repository window method, as paging already does. Include programs that overlap the selected half-open window; a program that starts before midnight and continues into the target day remains visible.

Because the initial list and `epgLoadedFromUtc`/`epgLoadedToUtc` describe one continuous window, existing Up/Down edge paging, date-picker loading, loaded-date indicators, and `ensureEpgForDateRange` retain their current behavior. Opening an archive day changes only the initial window and initial focus. Today remains available through the existing date picker and forward paging.

Apply every cached and fetched result only when both the generation and EPG channel still match. Cancellation is not an error. If the opening request fails, retain filtered cached programs and mark `targetLoadPending = false` so the UI can make its final selection.

Only a today-window response may refresh the wall-clock `currentProgram` and `currentProgramsMap` values using the existing logic. An archive-day opening preserves both values; it must not publish `null` merely because an old window has no currently airing entry. `programDvrProgram` remains the independent playback target.

## Focus lifecycle

Pass `EpgOpeningState` to `EpgPanel` and key initialization state by `session`: initial-resolution status, `LazyListState`, focused identity/index, and pending centering. The session therefore resets a live opening as well as an archive opening when the same channel is reopened. Keep the existing edge-request guard behavior, including its reset when list size changes.

Resolve initial focus with this order:

1. The exact opening-target identity.
2. The wall-clock current program, using `startUtcMillis <= now < stopUtcMillis`.
3. The first program.

While a target is absent and `targetLoadPending` is true, request focus for the list container but leave item selection unresolved. When the target request finishes, select and center the target if present; otherwise resolve the wall-clock/first choice once. Empty data keeps the item index at `-1` and resolves when programs arrive or loading finishes.

Maintain a local `automaticOpeningFocusEnabled` flag initialized from the session. Set it to `false` before handling any user directional navigation, click, date-picker selection, OK/Enter activation, or focus-manager callback that explicitly selects an index. A late load may merge data after that point but must not alter the user's selected program or scroll position.

After automatic opening focus is resolved or cancelled, preserve selection across list replacement and paging by canonical program identity. An identity-less selection remains usable while its current list is unchanged, but cannot be restored after list replacement; then choose the wall-clock current program or first entry once. Do not compare object references or introduce fuzzy matching.

Compose list keys and focus identity have different needs. Build each program item's Compose key from ID, start, stop, and its occurrence number among otherwise identical keys. This makes keys unique for duplicate provider records. Never store that occurrence-based UI key as the selected-program identity.

Use the existing border and centering behavior for the selected archive item. Keep date delimiters, lazy paging, date picker, archive eligibility, OK/long-press behavior, and current-airing styling unchanged. The half-open wall-clock check is an intentional boundary correction: at an exact program boundary, focus the newly started program.

## Navigation and search invariants

Only initial window and initial item selection change. Existing input handlers retain their current return values and actions:

| Input or feature | Required behavior after refactor |
| --- | --- |
| DPAD Up/Down | Move to the adjacent program and call the same past/future paging callbacks at list edges. |
| DPAD Left | Keep the current playlist/channel-panel transition and EPG-close behavior. |
| DPAD Right | Open the same date picker, initially positioned on today. |
| Date selection | Use the existing `onEnsureDateRange` request and focus the first program in the selected day as it does now. |
| Short OK/Enter | Run the existing archive-play or program-details action for the selected item. |
| Long OK/Enter | Run the existing long-press program action. |
| Back and close icon | Close the same panel hierarchy as today. |
| Touch click/scroll | Keep current click actions and manual scrolling. |
| Channel search/filter | Remain untouched; no query, filtered-channel, keyboard, or focus code is shared with this refactor. |

Disabling automatic opening focus on user input is added at the start of the existing handler path; it must not consume an additional event or replace the handler's current action.

## Closing and session ownership

Create one private `invalidateEpgPanelSession()` operation in `MainViewModel`. It increments `epgPanelGeneration` and cancels and nulls `epgPanelLoadJob`. Add one `MainViewState.withEpgPanelClosed()` reducer that changes only EPG-owned fields: it sets `showEpgPanel = false`, `isEpgLoading = false`, and clears `epgOpeningState`.

Every command that closes the EPG must call `invalidateEpgPanelSession()` before its state update and apply `withEpgPanelClosed()` within that update. This includes explicit EPG close, channel playback changes, opening/closing the channel list, playlist closure, and archive/timeshift starts. Each caller retains its existing behavior for `selectedProgramDetails` and all non-EPG fields. This keeps larger playback state changes atomic while giving every close path identical EPG cleanup. Do not leave direct close assignments that can preserve a stale opening record or allow an old load to update a later panel. Opening a new EPG always creates a new generation, including reopening the same channel.

## Implementation sequence

1. Add canonical program-identity and initial-index resolver helpers. Update program UI-key generation so duplicate records cannot create duplicate Compose keys.
2. Add `EpgOpeningState` to view/UI state. Create it and capture any eligible target in `openEpgForChannel` before starting asynchronous work.
3. Add the pure `dayContaining` calculation to `ComputeEpgWindowUseCase`. In the existing opening job, choose one opening window, filter cached programs to it, fetch it once through the defined existing API, and update `targetLoadPending` under generation/channel guards.
4. Pass the opening session and focus record through `PlayerScreen`. Refactor `EpgPanel` initialization and restoration around canonical identity, one-shot automatic focus, and cancellation on user input. Remove the unused `isArchivePlayback` parameter.
5. Introduce the centralized close transition and replace every direct EPG-closing state mutation with it.
6. Update the English and Russian user-manual EPG sections to state that archive and timeshift openings focus the playing program.

## Automated verification

Add focused tests for behavior that can regress:

- Archive and timeshift targets win over a different wall-clock program on the playing channel.
- A different EPG channel receives no playback target.
- Exact ID identity survives refreshed metadata and list prepends; timestamp identity works when ID is blank.
- An ID-only target uses the valid timestamps of its exact cached match; without such timestamps it follows the documented today behavior.
- The target day uses device-local boundaries, including a daylight-saving transition; an old target causes one opening-window request and never a request through today.
- Cached programs are filtered by overlap with the selected window, and loaded coverage equals that continuous window.
- Same-channel reopen clears previous programs and coverage before applying the new opening window.
- Archive-day loading does not clear or replace wall-clock `currentProgram`/`currentProgramsMap`; today loading retains the existing update behavior.
- Cached and empty-cache openings retain the existing `isEpgLoading` indicator behavior.
- A missing or failed target load resolves wall-clock/first selection exactly once.
- User navigation while the target request is pending prevents a later automatic jump.
- Duplicate IDs produce unique Compose item keys while target matching selects the first chronological match.
- Closing through each affected ViewModel path invalidates the generation, cancels the job, and clears opening state.
- Closing and reopening the same channel resets list/focus state; a stale result cannot affect the new session.
- Empty programs never create an invalid selected index.
- Up/Down, Left/Right, date selection, short/long OK, Back, touch, and channel-search behavior retain the invariants above.

Prefer unit tests for pure identity, window, merge, and selection helpers. Add one Compose interaction test for the pending-load/user-navigation race because that behavior depends on real `EpgPanel` effects and input handling.

Run the repository gates from the root using the checked-in wrapper JAR and Android Studio JBR because this Windows checkout has no `gradlew.bat`:

```powershell
& 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --console=plain
```

## Device acceptance checks

On the development STB with a catch-up-capable channel:

1. Play an archive program from an earlier day, open EPG, and confirm that program is focused and centered.
2. Repeat after seeking and after closing/reopening the EPG on the same channel.
3. During archive playback, open EPG for another channel and confirm its wall-clock current program is focused.
4. Return to live and confirm the playing channel opens on its wall-clock current program.
5. Start current-program timeshift, cross a program boundary when practical, and confirm EPG remains focused on the timeshifted program.
6. Navigate immediately while an older target is loading and confirm the eventual result does not move focus.
7. Verify DPAD Up/Down, OK, Back, date picker, past/future paging, and touch scrolling retain their existing actions and do not start playback merely by assigning focus.

Record the APK version/hash, device and Android version, channel, target times, playback mode, and pass/fail evidence. Restore live playback after testing.

## Scope and explicit assumptions

This refactor does not change archive URL construction, catch-up eligibility, retention preferences, date-picker initial date, program actions, playback resume, or the meaning of the current-airing indicator. It adds no persistent preference and does not load full catch-up history.

The implementation relies only on these explicit contracts:

- The playing and EPG channels share the same nonblank `tvgId` when they represent the same channel.
- `programDvrProgram` is the playback source for archive and current-program timeshift modes.
- An uncached target can be requested only when it has valid start/stop timestamps.
- Repository window requests use half-open UTC ranges.

If one of these contracts is false, the defined result is wall-clock current/first focus after the opening request is no longer pending. No title matching, broad history request, multi-range coverage model, or compatibility heuristic will be added.

## Kotlin and design constraints

- Keep `EpgOpeningState` immutable and presentation-owned. The ViewModel owns request selection, cancellation, and stale-result guards; Compose owns transient focus and user-interaction state.
- Implement identity, window selection, cache filtering, merging, and initial-index selection as small pure functions. Do not add a manager, service hierarchy, generic focus framework, or repository API for this single flow.
- Reuse `ComputeEpgWindowUseCase`, the repository's window call, existing coroutine scope, `Result`, and panel generation. Preserve structured cancellation and avoid detached coroutines.
- Keep one source of truth for focus identity and one centralized EPG-close reducer. Do not duplicate focus matching or cleanup rules between ViewModel and Compose; repository cache deduplication remains outside this refactor.
- Keep the close reducer limited to EPG-owned fields; callers continue to own their unrelated playlist, playback, and details state.
- Prefer immutable collections and `copy`, exhaustive `when` expressions where applicable, named arguments for multi-parameter calls, and null-safe branches. Do not use `!!` or exception handling for normal control flow.
- Limit public API changes to the state and `EpgPanel` parameters required by this behavior. Remove the now-unused `isArchivePlayback` parameter and any replaced wall-clock-only initialization code.

These constraints keep the implementation aligned with KISS, SRP and dependency direction under SOLID, and YAGNI: one state record, one selected window, pure helpers, existing dependencies, and no speculative compatibility or multi-range infrastructure.

## Final plan review

The revised plan uses one continuous opening window, so it neither loads the archive-to-today gap nor disturbs the existing paging and date-picker model. It defines target-specific completion, cancellation by user interaction, same-channel session reset, all close-path cleanup, ID-only target handling, exact stable focus identity, duplicate Compose keys, and the half-open boundary correction. Each asynchronous mutation has generation and channel ownership. The remaining provider and device conditions are listed as explicit contracts with deterministic outcomes and acceptance checks; there are no hidden implementation decisions in this plan.

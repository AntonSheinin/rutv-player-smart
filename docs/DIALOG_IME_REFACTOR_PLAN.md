# Dialog keyboard confirmation refactor plan

Prepared and reviewed 2026-09-12. Implemented on `feature/dialog-ime-confirm` and verified locally on 2026-09-13. Device coverage and the explicitly untested cases are recorded in `docs/DIALOG_IME_REFACTOR_VERIFICATION.md`.

## Intended behavior

For input dialogs with OK/Cancel (including equivalent Save/Cancel and Remove/Cancel actions), pressing the virtual keyboard's final Enter/Done action invokes the same action as pressing the primary button. An accepted submission hides the keyboard without another remote-control click. Dialog closure follows the per-dialog rules below; an asynchronous callback returning does not imply success.

Current channel flow: open dialog -> type -> keyboard Enter -> focus OK -> press OK.

Target channel flow: open dialog -> type -> keyboard Enter -> execute OK.

| User action | Expected result |
| --- | --- |
| Final keyboard Enter/Done | Run the shared confirmation path once. Hide the keyboard on accepted submission, without waiting for its closing animation. |
| Back or keyboard hide control | Do not confirm. Preserve the existing dismissal/navigation behavior, including focusing OK when only the keyboard closes. |
| Visible OK button or remote activation of OK | Use the same confirmation action as keyboard Done. |
| Cancel before submission | Close without applying input. Cancellation after accepted submission is defined below. |
| Done with invalid input | Preserve existing validation and closure rules. If the dialog remains open, focus the correction field and keep/request the keyboard. |
| Next in a form with multiple fields | Move to the next field; only the final field submits. |

Submission must be triggered by the explicit editor action, not inferred from keyboard visibility. Keyboard disappearance alone does not identify why it closed. Do not wait for the keyboard closing animation before executing confirmation.

## Source findings and scope

| Location | Current behavior | Planned change |
| --- | --- | --- |
| `app/src/main/java/com/rutv/presentation/MainDialogs.kt`: `GoToChannelDialog` | `KeyboardActions.onDone` requests OK focus; an IME visibility observer also focuses OK after keyboard dismissal. | Route Done to the existing `onConfirm`; retain focus fallback for ordinary keyboard dismissal. |
| `app/src/main/java/com/rutv/ui/mobile/screens/PlayerPlaylistPanel.kt`: search dialog | Done hides the keyboard and requests OK focus. A separate observer also requests OK focus. | Route Done to the existing search confirmation action and remove the obsolete comment requiring the extra click. |
| `MainDialogs.kt`: `ParentalPinDialog` | Done focuses OK. | Route Done through `commit()` with the same four-digit check and error handling as OK. |
| `app/src/main/java/com/rutv/ui/mobile/screens/SettingsScreen.kt`: `NumberInputDialog` | Done already invokes `confirmAction()`. | Keep this behavior; verify consistent keyboard cleanup and validation. |
| `SettingsScreen.kt`: `UrlInputDialog` | No explicit final editor action; field is not configured as single-line. | Configure single-line URL input and Done, invoking the existing Save action. |
| `SettingsScreen.kt`: `PinTextField` and set/change/remove PIN dialogs | Every field advertises Done and moves focus to another field or the primary button. | Separate keyboard Next from final Done; final Done invokes the owning dialog's existing commit action. Keep DPAD Down navigation separate. |

Confirmation-only dialogs and settings fields outside dialogs are outside this change. No redesign of buttons, channel matching, playback, or PIN validation is required.

Existing edge behavior must remain explicit: channel confirmation in `MainActivity.kt` closes the dialog even for empty/out-of-range input, but plays only valid channels. Search stays open for blank input; nonblank searches close even when there is no match. Numeric settings display an error and remain open for invalid values. Changing those rules is separate work.

The inclusion of URL and PIN dialogs is an explicit scope decision for consistent final-action behavior. PIN removal therefore also executes its existing Remove action on Done; add no extra confirmation step. Intermediate PIN fields use Next. No automatic submission occurs merely because the expected number of digits has been entered.

## Confirmation and focus contract

Use one dialog-local submission entry point for the primary button, `RemoteDialog.onConfirm`, and the final editor action. Validate in the existing dialog/owner and classify the outcome as `Rejected`, `Close`, or `Pending`. These are proposed internal outcomes, not existing callback return values. Existing `Unit` callbacks must not be treated as success signals. Keep business callbacks in their current owners; communicate pending completion through explicit state as described below.

| Dialog | Local rejection and correction focus | Accepted behavior |
| --- | --- | --- |
| Go to channel | None that keeps the dialog open; preserve the existing invalid-number dismissal. | Mark closing before invoking the existing callback; hide keyboard; close. Play only when the number is in the current filtered-channel range. |
| Channel search | Blank after trimming: remain open, focus search field, no new error message. | Nonblank: mark closing, hide keyboard, run the existing search and close, including when no match exists. |
| Numeric setting | Empty/unparseable/out of range: existing error; focus numeric input. | Hide keyboard and call the existing confirm/dismiss path once. |
| Playlist URL | Blank: remain open, focus URL input, no new error message. | Hide keyboard, invoke existing load callback and close immediately. Loading continues in its current owner. Preserve URL value handling; do not add normalization or new URL validation. |
| Parental unlock/lock prompt | Fewer than four digits: remain open, focus PIN input; preserve current hint. | Hide keyboard; remain open while verification is pending. Close on success; on failure restore PIN focus and keyboard with the error. |
| Set parental PIN | Invalid first PIN: focus first field. Mismatch: focus confirmation field. Preserve current errors. | Hide keyboard, invoke existing save callback and close immediately, as today. Persistence errors remain in the existing settings error flow. |
| Change parental PIN | Invalid current PIN: focus current field; otherwise invalid new PIN: new field; otherwise mismatch: confirmation field. Preserve validation order/messages. | Hide keyboard and remain open pending result. Success closes; wrong PIN restores current-PIN focus; storage/read failure restores final-field focus and shows an error. |
| Remove parental PIN | Invalid PIN: focus current-PIN field with existing error. | Hide keyboard and remain open pending result. Success closes; failure restores current-PIN focus and shows an error. |

For `Rejected`, retain entered values, leave submission enabled, request the specified input focus, and request keyboard display once the field is focused and the dialog window is attached. Do not repeatedly force the keyboard open: the user can still hide it with Back. Rejection from a visible button follows the same correction behavior as rejection from Done. This is an explicit focus improvement; validation rules remain unchanged.

For `Close`, set a dialog-local closing flag synchronously before hiding the keyboard or invoking a callback. Ignore subsequent submission calls until disposal. For `Pending`, synchronously mark pending before launching asynchronous work; keep fields read-only and reject all submission entry points while pending. Hide the keyboard and suppress automatic keyboard opening; do not move focus to OK. Clear pending on the matching terminal result, then close or restore correction focus as above.

For async operations, this local flag begins as an attempt guard: synchronously ask the owner to reserve/start the request and return an explicit accepted/busy result. On busy, release the attempt guard, keep the dialog open, and do not hide the keyboard or invoke dismissal. Only an accepted request becomes `Pending` (or `Close` for set-PIN). A reopened form waiting for another session's operation is busy, not pending on a request of its own; ignore Done/button submissions until owner idle, while leaving Cancel available. This distinction prevents waiting forever for a terminal result that belongs to another session.

In the two channel dialogs, allow the existing IME-hidden observer to focus OK only when the dialog is open, idle, the field is focused, and the keyboard was previously visible. Suppress this observer during explicit submission and correction-focus restoration. On correction, reset its prior-visible marker; rearm on the next observed visible keyboard so a later manual hide still focuses OK. The correction request is one-shot, not an effect that retriggers whenever the keyboard becomes hidden. Cancel outstanding opening/focus work on close; no delayed focus request may target a disposed dialog. Preserve initial keyboard opening in these two dialogs. Other dialogs retain their existing opening behavior; adding automatic opening everywhere is outside scope.

Back first remains subject to the IME's handling. If only the keyboard closes in a channel dialog, keep the existing OK-focus fallback. If Back reaches the dialog dismissal callback, close the dialog. Neither path submits. DPAD Down remains navigation, and DPAD Center inside the virtual keyboard remains key selection.

## Asynchronous PIN ownership

This is necessary implementation work, not an optional debounce. `MainViewModel.submitParentalPin` and the settings PIN operations launch coroutines, and current UI callbacks return `Unit`. Settings change/remove currently close through `parentalPinOperationVersion`; set-PIN closes immediately. Preserve that set-PIN distinction.

- Add explicit operation status for pending dialogs in `MainViewState`/`SettingsViewState`: idle, pending, success, or failure, identified by dialog session and request. Use a fresh session ID on each opening and a fresh request ID for each accepted attempt. This makes repeated identical errors observable and prevents an old result from closing a reopened dialog. Carry these identifiers and synchronous acceptance results through all relevant callbacks in `MainActivity`, `SettingsActivity`, and their screen call sites. Keep PIN values out of identifiers and logs.
- Enforce one active PIN operation in each owning ViewModel, synchronously at entry before launching its coroutine, as well as checking pending/closing in the dialog. A second invocation while an operation is active returns busy without starting work. Clear ownership in `finally` for success, failure, and cancellation, conditional on the same request still owning it. Keep terminal result identity separate from owner busy state so cleanup cannot erase a result before the UI observes it. No time-based debounce or permanent submitted latch.
- Wrap PIN reads as well as writes in error handling; existing change/remove reads are outside their write `try` blocks. Emit a matching failure on read/write errors so pending cannot become stuck. Rethrow coroutine cancellation after cleanup. Use existing localized error resources where available and add English/Russian resources when needed; never expose exception text or PIN contents.
- For parental prompts, tie verification to the captured prompt/session. On dismiss, invalidate that session and cancel its verification job. After suspended reads and before executing the protected action, recheck that the prompt/session is still current. A dismissed prompt must not later play/unlock a channel or toggle its lock. Once a protected action has started, dismissal does not undo it.
- Settings Cancel/Back remains available during pending work and closes the dialog. An already accepted settings write continues in the ViewModel; Cancel does not promise rollback. Its result may update global settings/error state, but must not focus or close another dialog session. While that operation is still active, a reopened PIN form cannot start another PIN operation; expose the owner busy state to its submission path. When the owner becomes idle, the new form becomes usable without receiving the old form's result.
- Replace the unscoped `parentalPinOperationVersion > 0` dialog-closing effect with matching-session success handling for change/remove. Keep any unrelated consumers of the version counter intact. Set-PIN still closes immediately; its ViewModel operation is covered by the same owner guard, preventing a second save after rapid reopen.
- Failure returns the matching open dialog to editable state and restores the table's correction field. A retry creates a new request even if the PIN and error text are unchanged. Do not auto-retry or auto-submit on state changes.
- Key the parental prompt's editable input by stable session identity, not the whole prompt object: its current `remember(prompt)` would otherwise reset input when `hasError` changes. Preserve values during same-session error correction; reset on a new opening. Clear/filter per-session errors on opening so an earlier settings PIN error cannot appear in a new form.

## Implementation sequence

1. **Use a single confirmation callback per dialog.** Wire the visible primary button, `RemoteDialog.onConfirm`, and final keyboard action to the same callback. Keep validation and side effects in the existing owner. Invoke the callback directly rather than simulating a click or moving focus to the button.

2. **Fix channel search and channel number first.** Replace their focus-only Done handlers using the confirmation/focus contract above. Keep initial text focus and automatic keyboard opening. Mark closing before callbacks and prevent IME-hidden focus work during submission. Blank search retains correction focus and permits retry.

3. **Apply the contract to other input dialogs.** Implement asynchronous PIN ownership before connecting their final Done handlers. Update parental unlock and URL input, and verify numeric settings. Configure URL input as single-line with URL keyboard type and Done. Give `PinTextField` explicit editor-action configuration/callbacks: intermediate fields use Next to focus the next input, and the final field uses Done to commit. Its DPAD Down focus target stays independent of its editor action. Make numeric input single-line so its final-action intent is explicit.

4. **Keep shared changes small.** `RemoteDialog.kt` owns dialog/button focus, while `RemoteActionModifiers.kt` owns DPAD/Back navigation; neither should submit when the IME merely disappears. If keyboard cleanup and submission coordination are duplicated after the first two fixes, extract a small helper under `ui/shared/components` with the current callback and dialog lifecycle as inputs. Do not move domain validation into it or introduce a generic dialog framework. Retain existing focus policy unless a regression demonstrates a required adjustment.

5. **Check Enter delivery on the target keyboard.** Start with `KeyboardActions.onDone` and `onNext`; keep Done as the final action label throughout this change. Confirm whether the STB keyboard delivers the editor action through this path. Do not add a raw Enter listener if that already works. If it does not, record event delivery on the test device and add a text-field-only fallback for Enter/NumPadEnter, handling one non-repeated activation and consuming the corresponding event sequence. The fallback must share the same closing/pending guards and not run alongside an editor action for the same gesture. Add a reproducer for the observed sequence before accepting the fallback. Do not globally intercept Enter or DPAD Center. Supporting untested third-party keyboards is not a completion claim.

6. **Document and verify.** Update `docs/USER_MANUAL.md` for Enter confirmation, intermediate PIN Next, and the distinction between keyboard hide and submission. Complete the mandatory gates below and record results in `docs/DIALOG_IME_REFACTOR_VERIFICATION.md` during implementation.

## Acceptance checks

- Channel number: type a valid number and press keyboard Enter once; the dialog and keyboard close and that channel starts exactly once.
- Channel search: type a matching query and press Enter once; the dialog closes and the matching channel receives the same focus/centering behavior as clicking OK.
- Blank/nonmatching search and empty/out-of-range channel numbers match the current OK semantics described above.
- Back/manual keyboard hide never starts playback, applies a search, saves settings, or submits a PIN. When only the keyboard closes in the channel dialogs, OK remains reachable through the existing focus behavior.
- Cancel, visible OK, DPAD navigation, reopening a dialog, initial keyboard opening, and returning focus to the underlying screen continue to work.
- Valid numeric/URL/PIN confirmation works from Done; invalid numeric or PIN input preserves the existing error/retry flow. PIN setup/change advances between fields and submits only from the final field.
- A single action produces one side effect, including with a keyboard that emits raw key events, held Enter, and rapid IME hide/focus updates. Reopening the dialog allows a new submission.
- Check virtual text and numeric keyboards, remote input, hardware Enter if available, and touch interaction. Ensure a dismissal gesture does not leak into the underlying player.

## Mandatory verification gates

1. **Automated regression coverage.** Add executable tests for one callback per accepted submission, local-rejection retry, PIN pending duplicate suppression, success/failure/cancellation cleanup, repeated identical failures, retained input on error, and stale results after dismiss/reopen. Include owner-busy rejection followed by retry after idle, covering immediate-close set-PIN as well as pending dialogs. Use the existing JUnit/coroutines-test/Robolectric setup. Add focused Compose interaction tests for final Done, intermediate Next, and primary-button equivalence using BOM-aligned Compose test dependencies in the local Robolectric suite. Make the affected dialog composables internally accessible or extract their existing dialog content for testing where needed; do not test a separately reimplemented dialog. There is no current `androidTest` suite or runner, so this plan does not assume one exists. If the local Compose harness cannot execute, resolving that harness is a required test task; do not silently omit coverage or change production build variants to accommodate it.

2. **Repository checks.** Run the existing CI task set from the repository root. This checkout has `gradlew` and `gradle/wrapper/gradle-wrapper.jar`, but no `gradlew.bat`; on Windows, invoke `org.gradle.wrapper.GradleWrapperMain` with the Android Studio JBR and run `:app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --console=plain`. Record failures, fix regressions introduced by this work, and distinguish any demonstrated pre-existing failure. Do not describe a failing gate as passed. The debug variant is currently minified and non-debuggable; do not assume a conventional debuggable APK or alter that configuration incidentally.

3. **Target STB check.** Record the device model, Android version, keyboard package/version, remote type, candidate commit/build variant, application ID, and APK hash. Verify the installed candidate actually contains this implementation. With the real virtual text and numeric keyboards, record pass/fail for channel Enter submission, Back/manual hide without submission, invalid-input correction, repeated Enter without duplicate side effects, and reopening. Run the PIN Next/Done and pending/error scenarios plus URL/numeric-setting confirmation. Keyboard visibility/focus must be observed; callback-only tests cannot satisfy this gate. Use a test profile/playlist and test PIN, restoring changed settings afterward.

4. **Touch check.** On one supported phone/tablet or emulator with its virtual keyboard enabled, verify Done, Next, visible buttons, rejection retry, and Back in the affected dialog types. Record API level and keyboard. Hardware Enter is additional coverage when available; label it untested when unavailable. Do not substitute hardware key injection for virtual-keyboard completion testing.

5. **Evidence.** Each required scenario has a result and reproduction steps in the verification document. Record any missing device or tool as blocked/unverified. Implementation may proceed without a device, but functional completion requires both the STB and touch gates to pass. Builds and automated tests alone are insufficient.

## Explicit environment dependencies

No STB address, selected test keyboard, device access, or candidate installation route has been established during planning. Resolve these before device verification from the active session/configuration; if absent, request the missing test target details then. They do not block source implementation or local tests. Do not infer that a production APK on the server contains the candidate changes.

The repo's `AGENTS.md` refers to `rutv-stb-deploy` for local build/launch work, but the inspected `C:/Users/anton/.codex/skills/rutv-stb-deploy/SKILL.md` explicitly limits itself to customer production provisioning and excludes local builds, debug APKs, and launch. It is not a candidate-testing procedure. Use the repository Gradle commands for builds; establish the actual authorized test-device installation/launch route for the candidate at verification time. Do not run the customer installer or rewrite customer configuration as a shortcut. This plan does not authorize a production release or customer deployment.

## Completion criteria

All scoped input dialogs use their primary action on final keyboard Done, and the channel flows require one fewer confirmation click. Per-dialog validation/closure rules and the explicit focus/pending/cancellation contract above are met. All mandatory gates pass with recorded evidence; no keyboard-hide observer can submit a dialog.

## Final plan review

Reviewed against the implemented dialog code, owning callbacks/ViewModels, view-state models, Gradle configuration, automated tests, and device evidence. The review resolved keyboard behavior after rejection, asynchronous completion and duplicate handling, stale-result ownership, the immediate-close set-PIN exception, and the actual STB installation route. No implementation step depends on keyboard visibility as a submission signal, a `Unit` callback as a success signal, an inferred device address, or an inferred operation result. Environment-dependent cases that were not exercised are named in the verification document rather than treated as passed. No hidden implementation assumption or unresolved implementation-policy decision remains.

# Dialog keyboard confirmation verification

Branch: `feature/dialog-ime-confirm`, based on `3e89f47f4b23639e9e39c8c31aa9cee1f11a5003`.
Candidate changes are uncommitted. This document records implementation evidence, not a production release.

## Implementation

- Channel number, channel search, numeric settings, URL input and parental PIN dialogs share their primary action with final keyboard Done.
- PIN forms use Next between inputs. Local validation keeps correction focus and preserves entered values.
- Dialog closing/pending guards prevent duplicate accepted submissions. ViewModels reserve PIN operations before launching their coroutines; terminal results carry session/request identity and release busy ownership on failure or cancellation.
- Settings PIN creation retains immediate dismissal after acceptance. Change/remove await matching success. Dismissed settings operations can finish without closing a reopened form. Parental prompt dismissal cancels verification and invalidates its session before a protected action can start.
- Keyboard visibility changes only move focus; they never confirm a dialog. No raw-key fallback has been added.

## Local checks

The repository contains `gradlew` and the wrapper JAR, but no `gradlew.bat`. On this Windows workstation, invoke the checked-in wrapper directly:

```powershell
& 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --console=plain
```

Compose tests use a Robolectric-created `ComponentActivity` and `createEmptyComposeRule`, avoiding changes to the application manifest or build variants. New dialog tests use API 34. Actual keyboard rendering and editor-action dispatch were checked separately on devices.

| Gate | Result |
| --- | --- |
| PIN operation ownership unit tests | Passed in debug and release suites |
| Compose dialog interaction tests | Passed in debug and release suites |
| Debug/release unit suites | Passed: 45 tests per variant, 0 failures/errors/skips |
| Debug/release lint | Passed |
| Candidate debug/release APK build | Passed |

The complete gate finished successfully in 6 minutes 45 seconds. Release output retained existing R8/default-ProGuard warnings and the repository's missing optional `app/proguard-rules.pro` warning; no task failed.

## Devices

| Target | Identified environment | Status |
| --- | --- | --- |
| Development STB | `10.100.102.10:5555`; HP4414-MECOOL / YYC, model `4K Android TV Box`, Android 14; Google Latin IME 17.3.09 TV; `com.rutv.dev` 1.1.0 (7) | Candidate installed and core channel/search/URL scenarios passed |
| Touch emulator | `Medium_Phone_API_36.1`, `emulator-5554`, API 36; Google Latin IME 15.4.8 | Candidate installed; numeric, URL, PIN, visible-button and Back scenarios passed |

Candidate debug APK SHA-256: `491AC1DFFEBB6565089D589B88C606D63E52C3AD55D41A020CEA96D45317D844`. The STB package update time changed after `adb install -r`, confirming installation of this candidate while preserving app data. STB navigation used ADB DPAD/Center as a remote-input proxy; no physical remote model was available. Virtual keyboard Done was selected inside the IME.

## Required device scenarios

| Scenario | STB | Touch |
| --- | --- | --- |
| Channel number: one final Done closes dialog and plays valid channel | Passed with numeric IME: selected channel 2, one Done closed keyboard/dialog and started channel 2; restored channel 1 afterward | Not run because emulator had no usable playlist; Compose interaction test passed |
| Search: blank rejection then matching query Done | Matching query `HD` passed with text IME and one Done; blank rejection covered by automated/touch state tests | Not run because emulator had no usable playlist; Compose interaction test passed |
| Back/manual keyboard hide does not submit; visible OK and Cancel work | Passed: Back hid numeric IME, retained channel dialog and focused OK without switching channel | Passed: Back hid numeric IME and retained dialog; tapping visible OK used the same action. Cancel equivalence passed in Compose tests |
| Reopen and repeated confirmation without duplicate side effects | Reopen passed; one Done produced one channel change. Rapid duplicate suppression is deterministic in automated tests | Reopen and retry passed; duplicate guards passed in automated tests |
| Numeric setting: invalid correction then valid Done | Not exercised on STB to avoid changing additional device settings | Passed: empty Done retained dialog/keyboard, entering the original valid value then Done closed it |
| URL final Done uses existing load action | Passed using the already configured URL; one Done closed the keyboard/dialog and invoked the existing load flow | Passed: blank Done retained input, nonblank Done invoked the existing load flow |
| PIN Next/final Done, mismatch/wrong PIN correction and matching success | Not exercised because the STB's existing PIN state/test PIN was not established | Passed with disposable PIN: Next advanced to confirmation, mismatch retained correction, final Done saved, and remove-PIN Done awaited and closed on success; PIN removed afterward |

Storage failure, cancellation races, repeated identical failures, duplicate pending submission, owner-busy retry and stale-session results are exercised deterministically in automated coverage. Hardware Enter and a named physical remote were unavailable. STB numeric/PIN coverage and touch channel/search coverage remain explicitly unverified on those targets; the same production composables are covered by Compose tests and by the complementary device target. No raw-Enter fallback was needed because both tested Google keyboards delivered their editor actions through `KeyboardActions`.

# Android EPG Description Language Refactor Plan

## Status

Implementation-ready Android plan. This document does not include further `epg_service` changes.

## Confirmed Service Contract

Every Android `POST /epg` request must include:

```json
"preferred_description_language": "en"
```

The Android client must treat this field as required even if a locally checked-out service version still declares it nullable.

The currently supported service values are exactly:

```text
en
ru
he
```

They are lowercase and case-sensitive. The app must never send `null`, an empty string, `auto`, a regional tag such as `en-US`, or a three-letter alias such as `eng` or `rus`.

The service continues to return one nullable `description` string per programme, so `EpgProgram` and the UI description rendering do not need a multilingual model.

## Goal

Add a user setting for the preferred EPG description language and include its selected, service-supported language code in every EPG network request.

Changing the preference must invalidate old-language EPG data and refresh the player without requiring a playlist source change.

## User-Facing Setting

Add **Preferred EPG description language** inside the existing **EPG Configuration** section, before **Clear EPG cache**.

Provide these choices:

| Stored and transmitted value | Display label |
|---|---|
| `en` | English |
| `ru` | Russian |
| `he` | Hebrew |

Use `ru` as the default EPG description language.

Do not add `auto` or "first available" modes. The service requires a concrete supported language, and coupling this preference to the application UI language would add behavior that was not requested.

## Preference Storage

In `PreferencesRepository`:

1. Add a DataStore string key named `epg_description_language`.
2. Expose it as `Flow<String>` with `ru` as the default.
3. Add `saveEpgDescriptionLanguage(value: String)`.
4. Accept only `en`, `ru`, or `he` when saving. Reject programmer errors rather than silently persisting an unsupported value.
5. When reading, map any unexpected stored value to `ru` so a corrupted preference cannot create an invalid service request.

This preference does not belong in SharedPreferences. Unlike the application locale, it is not required synchronously from `attachBaseContext`.

Define the supported values once, close to `PreferencesRepository`, and reuse them for preference validation and request validation. Do not introduce an enum or separate resolver unless implementation shows a concrete need.

## Settings State and UI Wiring

In `SettingsViewState`, add:

```kotlin
val epgDescriptionLanguage: String = "ru"
```

In `SettingsViewModel`:

- Observe `preferencesRepository.epgDescriptionLanguage`.
- Copy it into `SettingsViewState`.
- Add `setEpgDescriptionLanguage(value)` to save a changed value.
- Do not put network-request construction in the settings ViewModel.

In `SettingsScreen`:

- Add an `onEpgDescriptionLanguageChanged` callback.
- Add a dedicated dropdown using the existing remote-friendly exposed-menu pattern.
- Store codes, not localized labels.
- Add English and Russian resources for the setting title and the three language labels.
- Ensure D-pad focus, click, and menu dismissal behave like the existing application-language selector.

In `SettingsActivity`, pass the callback to `SettingsViewModel`:

- Call `setEpgDescriptionLanguage` only when the selected stored value changes.
- Do not recreate `SettingsActivity` for an EPG-language change.
- Do not add an activity-result flag. `MainViewModel` will observe the DataStore value directly.

## EPG Request Model and Transport

Extend `EpgRequest` with a required property:

```kotlin
@SerializedName("preferred_description_language")
val preferredDescriptionLanguage: String
```

Do not give this property a nullable type or a default value. A missing value should be a compile-time construction error inside the Android project.

Change `EpgRemoteDataSource.fetch` to receive the selected language:

```kotlin
fetch(
    url: String,
    ids: List<String>,
    from: Long,
    to: Long,
    preferredDescriptionLanguage: String
)
```

Construct every payload with that value. Keep the service value lowercase and validate it against `en`, `ru`, and `he` before executing the HTTP request. This validation is an internal invariant check, not user-input handling.

No response parsing changes are required because the service still returns a single `description` string.

## Repository Contract and Cache Correctness

The selected description is produced by the server, so cached `EpgProgram` objects are language-specific.

Add `preferredDescriptionLanguage: String` to all `EpgRepository` read methods:

```text
getWindowedProgramsForChannel
getWindowedProgramsForChannels
getCurrentProgram
getProgramsForChannel
```

Passing the language explicitly is preferred over injecting `PreferencesRepository` into `EpgRepositoryImpl`; it keeps the repository deterministic and avoids a hidden dependency on UI preferences.

In `EpgRepositoryImpl`:

1. Add the language to `WindowKey` and the batched/in-flight `Key`.
2. Key the per-channel programme cache by `(language, tvgId)` instead of only `tvgId`.
3. Pass the language to the remote fetch function.
4. Require the language for cache-only reads so callers cannot retrieve programmes selected for another language.
5. Keep `ensureFresh` responsible for URL, date, clock, and timezone invalidation; it does not need a mutable active-language field.

The existing generation check must remain in place. It prevents an old-language request from publishing its response after a preference change invalidates the repository.

Language must be part of cache identity rather than one mutable "active language." Otherwise, an older coroutine that captured the previous preference could run later and switch the whole repository back to that language. The existing cache size limits remain unchanged, so this does not introduce an unbounded multi-language cache.

## Reading the Language for Every Call Path

Before calling the EPG repository, read one value from `epgDescriptionLanguage`.

Pass the result through every existing EPG path, including:

- EPG panel initial loading.
- Past and future EPG paging.
- Visible-channel current-program preloading.
- Current-program lookup.
- Cached programme lookup used when opening the EPG.
- Archive completion and next-program lookup.
- Any background or startup EPG request.

Read it once per use-case invocation or ViewModel operation rather than repeatedly inside loops over channel IDs.

Update the injected test fetch function in `EpgRepositoryImpl` from four parameters to five so repository tests can assert which language was requested.

## Reacting to a Language Change

In `MainViewModel`, collect `epgDescriptionLanguage.distinctUntilChanged()` and remember the first emitted value without treating it as a change. On each later value:

1. Increment one thread-safe `epgLanguageGeneration` counter, following the existing atomic panel-generation pattern, to reject stale UI updates.
2. Invalidate the EPG panel session so its job and stale focus-generation updates are cancelled.
3. Cancel the visible-channel preload job.
4. Clear the repository cache. Its generation guard prevents requests already in flight from publishing afterward.
5. Clear language-specific UI data: `currentProgram`, `currentProgramsMap`, `epgPrograms`, loaded EPG bounds, and `selectedProgramDetails`.
6. Preserve playback, channel selection, archive/timeshift state, and the playlist itself.
7. Preload the active channel again.
8. Refresh visible-channel current programmes when that feature is enabled.
9. If the EPG panel was open, reload the same channel/window through the existing panel-loading path and preserve its target identity where possible.

Any asynchronous EPG operation that writes `currentProgram`, `currentProgramsMap`, or panel programmes must capture `epgLanguageGeneration` before loading and verify it before publishing. The existing panel generation remains responsible for panel channel/window changes; the language generation covers only preference changes.

Do not use a `SettingsActivity` result extra for this feature, and do not invoke `loadPlaylist` from the language-change observer. The existing general settings-return callback may continue its current playlist reload behavior; changing that broader behavior is outside this refactor.

The currently playing archive/timeshift session must not be restarted or replaced. Its already-held `EpgProgram` metadata may remain unchanged until it is loaded again; the setting affects newly fetched EPG data.

## Focused Tests

Add only tests that protect the request contract and cache behavior.

### Preference and change-handling tests

- The default is `ru`.
- A parameterized case confirms `en`, `ru`, and `he` persist unchanged.
- An unexpected stored value is read as `ru`.
- The MainViewModel ignores the initial preference emission and refreshes on a later distinct change.
- An EPG result captured under an older language generation cannot update UI state.

### Transport test

Capture one HTTP request body and verify that it always contains a concrete lowercase `preferred_description_language` value.

### Repository tests

- The selected language reaches the fetch function.
- Repeating the same URL, window, and language reuses cached/in-flight work.
- The same channel/window requested in two languages cannot share cache entries or in-flight work.
- An older-language request completing after `clearCache` cannot repopulate the cache.

Do not duplicate UI tests for description rendering because the response and `EpgProgram.description` behavior remain unchanged.

## Documentation

Update the English and Russian user manual sections to state:

- where the preferred EPG description language setting is located;
- that the service may fall back to another available description when the requested language is unavailable or empty.

Also update `README.md` and add a concise user-facing entry to `CHANGELOG.md`.

## Validation

Run the existing Android unit-test suite and a release compilation. At minimum:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleRelease
```

Then perform one device or emulator smoke test:

1. Select English and open details for a programme known to have English and Russian descriptions.
2. Select Russian, return to the player, and confirm the same programme refreshes in Russian without restarting the app or manually reloading the playlist.
3. Select Hebrew and confirm a Hebrew description is used when present and the service fallback is displayed otherwise.
4. Confirm every `/epg` request succeeds without a missing-key or unsupported-value response.

## Implementation Order

1. Add the supported-value constants, DataStore persistence, and settings state.
2. Add strings and the settings dropdown.
3. Make `EpgRequest.preferredDescriptionLanguage` required.
4. Thread the selected language through the remote data source and repository API.
5. Include language in the existing bounded EPG cache identities.
6. Update every EPG caller.
7. Add reactive language-change handling in `MainViewModel`.
8. Add the focused tests and documentation.
9. Run unit tests, release compilation, and the smoke test.

## Acceptance Criteria

- Every Android `/epg` request includes `preferred_description_language`.
- The transmitted value is always exactly `en`, `ru`, or `he`.
- The user can select English, Russian, or Hebrew in EPG settings.
- The preference survives process restarts.
- Changing the setting cannot display descriptions cached for the previous language.
- An old in-flight request cannot repopulate the cache after a language change.
- The EPG language-change handler refreshes EPG descriptions without initiating an additional playlist reload.
- Existing EPG display and archive behavior remain unchanged apart from description-language selection.

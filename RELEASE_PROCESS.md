# RuTV Commit, Changelog, Versioning, and Release Process

## Summary

RuTV uses two explicit Codex skills and two deterministic scripts:

- `rutv-commit`: commits and pushes normal changes.
- `rutv-release`: prepares and tags a release.
- GitHub Actions: validates pushes and builds/uploads release APKs from tags.

Regular commits update `CHANGELOG.md` only for user-visible changes. App version numbers change only during release.

## Version Files

- `version.properties` is the source of truth for Android app versioning.
- `VERSION_NAME` is the user-visible SemVer release, for example `1.2.0`.
- `VERSION_CODE` is Android's internal release integer and increments by `1` for each release APK.
- `app/build.gradle` reads these values during Android builds.

## Regular Commits

Use the `rutv-commit` skill only when explicitly requested.

The skill:

- inspects the git diff
- refuses unrelated dirty files unless approved
- proposes a Conventional Commit type, scope, and changelog text
- asks when intent is ambiguous
- updates `CHANGELOG.md` only for user-visible changes
- stages intended files only
- commits and pushes the current branch

Regular commits do not update `VERSION_NAME` or `VERSION_CODE`.

Changelog text must describe user-visible behavior, not implementation details.

Good:

```text
Fixed channel preview staying visible after closing the channel list.
```

Bad:

```text
Fixed stale preview controller release ordering.
```

## Changelog Script

Use `scripts/update-changelog.ps1` to add user-visible entries under `## [Unreleased]`.

Examples:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\update-changelog.ps1 `
  -Type feat `
  -Description "quick access to favorite channels from playback controls"
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\update-changelog.ps1 `
  -Type fix `
  -Description "channel preview staying visible after closing the channel list"
```

The script avoids duplicate entries.

## Release

Use the `rutv-release` skill only when explicitly requested.

The skill:

- requires the current branch to be the default branch, normally `main` or `master`
- rejects unrelated dirty changes
- validates that `CHANGELOG.md` has meaningful `Unreleased` entries unless explicitly allowed
- bumps `VERSION_NAME`
- increments `VERSION_CODE`
- moves `Unreleased` entries into `## [VERSION_NAME] - YYYY-MM-DD`
- recreates an empty `## [Unreleased]` section at the top
- creates a release commit
- tags that exact commit as `v<VERSION_NAME>`
- pushes the commit and tag to GitHub

The release skill does not build or upload the final APK locally by default. The tag-triggered GitHub Actions release workflow builds from the tag commit and uploads the APK to `hetzner2`.

## GitHub Actions

`ci.yml` runs on push to any branch and validates:

- Gradle can read `version.properties`
- `VERSION_NAME` is valid SemVer
- `VERSION_CODE` is numeric
- `CHANGELOG.md` format is valid
- latest commit message uses Conventional Commit format
- debug build and available tests pass

`release.yml` runs only on tags matching `v*.*.*` and:

- validates tag equals `VERSION_NAME`
- validates tag commit is reachable from the default branch
- validates changelog has a section for `VERSION_NAME`
- builds the release APK from the tag commit
- signs with the checked-in debug key for now
- attaches the APK to the GitHub Release
- uploads `rutv-release-<VERSION_NAME>.apk` to `hetzner2:/home/anton/playlist_service/apks/`

Required GitHub secrets:

- `HETZNER_HOST`
- `HETZNER_USER`
- `HETZNER_SSH_KEY`
- optional `HETZNER_APK_DIR`

## Signing Note

Release APKs currently use the checked-in debug key by decision. This preserves compatibility with existing debug-key installs, but it is not production-grade signing. Migrating later to a real release keystore can require uninstalling existing debug-key builds or explicitly handling signing-key migration.

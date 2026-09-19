# Changelog

All notable user-visible changes to RuTV are documented in this file.

This project follows the Keep a Changelog structure. Regular commits add entries under `Unreleased`; release preparation moves those entries into a dated version section.

## [Unreleased]

### Fixed

- Fixed custom playback controls reopening after pressing Back on a remote.

## [1.3.0] - 2026-09-19

### Added

- Added per-channel audio track selection for live and archive playback, using stream-provided labels and remembering the selected track across restarts.

## [1.2.0] - 2026-09-13

### Added

- Added temporary channel search result lists matching channel titles, stream names, and EPG tvg-id values.
- Added virtual-keyboard Enter/Done confirmation for channel search, channel-number navigation, PIN forms, and settings dialogs.
- Added automatic EPG focus on the playing archive or timeshift program, or the currently airing program during live playback.

### Changed

- Hardened playlist refresh, EPG paging/cache ownership, and playback lifecycle handling against stale concurrent work and cancellation.
- Made debug APKs use release-equivalent code and resource shrinking while retaining the separate `.dev` application identity.

### Fixed

- Preserved playlists, favorites, and aspect ratios across failed reloads, concurrent edits, and database upgrades.
- Corrected EPG boundary, timezone, empty-response, stalled-request, and overlapping page behavior.

## [1.1.0] - 2026-06-25

### Added

- Added adjustable channel and EPG panel spacing plus channel preview sizing controls.
- Added parental controls for locking channels with a PIN.
- Added landscape touchscreen controls for phones and tablets, including fullscreen tap/edge-swipe gestures and explicit playlist/EPG row actions.


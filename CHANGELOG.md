# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are
the app's `versionName`; the `versionCode` Google Play sees is derived from the commit count
(see `app/build.gradle.kts`) and is not a version.

## [Unreleased]

### Changed

- Coverage is measured by JaCoCo over the debug variant and gated at 99% line and 98% branch.
- The source now lives in its own repository, with CI on GitHub Actions.
- A build carries a Sentry DSN only when it is given one (`-PdistrictSentryDsn` or
  `DISTRICT_SENTRY_DSN`); a build from a plain checkout has crash reporting off.
- `versionCode` adds `BUILD_NUMBER_OFFSET` (default 4101) to the commit count, so builds from
  this repository's history number above every build already uploaded to the Google Play
  Console: those from the earlier source tree, and 4101, which an earlier single-commit tree
  already used.
- The iOS endpoint list used by the endpoint parity test is a vendored snapshot under
  `parity/`, overridable with `DISTRICT_IOS_ENDPOINT_IDS`.

### Fixed

- Closing a support request now shows a disabled "Closing…" button while the close is in flight,
  instead of saying the request cannot be closed from here.
- A request made while a token refresh was finishing, or right after one was rate-limited or could
  not be sent, no longer wipes the session and signs the user out.
- Hanging up while an answered call is still joining now stays hung up: the call no longer comes
  back as live, the call's foreground service is not started, and the microphone is never turned
  on for a call that has ended.
- An answered call no longer leaves its engine watchers running for the life of the process.
- A call placed while the call-back list loads, a marketplace tab or filter chosen while the owned
  numbers load, and a device revoke started while the device list loads are no longer undone when
  that list arrives (the last one allowed a second revoke).
- Workflow run history that arrives after a reload is dropped instead of being shown on the next
  expand, which after a session change could have been another account's history.

## [1.0] - 2026-09-26

The first release on Google Play.

It covers sign-in through the service's own login page (PKCE), calls and transcripts, the
shared inbox, contacts and their intelligence dossiers, multi-party rooms, the self-managed
softphone for outbound and inbound calls, push notifications, and workspace settings.

[Unreleased]: https://github.com/distronode-corporation/district-android/compare/v1.0...HEAD
[1.0]: https://github.com/distronode-corporation/district-android/releases/tag/v1.0

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are
the app's `versionName`; the `versionCode` Google Play sees is derived from the commit count
(see `app/build.gradle.kts`) and is not a version.

## [Unreleased]

### Changed

- The source now lives in its own repository, with CI on GitHub Actions.
- A build carries a Sentry DSN only when it is given one (`-PdistrictSentryDsn` or
  `DISTRICT_SENTRY_DSN`); a build from a plain checkout has crash reporting off.
- `versionCode` adds `BUILD_NUMBER_OFFSET` (default 4101) to the commit count, so builds from
  this repository's history number above every build already uploaded to the Google Play
  Console: those from the earlier source tree, and 4101, which an earlier single-commit tree
  already used.
- The iOS endpoint list used by the endpoint parity test is a vendored snapshot under
  `parity/`, overridable with `DISTRICT_IOS_ENDPOINT_IDS`.

## [1.0]

The first release, submitted to Google Play and in Google's review. The store listing is not
public yet; it goes live when the review completes, and this entry gets its date then.

It covers sign-in through the service's own login page (PKCE), calls and transcripts, the
shared inbox, contacts and their intelligence dossiers, multi-party rooms, the self-managed
softphone for outbound and inbound calls, push notifications, and workspace settings.

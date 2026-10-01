# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are
the app's `versionName`; the `versionCode` Google Play sees is derived from the commit count
(see `app/build.gradle.kts`) and is not a version.

## [Unreleased]

### Fixed

- After a change to the device's security settings, signing in keeps you signed in again,
  instead of returning you to the sign-in screen at every launch.

## [1.1] - 2026-09-28

### Fixed

- You are no longer signed out when a request arrives while your sign-in is being refreshed.
- Hanging up while a call is still connecting now ends it for good, with the microphone off.
- Closing a support request shows "Closing…" while it closes, instead of an error.
- A call, filter or device removal started while a list loads is no longer undone when the
  list arrives.
- Workflow run history from before a reload is no longer shown.

## [1.0] - 2026-09-26

The first release on Google Play.

It covers sign-in through the service's own login page (PKCE), calls and transcripts, the
shared inbox, contacts and their intelligence dossiers, multi-party rooms, the self-managed
softphone for outbound and inbound calls, push notifications, and workspace settings.

[Unreleased]: https://github.com/distronode-corporation/district-android/compare/v1.1...HEAD
[1.1]: https://github.com/distronode-corporation/district-android/compare/v1.0...v1.1
[1.0]: https://github.com/distronode-corporation/district-android/releases/tag/v1.0

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are
the app's `versionName`; the `versionCode` Google Play sees is derived from the commit count
(see `app/build.gradle.kts`) and is not a version.

## [Unreleased]

### Added

- Links to District Studio pages on distronode.com open the matching screen in the app: Persona,
  Voice, Call handling, Skills, Integrations and Knowledge. Video opens in the browser.

### Changed

- Workspace settings groups the receptionist's settings under a District Studio heading, in the
  same order and with the same names as the web: Persona, Voice, Call handling, Skills, Knowledge.

## [2.0] - 2026-10-04

### Added

- Voice Studio, in workspace settings: pick a starting point, see the signal chain from Ear to
  Voice with where each part runs and how fast the agent replies, and fine-tune any part of the
  call. Its words follow your portal language.

### Changed

- The agent persona screen keeps the name, greeting, character, language and answer length. The
  voice and engine are set in Voice Studio.

### Removed

- A call no longer offers to play a recording. Calls are not recorded; the transcript is the
  record of what was said.

## [1.2] - 2026-10-02

### Fixed

- After you miss or decline a call, the next calls still ring. Before, they could stop arriving
  until you opened the app.
- If you hang up just after answering, the call no longer connects anyway.
- Tapping a message notification opens that message, not the newest one.
- A draft's pictures are kept when you reopen the conversation.
- A credit under $1 shows its minus sign.
- After a change to the device's security settings, signing in keeps you signed in again.

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

[Unreleased]: https://github.com/distronode-corporation/district-android/compare/v2.0...HEAD
[2.0]: https://github.com/distronode-corporation/district-android/compare/v1.2...v2.0
[1.2]: https://github.com/distronode-corporation/district-android/compare/v1.1...v1.2
[1.1]: https://github.com/distronode-corporation/district-android/compare/v1.0...v1.1
[1.0]: https://github.com/distronode-corporation/district-android/releases/tag/v1.0

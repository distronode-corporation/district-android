# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are
the app's `versionName`; the `versionCode` Google Play sees is derived from the commit count
(see `app/build.gradle.kts`) and is not a version.

## [Unreleased]

## [3.1.0] - 2026-10-25

### Added

- Declining an incoming call now tells your AI attendant: once everyone the call rang has
  declined, it takes a message right away instead of keeping the caller waiting.
- Message notifications show the sender's name when the contact has one.
- Urgent messages, and messages from your VIP contacts, get their own notification channel and
  pop up on screen.

### Changed

- A voice engine your workspace cannot use yet is explained in your language.

## [3.0.3] - 2026-10-22

### Fixed

- Voicemail conversations in the Inbox are labelled Voicemail, and WhatsApp conversations
  WhatsApp, instead of an unknown value.

### Changed

- Call outcomes use the web dashboard's names: Callback requested, Passed to a person and
  Information only.
- Workflow runs say when a team alert went by email or to the app instead of a text.
- The app's components are updated, including the one that delivers notifications.

## [3.0.2] - 2026-10-16

### Fixed

- Meeting rooms offer the minutes only after a meeting ends.
- The billing month reads "Aug 2026" in English.
- An empty inbox offers New message, and an empty call log reads "No calls yet".
- In French, a call's length no longer breaks across lines.

### Changed

- Numbers in use are labelled Active.
- Delete contact and Block caller are shown in red, and Add contact has an icon.
- Workspace settings have a Workspace heading, the Email chip is no longer in capitals, and
  spacing is fixed.

## [3.0.1] - 2026-10-15

### Fixed

- Incoming calls now ring, as your sound settings allow, and respect silent mode and Do Not
  Disturb.
- On a locked phone, an incoming call opens full screen when Android allows it.
- Your devices stop ringing as soon as a call is answered elsewhere or the caller hangs up.
- Support request statuses now appear in your language.

### Security

- Opening the web dashboard from the app is now more secure.

## [3.0.0] - 2026-10-15

District AI is now in Canadian French.
- Live transcript of a call in progress
- Block a caller, and report AI-generated content
- Start a conversation from the Inbox, or send a contact a video call link
- Phone numbers: call routing, release, registrations and carriers
- Booking pages admin, now in the app
- Read a text file into Knowledge

## [2.2] - 2026-10-13

### Changed

- The Phone numbers search opens on your workspace region's country, as on the web.
- The app reads the service's new authenticator step at sign-in. How you sign in does not
  change.

### Fixed

- Fixes the plan name on the Billing page, which showed the plan's internal key (for example
  "VoicePro") instead of its name.
- When your device's notification token changes while you are signed out, the app registers it
  when you sign in instead of reporting an error.

## [2.1] - 2026-10-05

### Added

- District Studio links on distronode.com open the matching screen in the app: Persona, Voice,
  Call handling, Skills, Integrations and Knowledge. Video opens in the browser.

### Changed

- Workspace settings groups the receptionist's settings under District Studio, in the web's
  order and names: Persona, Voice, Call handling, Skills, Knowledge.
- Words from the service follow District Studio, such as the Voice heading, and District Studio
  → Integrations when contact enrichment is off.

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

[Unreleased]: https://github.com/distronode-corporation/district-android/compare/v3.1.0...HEAD
[3.1.0]: https://github.com/distronode-corporation/district-android/compare/v3.0.3...v3.1.0
[3.0.3]: https://github.com/distronode-corporation/district-android/compare/v3.0.2...v3.0.3
[3.0.2]: https://github.com/distronode-corporation/district-android/compare/v3.0.1...v3.0.2
[3.0.1]: https://github.com/distronode-corporation/district-android/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/distronode-corporation/district-android/compare/v2.2...v3.0.0
[2.2]: https://github.com/distronode-corporation/district-android/compare/v2.1...v2.2
[2.1]: https://github.com/distronode-corporation/district-android/compare/v2.0...v2.1
[2.0]: https://github.com/distronode-corporation/district-android/compare/v1.2...v2.0
[1.2]: https://github.com/distronode-corporation/district-android/compare/v1.1...v1.2
[1.1]: https://github.com/distronode-corporation/district-android/compare/v1.0...v1.1
[1.0]: https://github.com/distronode-corporation/district-android/releases/tag/v1.0

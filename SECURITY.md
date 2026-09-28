# Security Policy

## Reporting a vulnerability

Please report privately, not in a public issue.

- **Preferred:** GitHub's private vulnerability reporting. Open the repository's
  **Security** tab and choose **Report a vulnerability**, or go straight to
  <https://github.com/distronode-corporation/district-android/security/advisories/new>.
- **Fallback:** email **opensource@distronode.com** if you cannot use GitHub.

Include what you did, what happened, and what you expected, with the app version and the
device and Android version. A proof of concept is welcome but not required. Never include a
real access token, and never include data from a real workspace; if one is part of the
problem, say where it appeared, not what it was.

Expect an acknowledgement within a few working days. There is no paid bug bounty; what you
get is credit in the changelog entry for the fix, if you want it.

A vulnerability in the District AI service itself (the API, the web app) is in scope for a
report through the same channels, even though the server code is not in this repository.

## Supported versions

Only the current Google Play release is supported. Fixes ship in a new release rather than
being backported.

| Version | Supported |
| --- | --- |
| 1.0 (current Google Play release) | Yes |
| Anything older | No |

## Security model

What the app does to protect its users, so a report can say which of these it breaks.

### Sign-in never happens inside the app

The app collects no password. Sign-in opens the service's own login page in a Custom Tab and
comes back to the app with an authorization code over `districtai://auth`, exchanged with PKCE
(`core/core-auth`, `Pkce.kt` and `PkceLoginFlow.kt`). The consent step on the server needs an
explicit press, so a web page cannot silently mint a code for the app.

### Tokens are encrypted at rest with a Keystore key

Access and refresh tokens are encrypted with an AES-GCM key that is generated in, and never
leaves, the Android Keystore (`KeystoreCipher`, `KeystoreTokenStore`). Every read fails
closed: a key invalidated by a device-security change, or a value that will not decrypt, reads
as "signed out", never as a partial session. `android:allowBackup="false"` keeps the
ciphertext out of device backups. Refresh-token rotation is handled by
`TokenRefreshCoordinator` so the same refresh token is never presented twice, because the
server revokes the whole token family on a replay.

### The permission surface is small on purpose

No SMS, contacts, call-log or location permissions: the inbox and contact importers run on the
server. Calling uses `MANAGE_OWN_CALLS` (a self-managed Telecom account), not `CALL_PHONE`.
Microphone and camera are runtime requests made where a room, a call or a voice preview needs
them, not at launch. The manifest explains each permission beside its declaration.

### Push payloads carry identifiers only

A notification's payload holds ids, never message content or a phone number, because the OS
can read it. The app spends one authenticated request to turn an id into what it shows.

### Crash reporting is opt-in per build and strips personal data

A build made from a plain checkout of this repository reports no crashes: Sentry is enabled
only when the build is given a DSN, which the release workflow does for Distronode's own
releases. When it is, `sendDefaultPii` is off and tracing and profiling are disabled
(`DistrictSentry.kt`, both pinned by `DistrictSentryTest`), and neither the session-replay nor
the NDK module is a dependency.

### Releases are built in CI with borrowed credentials

Releases are built, signed and uploaded to Google Play by `.github/workflows/release.yml`, from
a protected `v*` tag or a dispatch on `main`. No signing key or store credential is stored in
this repository or in GitHub: the jobs run in a `release` environment that only `main` and `v*`
tags can use, and borrow the upload key, the Play publishing credential and the Sentry upload
token from Distronode's Google Cloud for the length of one run, through workload identity
federation pinned to this repository, that environment and those refs. The workflows have no
`pull_request` trigger, so no pull request, from a fork or otherwise, can reach them.
Submission for store review runs only with the maintainers' explicit approval. Google Play App
Signing holds the app signing key; the key borrowed here is the upload key.

Versions up to and including 1.0 were built and signed on a maintainer's machine before this
workflow existed.

## Scope

In scope, in rough order of damage:

- A token, or data from a workspace, reaching a log line, a crash report, a notification, a
  backup, or another app (through an exported component, an intent, a content URI or the
  clipboard).
- A way to make the app send a token to a host other than the service's.
- A deep link or app link that performs an action, or discloses data, without the signed-in
  user's intent.
- Anything that lets one workspace's data appear in another's view on the same device.
- Weaknesses in the token storage, the refresh coordinator, or the PKCE flow.
- A committed secret. See the next section for the values that look like one and are not.

Out of scope:

- An attacker who already has root on the device, or an unlocked device in hand.
- Findings that need a modified build of the app (anyone can build and sign their own).
- Missing hardening with no demonstrated impact (for example the absence of certificate
  pinning, which the app does not do).
- Rate limits and account policy on the service, unless they can be bypassed from the client.

## Values that look like secrets and are not

Secret scanners flag these. Each is deliberate:

- **Firebase client configuration** in `app/src/main/kotlin/com/distronode/districtai/push/DistrictFirebase.kt`
  (`API_KEY`, the sender id and the app ids). It ships in every APK, identifies the Firebase
  project to Google's client SDKs and grants nothing by itself. Sending a push needs a service
  account that only the server holds.
- **LiveKit end-to-end-encryption keys** in three contract fixtures
  (`contracts/district-room-token.json`, `district-room-token-viewer.json`,
  `district-persona-preview-token.json`) and in the unit tests that use them
  (`ActiveRoomViewModelTest`, `PersonaPreviewViewModelTest`, `PersonaOptionsRepositoryTest`,
  `PersonaOptionsContractFixtureTest`, `CallEngineModelsTest`, `LiveKitCallEngineTest`). They
  are produced by the server's test suite from fixed test inputs, are reproducible from those
  inputs, and protect no room.

`.gitleaks.toml` allowlists exactly these values, by file and by value, so a real key added
next to them is still reported. The Secret scan workflow (`.github/workflows/gitleaks.yml`)
runs gitleaks with that configuration over the whole history on every push to `main` and every
pull request.

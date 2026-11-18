# Security model

District AI for Android holds a District AI session and shows a workspace's calls, messages and
contacts, and it rings and joins calls through Android's Telecom framework. This document says
what it protects, who it defends against, what it trusts, and how the code holds each line. The
control-by-control detail (sign-in, token storage, permissions, push, crash reports, releases)
is in the "Security model" section of [SECURITY.md](SECURITY.md#security-model), and how the
modules fit together is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Assets

- **The session**: the access and refresh tokens, encrypted on the device with a key that lives
  in the Android Keystore.
- **Customer data**: callers' numbers, transcripts, messages and contacts, which belong to the
  workspace and its customers, and the saved copies of the first screens kept for offline
  reading.
- **Billed and destructive actions**: placing a call, answering one, deleting a contact or the
  account.
- **The microphone and camera**, and a call's media.
- **The release**: the upload key and the store credential that put a build on Google Play.

## Threat model

| Actor | What they can do | What stops them |
|---|---|---|
| Another app on the device | Fire an intent or a `districtai://` link at the exported activity, or try to finish a sign-in it did not start. | PKCE with a fresh verifier and `state` per attempt; a callback with the wrong or no `state`, or with no attempt in flight, is refused before the server is contacted; app links resolve only the claimed hosts and paths. |
| Anyone who can read notifications or the push channel | Read a notification's payload. | Push payloads carry ids only; a message notification carries no content; the app spends one authenticated request to show more. |
| A thief of a refresh token | Present it to the service directly. | Not an app control: the service revokes the whole token family when a refresh token is presented twice ([SECURITY.md](SECURITY.md#tokens-are-encrypted-at-rest-with-a-keystore-key)), which this document assumes. The app's part is never to present one twice itself (next row). |
| A crash or a race mid-refresh | Make the app present a spent token, which the service would treat as theft. | Refreshes one at a time; the rotated token is saved before its access token is used; a restart that finds an interrupted refresh signs in again rather than replay. |
| Someone with the device's storage or a backup, but not the unlocked device | Read the app's files. | Tokens are AES-GCM ciphertext under a Keystore key that never leaves it; a value that will not decrypt reads as signed out; `allowBackup="false"`. |
| A second account signed in on the same device | See the first account's data. | Saved copies are bound to the account named in the token and forgotten when another account signs in; every sign-out clears them. |
| A hostile or compromised server response | Return any redirect, image or shape. | Strict decoding with unknown keys rejected; a redirect the app asks for is returned, never fetched, and refused unless it is `https`; an image that is not one is no image. |
| Someone who reads crash reports | Read whatever the app sent. | Crash reporting is off in any build without a DSN; when on, personal data is never sent and tracing and profiling are disabled. |
| Someone who wants to ship a modified release | Sign and upload a build as Distronode. | No signing key or store credential is in this repository or in GitHub; release jobs borrow them for one run through workload identity federation pinned to this repository, a `release` environment and `main` and `v*` refs. |

Out of scope, as [SECURITY.md](SECURITY.md#scope) lists: an attacker with root or an unlocked
device in hand, a modified build, and missing hardening with no demonstrated impact.

## Trust boundaries

| Party | Trusted? | Why |
|---|---|---|
| The person holding the unlocked device | **Yes** | Every billed or destructive action waits for their tap. |
| The Android Keystore and the OS | **Yes** | They hold the token key and enforce app sandboxing. |
| The District AI service | Yes, for what it answers about the account | It decides roles and data; the app still decodes strictly and never follows a redirect it asked to see. |
| Other apps on the device | **No** | They can fire intents at exported components, so callbacks need PKCE and `state` and links are matched by host and path. |
| Push messages | **No**, beyond the ids they carry | Payloads are parsed to ids only; anything shown is fetched under the app's own session. |
| Customers, callers and model output | **No** | Shown as text. |
| The network | **No** | The service's address is `https`, verified by the platform's TLS. |

## Secure design principles applied

- **Least privilege.** No SMS, contacts, call-log or location permissions; calling uses
  `MANAGE_OWN_CALLS`, not `CALL_PHONE`; microphone and camera are asked for where a call or a
  room needs them, not at launch (`app/src/main/AndroidManifest.xml` explains each permission
  beside its declaration). The release job that can write to this repository holds no Google
  Cloud credential.
- **Fail closed.** A token read that fails is "signed out", never a partial session; a write that
  cannot be encrypted stores nothing; a callback with the wrong `state` is refused before
  anything in it is used (`core/core-auth`).
- **Complete mediation.** Every API call goes through one client and one refresh coordinator
  (`DistrictApiClient`, `TokenRefreshCoordinator`), and every response through the same strict
  decoder and error mapping (`ApiResult`).
- **Economy of mechanism.** One small typed HTTP client over OkHttp, no DI framework, LiveKit
  hidden behind one interface (`CallEngine`) so no other module can name its types.
- **Safe defaults.** A build from a plain checkout reports no crashes; personal data is off in
  crash reports; the debug build installs under its own id beside a release.
- **Separation of secrets.** The tokens are only in Keystore-encrypted storage; release
  credentials are only borrowed by the release jobs; the values in this repository that look
  like secrets and are not are listed in [SECURITY.md](SECURITY.md) and allowlisted by value.

## Assurance case

Each control above is held by a test CI runs on every pull request
([.github/workflows/ci.yml](.github/workflows/ci.yml)), or, where no test can hold it, says so.
Weaknesses are mapped to the CWE Top 25 and the OWASP Top 10 (Mobile where it fits).

| Weakness | How it is countered | Tests |
|---|---|---|
| Authentication bypass by a forged or replayed callback (CWE-287, CWE-294) | OAuth 2.0 with PKCE; a fresh challenge and `state` per attempt; one callback per attempt. | `core/core-auth/.../NativeLoginFlowTest.kt` (`a mismatched state is refused WITHOUT contacting the server`, `a second callback cannot reuse the first attempt's verifier`), `PkceVectorTest.kt` (`includes the RFC 7636 appendix B reference vector`, `each attempt gets fresh secrets`) |
| Replay of a spent refresh token (CWE-294) | Serialised refresh, rotated token saved first, sign in again after a crash mid-refresh. | `core/core-auth/.../TokenRefreshCoordinatorTest.kt` (`concurrent callers trigger exactly one refresh`, `process death mid-refresh re-authenticates instead of replaying the token`, `a long sequence of refreshes never presents the same token twice`) |
| Cleartext storage of credentials (CWE-312, CWE-922) | AES-GCM under a Keystore key; a failed write stores nothing. | `core/core-auth/.../KeystoreTokenStoreTest.kt` (`a failed write leaves nothing readable rather than storing plaintext`, `reads null rather than throwing when the keystore is unavailable`), `KeystoreRoundTripTest.kt` |
| Exposure of customer data through notifications (CWE-200, CWE-359) | Ids only in the payload; no content in a message notification. | `app/.../push/PushPayloadTest.kt`, `app/.../push/PushNotifierTest.kt` (`a message notification carries no content, only a way to open the app`) |
| One account's data shown to another (CWE-200) | Saved copies bound to the token's account and forgotten on another sign-in. | `core/core-network/.../SavedReadsAccountsTest.kt` (`a sign-in as another account, offline at once, shows nothing of the old`), `SavedReadsTest.kt` (`an answer from the service is never covered over`) |
| Opening untrusted links (CWE-601, CWE-939) | App links resolve only the claimed hosts and exact segments; the login callback is never handed on. | `app/.../applinks/AppLinkResolverTest.kt` (`a lookalike host is ignored`, `a prefix match is not a startsWith`, `the login callback is ignored, not handed to a browser`) |
| Following a server redirect to another place (CWE-601) | A redirect the app asks for is returned, not fetched, and refused unless `https`. | `core/core-network/.../RedirectTargetTest.kt` (`never fetches the redirect target`, `a Location that is not an https address is refused, and never quoted`) |
| Personal data in crash reports (CWE-532, OWASP A09) | No DSN, no reporting; `sendDefaultPii` off; tracing and profiling disabled. | `app/.../DistrictSentryTest.kt` (`personally identifiable information is never sent by default`), `DistrictSentryGuardTest.kt` (`a blank dsn arms nothing`) |
| A server shape the client does not expect (CWE-20) | Strict decoding of server-generated fixtures, unknown keys rejected. | `core/core-model/.../ContractManifestTest.kt` and the contract fixture tests beside it |
| Committed secrets (CWE-798) | gitleaks over the whole history on every push to `main` and every pull request, with an allowlist by file and value. | `.github/workflows/gitleaks.yml`, `.gitleaks.toml` |
| Vulnerable and outdated components (OWASP A06) | Locked dependencies, restricted repositories, Dependabot, CodeQL, dependency submission, zizmor over the workflows, a checksum-pinned Gradle wrapper. | `.github/workflows/ci.yml`, `codeql.yml`, `dependency-submission.yml`, `.github/dependabot.yml`, `settings.gradle.kts` |
| Release credential theft (CWE-522) | No stored credential; per-run borrowing pinned to repository, environment and refs; no `pull_request` trigger on the release workflows. | Mechanism only: `.github/workflows/release.yml` and `submit.yml`, tested in part by `scripts/release-scripts.test.sh` |
| Improper certificate validation (CWE-295) | The platform's TLS with its default verification; the app does not pin certificates (out of scope in [SECURITY.md](SECURITY.md#scope)). | Mechanism only: no code path builds a client that skips verification. |

## How the project is run

Who decides, who can merge and release, and how the project continues are in the
organization's
[GOVERNANCE.md](https://github.com/distronode-corporation/.github/blob/main/GOVERNANCE.md).

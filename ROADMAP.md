# Roadmap

The near-term plan for District AI for Android. It is taken from this repository's one open
issue, its open pull requests and its own unfinished items, each named with where it comes
from. It has no dates: an item is done when the change that does it is merged and released, and
it then leaves this list for [CHANGELOG.md](CHANGELOG.md). Anything here can change; open an
issue to discuss an item or to propose a new one.

## Near term

- **Release what is merged.** Two-step verification, District HQ by voice, account deletion in
  the app and the setup wizard in the app are merged and listed under `[Unreleased]` in
  [CHANGELOG.md](CHANGELOG.md). The plan is to ship them in the next Google Play release. The
  read-only offline copies of the first screens are merged too
  ([#159](https://github.com/distronode-corporation/district-android/pull/159)), though not yet
  listed in that section.
- **Hardening from the deep security scan.** Two open pull requests: answering a call only from
  the ringing notification
  ([#161](https://github.com/distronode-corporation/district-android/pull/161)), and keeping a
  sign-out final while a token refresh is in flight, with saved copies bound to their account
  ([#162](https://github.com/distronode-corporation/district-android/pull/162)).
- **The branch coverage floor back to 98.** It was lowered to 97 because JaCoCo does not yet
  filter a branch the Compose 2.4 compiler emits; it goes back up when a JaCoCo release filters
  it ([#107](https://github.com/distronode-corporation/district-android/issues/107)).

## Not planned

- **The two endpoints the iOS app calls and this one does not** (the account settings read and
  the notification extension's read token). Both are written down as ones this client has no
  use for, not ones it has yet to build (`MISSING_ON_ANDROID` in
  `core/core-network/src/test/kotlin/com/distronode/districtai/core/network/EndpointParity.kt`).

## Open questions

- **Using the app without a District AI account.** Today the app needs a District AI account to
  sign in. Whether it can work without one, and what that would look like, is an open question
  we are asking about before building anything (README, "Without an account with us";
  [the discussion](https://github.com/distronode-corporation/.github/discussions/1)).

## How the project is run

Who decides what goes on this list, and how, is in the organization's
[GOVERNANCE.md](https://github.com/distronode-corporation/.github/blob/main/GOVERNANCE.md).

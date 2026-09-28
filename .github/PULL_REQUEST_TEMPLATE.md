<!-- Thanks for the contribution. Delete any section that genuinely does not apply. -->

## What changed

<!-- One or two sentences. The diff says what; this says it in words. -->

## Why

<!-- The problem, not the patch. If it fixes an issue, link it (Fixes #123). If you hit
     it on a device rather than reading the code, say what you saw. -->

## How it was tested

<!-- Commands you actually ran, and what they said. "Should work" is not a test.
     The first four are exactly what CI runs, in the order it runs them. -->

- [ ] `./gradlew detekt lintDebug testDebugUnitTest assembleDebug`
- [ ] `./gradlew :koverLogUnit :koverVerifyUnit` (the coverage floors only go up)
- [ ] `scripts/verify-release-minification.sh`, if it touches models, serialization or
      `app/proguard-rules.pro`
- [ ] Changes a version in `gradle/libs.versions.toml`, and so the lockfiles were regenerated
      and are part of this pull request
- [ ] Changes a screen, and so it was checked on a device or an emulator (say which, and the
      Android version)

## API and contract changes

<!-- Only if you touched a type in core-model or an API interface in core-network. -->

- [ ] No fixture in `contracts/` was edited by hand
- [ ] A new or removed endpoint is reflected in `EndpointParity.kt`, or the parity test
      still passes without it

## Anything a reviewer should know

<!-- A decision you were unsure about, something you deliberately left out, a follow-up
     you think is needed. Saying "I could not test X" here is useful, not a problem. -->

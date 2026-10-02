#!/usr/bin/env bash
# Boot the emulator, install the APK, run the Maestro smoke flow, tear the emulator down.
#
# Usage, from anywhere in the repository:
#   scripts/smoke-emulator.sh [--build] [--apk PATH] [--avd NAME]
#
# A local lane: CI does not run it. It needs an Android emulator image and an AVD, and
# Maestro (https://maestro.mobile.dev) on PATH or at $MAESTRO_BIN.
#
#   --build      run `./gradlew :app:assembleDebug` first
#   --apk PATH   install this APK instead of the default debug output
#   --avd NAME   boot this AVD instead of $SMOKE_AVD / canary-pixel8
#
# Credentials come from the environment and are never written to disk:
#   DISTRICT_SMOKE_EMAIL / DISTRICT_SMOKE_PASSWORD   the seeded UI-test account
#       (appreview@, workspace "District Review"), NOT the store review demo account
#   DISTRICT_SMOKE_CONTACT   a seeded contact's display name, default Amara Osei
#   DISTRICT_SMOKE_PLAN      the raw tier the billing card prints, default VoicePro
# With the first two unset the flow runs its launch leg only and still produces a
# screenshot; that is a deliberate mode, not a degraded one.
#
# ⛔ EXITS WITH MAESTRO'S STATUS, AND THE TEARDOWN MUST NOT EAT IT. The emulator is
# killed from an EXIT trap so it dies on every path including a failed assertion, but a
# trap that runs `adb emu kill` as its last command would hand the shell THAT command's
# status. The maestro status is captured into a variable and re-raised explicitly.
set -euo pipefail

# ⚠️ Resolve to the repository root regardless of the caller's cwd, so the relative
# paths to .maestro/ and build/ below work wherever the script is started from.
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

AVD="${SMOKE_AVD:-canary-pixel8}"
APK="app/build/outputs/apk/debug/app-debug.apk"
DO_BUILD=0
# ⛔ THE FLOW'S appId IS THE SOURCE OF TRUTH AND IS CHECKED, NOT ASSUMED. See the long
# note in .maestro/smoke.yaml: the debug build carries `applicationIdSuffix = ".debug"`,
# so a flow naming `com.distronode.districtai` launches nothing and fails as a missing
# ELEMENT. Reading it back out of the YAML means the two cannot drift silently.
FLOW="$ROOT/.maestro/smoke.yaml"
OUT="$ROOT/build/smoke"

while [ $# -gt 0 ]; do
  case "$1" in
    --build) DO_BUILD=1; shift ;;
    --apk) APK="$2"; shift 2 ;;
    --avd) AVD="$2"; shift 2 ;;
    *) echo "FATAL: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# ── Tooling ─────────────────────────────────────────────────────────────────────
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
MAESTRO="${MAESTRO_BIN:-$HOME/.maestro/bin/maestro}"

for tool in "$ADB" "$EMULATOR" "$MAESTRO"; do
  # ⚠️ `command -v` rather than `-x`: MAESTRO_BIN may legitimately be a bare name on
  # PATH in a CI image, where a path test would reject a perfectly good binary.
  if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
    echo "FATAL: $tool not found or not executable" >&2
    exit 1
  fi
done

if [ "$DO_BUILD" -eq 1 ]; then
  echo "== building the debug APK =="
  ./gradlew --no-daemon :app:assembleDebug
fi

[ -f "$APK" ] || { echo "FATAL: APK not found at $APK (pass --build or --apk)" >&2; exit 1; }

# ── The appId guard ─────────────────────────────────────────────────────────────
# ⛔ A MISMATCH HERE IS THE FAILURE THIS SCRIPT EXISTS TO MAKE LOUD. Compare the package
# the APK actually declares against the id the flow will launch, and refuse before
# spending four minutes on an emulator boot to discover it.
# ⚠️ sed quits at the first appId line itself instead of feeding `head -1`: under pipefail a
# reader that closes the pipe early SIGPIPEs the writer, and the failed assignment would stop
# the script under `set -e`. `tr` reads to the end, so it cannot do that.
FLOW_APP_ID="$(sed -n '/^appId:/{s/^appId:[[:space:]]*//p;q;}' "$FLOW" | tr -d '\r')"
[ -n "$FLOW_APP_ID" ] || { echo "FATAL: no appId in $FLOW" >&2; exit 1; }

# ⚠️ aapt lives under a versioned build-tools directory and is absent from some CI
# images, so its absence downgrades to a warning rather than failing the run. The guard
# is worth having when it can run and is not worth blocking on when it cannot.
AAPT="$(ls -1 "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$AAPT" ]; then
  APK_APP_ID="$("$AAPT" dump packagename "$APK" 2>/dev/null | tr -d '\r' || true)"
  if [ -n "$APK_APP_ID" ] && [ "$APK_APP_ID" != "$FLOW_APP_ID" ]; then
    echo "FATAL: the APK declares '$APK_APP_ID' but $FLOW launches '$FLOW_APP_ID'." >&2
    echo "       Nothing would be launched and every assertion would fail as a" >&2
    echo "       missing element. Fix the appId in the flow, or install the other APK." >&2
    exit 1
  fi
  echo "== appId agrees: $FLOW_APP_ID =="
else
  echo "WARNING: aapt2 not found; skipping the appId guard"
fi

# ── Emulator ────────────────────────────────────────────────────────────────────
SERIAL="emulator-${SMOKE_EMULATOR_PORT:-5554}"
STARTED_EMULATOR=0
DEBUG_DIR=""

cleanup() {
  # Maestro's debug tree holds the -e values (see the ⛔ before the maestro call).
  if [ -n "$DEBUG_DIR" ]; then rm -rf "$DEBUG_DIR"; fi
  if [ "$STARTED_EMULATOR" -eq 1 ]; then
    echo "== stopping $SERIAL =="
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    # Give qemu a moment to release the AVD lock, so a second run straight after this one does
    # not fail with "another emulator instance is running".
    sleep 3
  fi
  return 0
}
trap cleanup EXIT

# ⛔ CREATED BEFORE THE EMULATOR, WHOSE LOG REDIRECT BELOW WRITES INTO build/. On a clean
# checkout build/ does not exist, so the redirect failed, the emulator never started, and
# the then-unbounded wait-for-device below hung until something outside killed it.
# `mkdir -p "$OUT"` used to run AFTER the launch.
mkdir -p "$OUT"
# Last run's screenshots would otherwise mix with this run's and pass for its evidence.
rm -f "$OUT"/*.png "$OUT"/report.xml

if "$ADB" devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
  echo "== reusing the already-running $SERIAL =="
else
  "$EMULATOR" -avd "$AVD" \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
    -port "${SMOKE_EMULATOR_PORT:-5554}" \
    > "$ROOT/build/emulator.log" 2>&1 &
  STARTED_EMULATOR=1
  echo "== booting $AVD headless as $SERIAL =="
fi

# ⛔ `wait-for-device` RETURNS AS SOON AS adbd ANSWERS, WHICH IS LONG BEFORE THE UI
# EXISTS. Installing at that point fails with a package-manager error that reads like a
# broken APK. `sys.boot_completed` is the real signal.
# ⛔ AND IT NEVER RETURNS AT ALL IF THE EMULATOR DIED, so it is bounded: a dead emulator
# fails the run in minutes with the log pointed at, instead of hanging.
timeout "${SMOKE_DEVICE_WAIT_SECONDS:-300}" "$ADB" -s "$SERIAL" wait-for-device || {
  echo "FATAL: $SERIAL never came up; see build/emulator.log" >&2
  tail -20 "$ROOT/build/emulator.log" >&2 2>/dev/null || true
  exit 1
}
BOOTED=0
for _ in $(seq 1 90); do
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1
    break
  fi
  sleep 5
done
[ "$BOOTED" -eq 1 ] || { echo "FATAL: $AVD did not finish booting within 450s" >&2; exit 1; }
echo "== $SERIAL booted (API $("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')) =="

# ⛔ `sys.boot_completed` IS NOT "READY", AND BELIEVING IT COST A RED RUN ON A HEALTHY
# APP. On a COLD boot the property flips while SystemUI is still starting, and under
# software rendering SystemUI then misses its watchdog and Android raises
# **"System UI isn't responding" / Close app / Wait**, a system dialog that takes focus
# and covers the app. Measured: the very first cold-boot run of this script failed with
# `Assertion is false: "district-sign-in-root" is visible`, and the failure screenshot
# shows the sign-in card rendered correctly BEHIND that dialog. So the app was fine, the
# emulator was not, and the flow blamed the app. Every warm run had passed because the
# dialog only appears on a cold boot.
#
# ⚠️ THE SIGNAL IS THE LAUNCHER TAKING FOCUS, NOT A FIXED SLEEP. A sleep long enough to
# be safe on the slowest runner is wasted on every fast one, and a sleep tuned to a fast
# one is a flake. Focus landing on the launcher means SystemUI is actually serving.
echo "== waiting for the device to settle =="
SETTLED=0
for _ in $(seq 1 60); do
  ANIM="$("$ADB" -s "$SERIAL" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')"
  FOCUS="$("$ADB" -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
  case "$FOCUS" in
    *"isn't responding"*|*"Application Not Responding"*)
      # ⚠️ DISMISS IT RATHER THAN WAITING IT OUT. An ANR dialog does not time out on its
      # own; "Wait" is the non-destructive answer and it is the default-focused button.
      "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_ENTER >/dev/null 2>&1 || true
      ;;
  esac
  # ⚠️ EMPTY COUNTS AS DONE. This script boots with -no-boot-anim, so the bootanim service
  # never starts and its property stays unset; requiring "stopped" made this loop wait the
  # full 300s on every run and fall through (found by the fail-loud check below on API 37).
  if { [ "$ANIM" = "stopped" ] || [ -z "$ANIM" ]; } && printf '%s' "$FOCUS" | grep -q 'launcher'; then
    echo "== launcher has focus; device is serving =="
    SETTLED=1
    break
  fi
  sleep 5
done
# ⛔ FAIL HERE, NOT IN MAESTRO. Falling through on an unsettled device runs the flows against
# a dialog or a blank screen, and they fail as missing elements: a red run that blames the app
# for the emulator's state, which is the exact misreading the wait above exists to prevent.
[ "$SETTLED" -eq 1 ] || {
  echo "FATAL: the launcher never took focus within 300s (last focus: ${FOCUS:-none}, bootanim: ${ANIM:-unknown})" >&2
  exit 1
}

# A short final settle. The launcher having focus means SystemUI recovered; it does not
# mean the package manager has finished the work a cold boot queues behind it.
sleep "${SMOKE_SETTLE_SECONDS:-10}"

echo "== installing $APK =="
"$ADB" -s "$SERIAL" install -r "$APK"

# ⛔ SETTLE AFTER THE INSTALL, BECAUSE MAESTRO'S FIRST CALL CAN LAND ON AN OFFLINE
# DEVICE. Measured: a run started immediately after `install -r` died in 2s
# with `io.grpc.StatusRuntimeException: UNAVAILABLE` / `device offline`, and the very
# same command passed on a retry with the emulator untouched and `adb devices` reporting
# `device` throughout. Maestro pushes and starts its own instrumentation driver on first
# contact, and that races a package-manager still settling from the install. The failure
# is reported as a FAILED flow, not as an infrastructure error, so without this it reads
# like a broken app.
timeout "${SMOKE_DEVICE_WAIT_SECONDS:-300}" "$ADB" -s "$SERIAL" wait-for-device || {
  echo "FATAL: $SERIAL went away after the install; see build/emulator.log" >&2
  exit 1
}
sleep 5

# ── The flow ────────────────────────────────────────────────────────────────────
if [ -n "${DISTRICT_SMOKE_EMAIL:-}" ] && [ -n "${DISTRICT_SMOKE_PASSWORD:-}" ]; then
  echo "== credentials present: running the full authenticated tour =="
else
  echo "== DISTRICT_SMOKE_EMAIL / DISTRICT_SMOKE_PASSWORD unset =="
  echo "== running the LAUNCH LEG ONLY; the authenticated tour is SKIPPED =="
fi

# ⛔ `takeScreenshot: 01-sign-in` DOES NOT WRITE TO THE CWD, AND THAT COST A RUN TO
# LEARN. Maestro treats the name as a path UNDER its debug-artifact tree, so a flow
# asking for `build/smoke/01-sign-in` produced
# `~/.maestro/tests/<timestamp>/<flow name>/takeScreenshot/build/smoke/01-sign-in.png`
# and nothing at all under the repository, a green run with no screenshots, which
# is the quietest possible way to lose the evidence. `--debug-output` moves that tree
# here and `--flatten-debug-output` drops the per-run timestamp directory, which is
# exactly what its own help text recommends for CI.
#
# ⛔ AND THE DEBUG TREE GOES TO A PRIVATE TEMP DIRECTORY THAT IS DELETED, BECAUSE MAESTRO
# WRITES THE -e VALUES INTO IT. commands.json records every variable, maestro.log records
# each inputText, and the screen-hierarchy dumps record what was typed: the first
# authenticated run (2026-10-02) left the review account's password in all three under
# build/smoke. Only the screenshots and the JUnit report are copied out of it.
cd "$ROOT"
DEBUG_DIR="$(mktemp -d)"
set +e
"$MAESTRO" test \
  --format junit \
  --output "$OUT/report.xml" \
  --debug-output "$DEBUG_DIR" \
  --flatten-debug-output \
  -e DISTRICT_SMOKE_EMAIL="${DISTRICT_SMOKE_EMAIL:-}" \
  -e DISTRICT_SMOKE_PASSWORD="${DISTRICT_SMOKE_PASSWORD:-}" \
  -e DISTRICT_SMOKE_CONTACT="${DISTRICT_SMOKE_CONTACT:-Amara Osei}" \
  -e DISTRICT_SMOKE_PLAN="${DISTRICT_SMOKE_PLAN:-VoicePro}" \
  "$FLOW"
STATUS=$?
set -e

find "$DEBUG_DIR" -name '*.png' -exec cp {} "$OUT/" \; 2>/dev/null || true
rm -rf "$DEBUG_DIR"
echo "== maestro exited $STATUS; report at $OUT/report.xml =="
# ⚠️ COUNT THE SCREENSHOTS AND SAY SO. A flow can pass while writing none of them (see
# the ⛔ above), and "0 screenshots" beside a green report is the only visible sign.
SHOTS="$(find "$OUT" -name '*.png' 2>/dev/null | wc -l | tr -d ' ')"
echo "== screenshots captured: $SHOTS =="
find "$OUT" -name '*.png' 2>/dev/null | sort || true

exit "$STATUS"

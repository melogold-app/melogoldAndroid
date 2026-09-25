#!/bin/sh
# One session on the shared emulator (REWRITE §6.0.7):
#   scripts/agent/emu.sh <scenario.sh> [args...]
# 1. takes the emulator lock; 2. waits for sys.boot_completed; 3. resets the device (font 1.0,
# light mode, display size and density, rotation 0) and uninstalls the debug app; 4. runs the
# scenario; 5. resets the device again, also when the scenario fails.
#
# Build the APK before calling: the scenario only installs it (adb install -r …/app-debug.apk).
# The scenario gets adb on PATH, ANDROID_SERIAL set and $ADB = "adb -s <serial>"; it may call
# scripts/agent/shoot.sh, which then reuses this session's lock.
# MELOGOLD_KEEP_APP=1 skips the uninstall and keeps the app's data (e.g. a seeded library).
# Never start or kill the emulator here: the coordinator keeps it running.
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
. "$ROOT/scripts/agent/env.sh"

if [ $# -lt 1 ] || [ ! -f "$1" ]; then
    echo "usage: $0 <scenario.sh> [args...]" >&2
    exit 2
fi

melogold_take_emulator_lock "$0" "$@"

ANDROID_SERIAL=$MELOGOLD_EMULATOR
ADB="adb -s $MELOGOLD_EMULATOR"
export ANDROID_SERIAL ADB

reset_device() {
    adb shell settings put system font_scale 1.0
    adb shell cmd uimode night no >/dev/null
    adb shell wm size reset
    adb shell wm density reset
    adb shell settings put system user_rotation 0
}

adb wait-for-device
waited=0
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
    if [ "$waited" -ge 300 ]; then
        echo "emu.sh: $MELOGOLD_EMULATOR did not finish booting in 300 s" >&2
        exit 1
    fi
    sleep 2
    waited=$((waited + 2))
done

reset_device
if [ "${MELOGOLD_KEEP_APP:-}" != 1 ]; then
    adb shell pm uninstall "$MELOGOLD_APP_ID" >/dev/null 2>&1 || true
fi

trap 'reset_device || true' EXIT
trap 'exit 130' INT TERM

scenario=$1
shift
if [ -x "$scenario" ]; then "$scenario" "$@"; else sh "$scenario" "$@"; fi

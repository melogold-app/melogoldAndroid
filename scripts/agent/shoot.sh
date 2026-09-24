#!/bin/sh
# Emulator screenshot (REWRITE §6.0.9):
#   scripts/agent/shoot.sh <ID> <name>   →   $MELOGOLD_SHOTS_DIR/<ID>/<name>.png
# A name without the form suffix gets one from the device state: -P|-PL|-T|-TW, then -light|-dark,
# then -fs200 at font scale 2.0. So `shoot.sh R3.1 01-search-root` in dark mode on the tablet size
# writes 01-search-root-T-dark.png. Inside an emu.sh scenario it reuses the session's lock,
# otherwise it takes the emulator lock itself.
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
. "$ROOT/scripts/agent/env.sh"

if [ $# -ne 2 ]; then
    echo "usage: $0 <ID> <name>" >&2
    exit 2
fi

melogold_take_emulator_lock "$0" "$@"

id=$1
name=${2%.png}
serial=$MELOGOLD_EMULATOR

device() { adb -s "$serial" shell "$@" | tr -d '\r'; }

suffix() {
    case "$(device wm size)" in
        *"Override size: 1600x2560"*) form=T ;;
        *"Override size: 1840x2560"*) form=TW ;;
        *)
            case "$(device settings get system user_rotation)" in
                1 | 3) form=PL ;;
                *) form=P ;;
            esac
            ;;
    esac

    case "$(device cmd uimode night)" in
        *yes*) theme=dark ;;
        *) theme=light ;;
    esac

    font=$(device settings get system font_scale)
    case "$font" in
        1 | 1.0 | null | "") fs="" ;;
        *) fs="-fs$(echo "$font" | awk '{ printf "%d", $1 * 100 + 0.5 }')" ;;
    esac

    echo "-$form-$theme$fs"
}

if ! echo "$name" | grep -Eq -- '-(P|PL|T|TW)-(light|dark)(-fs[0-9]+)?$'; then
    name=$name$(suffix)
fi

dir=$MELOGOLD_SHOTS_DIR/$id
mkdir -p "$dir"
adb -s "$serial" exec-out screencap -p >"$dir/$name.png"
echo "$dir/$name.png"

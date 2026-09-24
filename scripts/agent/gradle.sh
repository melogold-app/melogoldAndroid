#!/bin/sh
# Runs ./gradlew under the machine-wide Gradle lock (REWRITE §6.0.7): one build at a time on the
# 16 GB machine, no daemon left behind. Merge tasks into one run instead of calling it twice:
#   scripts/agent/gradle.sh :app:assembleDebug :core:domain:test detekt
# Waiting in the queue is normal; do not edit the code while your build holds the lock.
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
. "$ROOT/scripts/agent/env.sh"

cd "$ROOT"
exec lockf -t "${MELOGOLD_LOCK_TIMEOUT:-7200}" "$MELOGOLD_LOCK_DIR/gradle.lock" \
    ./gradlew --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process "$@"

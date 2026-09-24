# Shared settings of the agent scripts (REWRITE §6.0.7); sourced, not run.
# Every value can be overridden from the environment.

# The lock files every agent on this machine uses: one Gradle build and one emulator session at a time
MELOGOLD_LOCK_DIR=${MELOGOLD_LOCK_DIR:-/private/tmp/claude-501/-Users-maxim-Documents-melogold/4f0297a2-cbbe-4757-8fc4-a29269ce6693/scratchpad/locks}
# Screenshots stay out of the repository (REWRITE §6.0.9)
MELOGOLD_SHOTS_DIR=${MELOGOLD_SHOTS_DIR:-/private/tmp/claude-501/-Users-maxim-Documents-melogold/4f0297a2-cbbe-4757-8fc4-a29269ce6693/scratchpad/shots}
MELOGOLD_EMULATOR=${MELOGOLD_EMULATOR:-emulator-5554}
MELOGOLD_APP_ID=${MELOGOLD_APP_ID:-app.melogold.android.debug}

ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_HOME

# JDK 25: the build takes its toolchain from JAVA_HOME only (gradle.properties)
if [ -n "${MELOGOLD_JAVA_HOME:-}" ]; then
    JAVA_HOME=$MELOGOLD_JAVA_HOME
elif [ -d /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
fi
export JAVA_HOME

PATH=$ANDROID_HOME/platform-tools:$PATH
export PATH

mkdir -p "$MELOGOLD_LOCK_DIR"

# Re-runs the calling script under the emulator lock unless this process tree already holds it.
# lockf without -k, exactly like the agents' own commands: mixing -k and plain lockf on one file
# lets two holders in at once.
melogold_take_emulator_lock() {
    if [ "${MELOGOLD_EMU_LOCKED:-}" != 1 ]; then
        MELOGOLD_EMU_LOCKED=1
        export MELOGOLD_EMU_LOCKED
        exec lockf -t "${MELOGOLD_LOCK_TIMEOUT:-3600}" "$MELOGOLD_LOCK_DIR/emulator.lock" "$@"
    fi
}

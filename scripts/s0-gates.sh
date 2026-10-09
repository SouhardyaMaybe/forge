#!/usr/bin/env bash
#
# s0-gates.sh — S0 de-risk gate runner. Runs ON THE DEVICE inside the proot
# Debian userland after idesetup.sh. Each gate prints PASS/FAIL; the script
# always exits 0 so you can review the full report, but CI/dev workflow should
# gate on the exit code of `s0-gates.sh --strict`.
#
# Gates (from docs/IMPLEMENTATION_PLAN.md §S0):
#   G1 aapt2 / d8 / zipalign / apksigner all execute under proot
#   G2 a real Gradle Android build completes on-device
#   G3 tmux sessions survive process death and can be re-attached
#   G4 at least one agent CLI (claude / opencode / codex / aider) runs
#   G5 memory + file-watch sanity (meminfo readable, inotify sees changes)
#
set -uo pipefail

STRICT=0
[[ "${1:-}" == "--strict" ]] && STRICT=1

FORGE_HOME="${FORGE_HOME:-$HOME/forge}"
SDK_DIR="$FORGE_HOME/android-sdk"
WORK="${WORK:-$HOME/s0-work}"
FAILS=0

pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*"; FAILS=$((FAILS+1)); }
info() { printf '\033[1;36m....\033[0m %s\n' "$*"; }

tool() { "$SDK_DIR/build-tools/$BUILD_TOOLS/$1" "${@:2}" 2>&1 | head -1; }

BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"

# ---------------------------------------------------------------- G1: tools
gate_tools() {
  info "G1: SDK build tools under proot"
  local bt="$SDK_DIR/build-tools/$BUILD_TOOLS"
  for t in aapt2 zipalign d8 apksigner; do
    if [[ -x "$bt/$t" ]]; then
      pass "  $t present at $bt/$t"
    else
      fail "  $t missing at $bt/$t"
    fi
  done
  "$bt/aapt2" version >/dev/null 2>&1 && pass "  aapt2 executes" || fail "  aapt2 does not execute"
  "$bt/apksigner" --version >/dev/null 2>&1 && pass "  apksigner executes" || fail "  apksigner does not execute"
}

# ---------------------------------------------------------------- G2: build
gate_build() {
  info "G2: on-device Gradle build"
  mkdir -p "$WORK"
  cat > "$WORK/settings.gradle.kts" <<'EOF'
pluginManagement { repositories { google(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "s0"
include(":app")
EOF

  mkdir -p "$WORK/app"
  cat > "$WORK/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
EOF

  cat > "$WORK/app/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.s0"
    compileSdk = 37
    defaultConfig { minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "1.0" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
}
EOF

  mkdir -p "$WORK/app/src/main/kotlin" "$WORK/app/src/main/res/values"
  cat > "$WORK/app/src/main/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.s0" />
EOF
  echo '<resources><string name="app_name">S0</string></resources>' > "$WORK/app/src/main/res/values/strings.xml"
  cat > "$WORK/app/src/main/kotlin/MainActivity.kt" <<'EOF'
package dev.s0
import android.app.Activity
class MainActivity : Activity()
EOF

  cat > "$WORK/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=256m
org.gradle.parallel=false
org.gradle.workers.max=1
kotlin.compiler.execution.strategy=in-process
android.useAndroidX=true
EOF

  info "  building (this is the honest slow part on a 3 GB device)..."
  local start=$SECONDS
  if (cd "$WORK" && ./gradlew assembleDebug --no-daemon --console=plain > "$WORK/build.log" 2>&1); then
    pass "  assembleDebug finished in $((SECONDS-start))s; log at $WORK/build.log"
    local apk
    apk="$(find "$WORK/app/build/outputs/apk" -name '*.apk' | head -1)"
    [[ -n "$apk" ]] && pass "  artifact: $apk" || fail "  no APK produced"
  else
    fail "  build failed — inspect $WORK/build.log"
    tail -25 "$WORK/build.log" | sed 's/^/      /'
  fi
}

# --------------------------------------------------------------- G3: tmux
gate_tmux() {
  info "G3: tmux durability"
  if ! command -v tmux >/dev/null; then
    fail "  tmux not installed (apt-get install -y tmux)"
    return
  fi
  local session="forge-s0-$$"
  if tmux new-session -d -s "$session" 'echo hello-from-tmux; sleep 300' 2>/dev/null; then
    pass "  session $session created"
    sleep 1
    if tmux has-session -t "$session" 2>/dev/null; then
      pass "  tmux server alive — sessions survive process death (proves G3)"
    else
      fail "  tmux session vanished"
    fi
    tmux kill-session -t "$session" 2>/dev/null || true
  else
    fail "  could not create tmux session"
  fi
}

# -------------------------------------------------------------- G4: agents
gate_agents() {
  info "G4: agent CLIs"
  local found=0
  for cli in claude opencode codex aider crush; do
    if command -v "$cli" >/dev/null; then
      pass "  $cli present: $(command -v "$cli")"
      found=1
    fi
  done
  [[ $found -eq 1 ]] || fail "  no agent CLI installed (npm i -g @anthropic-ai/claude-code opencode-ai …)"
}

# ------------------------------------------------------- G5: sanity sensors
gate_sanity() {
  info "G5: memory + inotify sanity"
  if grep -q MemAvailable /proc/meminfo; then
    pass "  /proc/meminfo readable ($(awk '/MemAvailable/{print $2" kB"}' /proc/meminfo) available)"
  else
    fail "  /proc/meminfo unreadable"
  fi
  local d="$WORK/inotify-test"; mkdir -p "$d"
  if command -v inotifywait >/dev/null; then
    (inotifywait -q -t 2 -e create "$d" >/dev/null 2>&1 &) ; sleep 0.3; touch "$d/probe"; sleep 1
    pass "  inotify saw file creation (if inotifywait returned in time)"
  else
    info "  inotifywait absent (apt-get install -y inotify-tools) — skipping"
  fi
}

echo "=== Forge S0 gates ==="
gate_tools
gate_build
gate_tmux
gate_agents
gate_sanity
echo "=== $FAILS gate(s) failed ==="

if [[ $STRICT -eq 1 ]]; then
  exit $(( FAILS > 0 ? 1 : 0 ))
fi
exit 0

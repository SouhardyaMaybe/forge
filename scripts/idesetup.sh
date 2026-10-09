#!/usr/bin/env bash
#
# idesetup.sh — provision the Forge toolchain ON THE DEVICE.
#
# Runs inside the proot Debian userland (e.g. Termux: `proot-distro login debian`),
# NOT in CI and NOT on a desktop. It installs:
#
#   - openjdk-17 (Debian package, glibc)
#   - Android SDK cmdline-tools, platform-tools, build-tools, one platform
#
# Everything lands under $FORGE_HOME (default: $HOME/forge).
#
# Usage:
#   ./idesetup.sh                     # default: build-tools 36.0.0 + android-36
#   BUILD_TOOLS=37.0.0 PLATFORM=37 ./idesetup.sh
#
set -euo pipefail

FORGE_HOME="${FORGE_HOME:-$HOME/forge}"
BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"
PLATFORM="${PLATFORM:-36}"
SDK_DIR="$FORGE_HOME/android-sdk"

log() { printf '\033[1;32m[idesetup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[idesetup]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[idesetup] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

detect_arch() {
  case "$(uname -m)" in
    aarch64|arm64) echo "aarch64" ;;
    armv7l|armv8l|arm) echo "arm" ;;
    x86_64) echo "x86_64" ;;
    *) die "unsupported architecture: $(uname -m)" ;;
  esac
}

check_env() {
  [[ $EUID -ne 0 ]] || warn "running as root inside proot; fine but unusual"
  command -v apt-get >/dev/null || die "apt-get not found — run this inside the Debian userland"
  command -v curl >/dev/null || die "curl not found — install it with: apt-get install -y curl"
}

install_jdk() {
  if [[ -x /usr/lib/jvm/java-17-openjdk-*/bin/java ]] || command -v java >/dev/null; then
    log "JDK already present: $(java -version 2>&1 | head -1)"
    return
  fi
  log "installing openjdk-17 (this is the big one)..."
  apt-get update -qq
  apt-get install -y -qq openjdk-17-jre-headless ca-certificates
  log "JDK installed: $(java -version 2>&1 | head -1)"
}

install_sdk() {
  local arch; arch="$(detect_arch)"
  case "$arch" in
    aarch64) CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" ;;
    x86_64)  CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" ;;
    arm)     die "Google does not ship 32-bit ARM cmdline-tools; a bionic port is required here." ;;
  esac

  mkdir -p "$SDK_DIR/cmdline-tools"
  if [[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
    log "Android SDK already provisioned at $SDK_DIR"
  else
    log "downloading Android SDK cmdline-tools (~130 MB)..."
    local tmp; tmp="$(mktemp -d)"
    curl -sSL -o "$tmp/clt.zip" "$CMDLINE_URL"
    unzip -q "$tmp/clt.zip" -d "$tmp/clt"
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$tmp/clt/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$tmp"
  fi

  export ANDROID_SDK_ROOT="$SDK_DIR"
  export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

  log "accepting licenses..."
  yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

  log "installing platform-tools, build-tools;$BUILD_TOOLS, platforms;android-$PLATFORM..."
  "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" \
    "platform-tools" "build-tools;$BUILD_TOOLS" "platforms;android-$PLATFORM" >/dev/null
}

write_env_file() {
  cat > "$FORGE_HOME/env.sh" <<EOF
# Source this inside the proot userland before building:
#   . $FORGE_HOME/env.sh
export FORGE_HOME="$FORGE_HOME"
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export JAVA_HOME="\${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export PATH="\$JAVA_HOME/bin:\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$ANDROID_SDK_ROOT/platform-tools:\$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS:\$PATH"
EOF
  log "wrote $FORGE_HOME/env.sh"
}

main() {
  check_env
  log "arch: $(detect_arch)"
  install_jdk
  install_sdk
  write_env_file
  log "done. Next: . $FORGE_HOME/env.sh && ./scripts/s0-gates.sh"
}

main "$@"

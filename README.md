# Forge

**A native, agentic IDE for Android.** Editor, terminal, on-device Gradle builds, and a host for CLI coding agents — built for 3 GB phones.

> Status: **scaffold / M0 spike**. CI builds a debug APK; feature work lands incrementally per the implementation plan.

## What Forge is

A from-scratch Kotlin/Compose app (no WebView, no Electron) that provides:

| Pillar | What it means |
|---|---|
| **Native editing** | Compose UI, VS Code-style file explorer, tabs, command palette, LSP bridge — 60 fps on a 3 GB phone |
| **Real builds** | A Debian userland under proot hosts JDK, Gradle, Android SDK build-tools and NDK, so real `assembleDebug` / signed `assembleRelease` run on the phone |
| **Durable everything** | tmux-backed terminals, journaled agents, log-tailed builds: any process can die and lose ≤ 1 s |
| **Any agent** | Host Claude Code, OpenCode, Codex, Aider or any TUI agent with a permission broker, checkpoints and rollback |

## Repository layout

```
app/                  MainActivity, Navigation 3 host, feature screens
core/backend-api/     Wire protocol DTOs (shared by UI + backend processes)
core/backend/         Foreground service + Ktor server on 127.0.0.1 (:backend process)
core/native/          Native PTY (JNI) + Kotlin session wrapper
scripts/              On-device toolchain provisioning and S0 spike gates (run on the phone)
docs/                 Implementation plan and research notes
.github/workflows/    CI — assembles the debug APK and runs unit tests
```

Full architecture, constraints and the 18-month roadmap: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).

## Building

**All builds happen on GitHub Actions** (this repo's dev environment has no Android build capacity).

- Push to `main` or open a PR → CI assembles the debug APK and uploads it as an artifact.
- Manual run: Actions → *build* → *Run workflow*.

Pinned toolchain: AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, compileSdk/targetSdk 37, minSdk 26.

To build locally (once, on a machine with the SDK), generate the wrapper first:

```bash
gradle wrapper --gradle-version 9.8.1 --distribution-type bin
./gradlew :app:assembleDebug
```

## On-device setup (S0 spike)

The scripts in `scripts/` run **on the phone** (inside Termux + proot Debian), not in CI:

```bash
# Provision toolchain: rootfs, JDK, Android SDK build-tools
./scripts/idesetup.sh

# Verify the S0 gates: aapt2/d8/gradle run, tmux survives UI death, agent CLIs run
./scripts/s0-gates.sh
```

## Roadmap

- **S0 (now)** — de-risk spike on a 3 GB device: proot + SDK tools + a real Gradle build
- **M1** — alpha IDE: files, editor, terminal, build orchestration, install, logcat
- **M2** — git UI and LSP bridge
- **M3** — agentic host with permission broker and checkpoints
- **M4** — hardening, adaptive layouts, plugin system
- **M5** — beta and public release

## License

Apache-2.0. Third-party component licenses are documented in `docs/oss-research.md`.

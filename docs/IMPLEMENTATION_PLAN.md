# Implementation Plan — Android-Native Agentic IDE

**Codename:** Forge (renameable)
**Type:** Production-ready mobile IDE + APK build environment + CLI-agent host
**Target device floor:** 3 GB RAM Android phones (Android 8.0+), optimized for phones, usable on tablets/foldables/desktop-mode
**Team model:** 1–2 developers, ~18 months to production
**Doc version:** 1.0 — Oct 2026

---

## 0. Scope, Assumptions, Non-Goals

### 0.1 What this is
A from-scratch Android app that provides, **on the phone itself**:

1. A VS Code/Antigravity-class **code editing experience** (file explorer, tabs, editor, command palette, git, search, diagnostics).
2. A real **Linux development environment** (Debian userland under proot) containing JDK, Gradle, Android SDK build-tools, NDK, interpreters (Python/Node) — i.e. real `./gradlew assembleRelease` producing installable APKs/AABs.
3. A **terminal** with durable sessions (tmux-backed) and full shell access.
4. An **agentic coding host**: run *any* CLI agent (Claude Code, OpenCode, Codex, Aider, "DeepSeek harness", etc.) inside that environment with a managed UI, permission prompts, streaming output, checkpoints and rollback.
5. A **plugin system** with a marketplace-style catalog, including importers for Open VSX assets (themes, grammars, snippets, keybindings) and "tool plugins" that install new toolchain components.

### 0.2 Key architectural decisions (already made)

| # | Decision | Rationale |
|---|---|---|
| D1 | Custom native app (Kotlin/Compose) UI; **no WebView/Chromium** in the editing loop | A 3 GB phone cannot afford Electron/code-server (~450 MB+ RSS). Native UI is the only path to the "no lag" requirement. |
| D2 | Backend runs as a **Debian userland under proot**, glibc binaries unmodified | Zero porting cost for official Android SDK tools (aapt2, d8, zipalign, apksigner), JDK, Gradle, NDK, git, node, python. bionic-native ports are a later optimization for hot tools only. |
| D3 | Everything long-running lives **outside the Activity process** and is resumable | Android's LMK + lifecycle will kill things. Survival = durability of state, not never-dying processes. |
| D4 | Agent = **hosted CLI processes** with adapters + a permission broker | Reuses the entire agent ecosystem (Claude Code hooks, OpenCode sessions, Aider JSON…) instead of reimplementing an agent runtime we cannot out-iterate. |
| D5 | Multi-process app with a **Resource Governor** | Build / LSP / agent / terminal compete for ~1.5 GB of usable RAM on a 3 GB phone. Only one "heavy" op at a time, by policy. |

### 0.3 Non-goals (explicit)
- Full VS Code extension API (Electron) compatibility. We support a documented subset (§11).
- Cloud/remote build offload in v1 (design the seam; ship later).
- Running Android emulators on-device (impossible at this RAM class).
- Replacing Android Studio on desktop. This is a *phone-first* tool for real work away from a desk, and for on-device iteration.
- Supporting devices below 3 GB RAM.

### 0.4 Assumptions (verify in Spike S0)
- proot + Debian userland can run the official glibc aarch64 Android SDK cmdline-tools & build-tools on Android 10–15 devices (strong evidence from the Termux/proot-distro ecosystem; must be validated on a 3 GB device before anything else).
- Gradle + AGP complete a debug build of a small app on-device within a tolerable time window under our memory tiers.
- Terminal-emulator + editor view libraries can be vendored/reimplemented with acceptable license (GPL) or replaced by our own VT100/editor cores.

---

## 1. Executive Summary

**Product thesis:** developers already carry a 3 GB Android phone that is a paperweight for real coding work. Forge turns the phone into a *credible* mobile dev machine: native-speed editing, a real toolchain on-device, durable terminals, and every agent CLI as a first-class participant — without lagging, crashing, or losing work when Android reclaims memory.

**Four product pillars (each maps to later chapters):**

| Pillar | Meaning | Chapter |
|---|---|---|
| **Native feel** | 120 Hz-capable Compose UI, 60 fps editing on 50k-line files, < 2.5 s cold start | §7, §12 |
| **Real builds** | Actual Gradle/AGP pipeline; debug & signed release artifacts | §5, §6 |
| **Durable everything** | Terminals in tmux, agents resumable, builds restart-safe, UI reconnects | §13 |
| **Any agent** | Claude Code / OpenCode / Codex / Aider / generic PTY, with permissions & checkpoints | §10 |

**Shape of the system in one line:** a Compose UI process, a supervised backend process hosting localhost HTTP/WS + a proot Linux world, and a resource governor that serializes heavy work so a 3 GB device never OOMs.

---

## 2. Product Definition

### 2.1 Primary personas
1. **Mobile-first sidetracker** — has 45 min on a train, wants to review a PR, fix a crash, kick off a release build.
2. **On-call commuter** — needs SSH-free, laptop-free hotfix: edit → build → install → verify on a test device.
3. **Agent-pilot** — runs Claude Code/OpenCode from the phone, reviews diffs, approves permissions, monitors long refactors while away from desk.
4. **Student/hacker on cheap hardware** — 3 GB phone is their only computer; wants to learn Android dev without a PC.

### 2.2 Feature matrix (MoSCoW)

| Feature | Priority | Milestone |
|---|---|---|
| File explorer (tree, git overlays, CRUD, rename, drag) | Must | M1 |
| Code editor (highlighting, multi-cursor, undo/redo, goto symbol, minimap) | Must | M1 |
| Terminal (durable tmux sessions, soft-keyboard accessories) | Must | M1 |
| Gradle build: debug + release signing, APK install, logcat | Must | M1 |
| Git status/diff/commit/pull/push (in-rootfs git) | Must | M2 |
| LSP bridge: Kotlin, Java, C/C++, JSON, XML | Should | M2 |
| Agent host (Claude Code adapter, OpenCode adapter, generic PTY) | Must | M3 |
| Permission broker + checkpoints + diff review UI | Must | M3 |
| Command palette, go-to-file, workspace search (ripgrep) | Must | M1 |
| Adaptive layout (phone/tablet/foldable/desktop mode) | Should | M4 |
| Plugin catalog: themes, snippets, keybindings, grammars | Should | M4 |
| Tool plugins (install LSP/runtimes into rootfs) | Could | M5 |
| Split view / multi-window editors | Should | M4 |
| Remote model keys manager, on-device ML Kit fallback | Could | M5 |
| Cloud build offload | Won't (v1) | — |
| Emulator | Won't | — |

---

## 3. Hard Constraints → Engineering Laws

These are non-negotiable design laws derived from the 3 GB target. Every later chapter obeys them.

**L1 — RAM budget is a first-class resource.** On a 3 GB device, usable headroom after the OS is ~1.5–1.8 GB. Budget:
| Consumer | Ceiling (Tier-L device) |
|---|---|
| UI process (Compose + editor + images) | ≤ 320 MB |
| Backend process (HTTP/WS, watchers, DB) | ≤ 160 MB |
| proot + shell + small tools | ≤ 90 MB |
| Gradle daemon JVM | ≤ 1.0 GB heap, workers = 1 |
| Kotlin compilation | in-process (no 2nd daemon) |
| LSP servers | suspended during builds |
| Agent CLIs | at most 1 concurrent with a build |

**L2 — One heavy operation at a time.** A central Resource Governor serializes: builds, agent runs, LSP indexing, package installs. Others queue with visible state. No exceptions; this is what prevents OOM-kill death spirals.

**L3 — State lives on disk, processes are disposable.** Every long-running operation (terminal, agent, build, agent transcript) is backed by a durable artifact (tmux server, session journal, log files, Room DB). Killing any process must lose ≤ 1 s of work.

**L4 — The UI process must never be the thing that dies.** Backend crash ≠ app crash; agent crash ≠ app crash; build daemon OOM ≠ app crash. Process isolation is mandatory.

**L5 — Reconnect is a feature, not an error path.** Every screen must render "reconnecting/attaching…" states that are informative, not fatal, and must converge to live state without user action.

**L6 — Interruptible, never blocking I/O on the main thread; jank budget 16 ms.** Editor scrolling is the sacred path: no allocation, no IPC on scroll frames.

**L7 — Storage is scarce too.** 3 GB-RAM devices are typically 32 GB. Toolchain is downloaded on demand per ABI with resume + delta; provide a "disk budget" dashboard and cleanup tools.

**L8 — Battery and thermal sanity.** Heavy work is foreground-service work with a visible notification and thermal throttling awareness (poll thermal zones; degrade to "lite build" when throttling).

---

## 4. System Architecture

### 4.1 Process topology

```
┌────────────────────────────────────────────────────────────────────┐
│  PROCESS :forge (UI, default)                                     │
│  MainActivity · Compose navigation · Editor/FileTree/Terminal/Agent│
│  panels · diff viewer · permission dialogs · settings              │
│  Role: thin, restartable, stateless-ish. All truth lives below.    │
└───────────────┬────────────────────────────────────────────────────┘
                │ 127.0.0.1:<random port> · WS + REST · bearer token
                │ (idempotent, resumable protocol; auto-reconnect)
┌───────────────▼────────────────────────────────────────────────────┐
│  PROCESS :forge.backend (foreground service, dataSync type)       │
│  ┌──────────────┐ ┌───────────────┐ ┌──────────────┐ ┌──────────┐ │
│  │ Ktor server  │ │ File watcher  │ │ SQLite/Room  │ │ Resource │ │
│  │ (REST+WS)    │ │ (inotify)     │ │ journals     │ │ Governor │ │
│  └──────────────┘ └───────────────┘ └──────────────┘ └──────────┘ │
│  ┌──────────────┐ ┌───────────────┐ ┌──────────────┐ ┌──────────┐ │
│  │ Env manager  │ │ Build orch.   │ │ Agent host   │ │ Plugin   │ │
│  │ (rootfs, SDK)│ │ (gradle queue)│ │ (agentd)     │ │ manager  │ │
│  └──────────────┘ └───────────────┘ └──────────────┘ └──────────┘ │
└───────────────┬────────────────────────────────────────────────────┘
                │ fork/exec, setsid, nohup — children outlive UI
┌───────────────▼────────────────────────────────────────────────────┐
│  PROCESS :forge.watchdog (native, near-empty; init-style)          │
│  C++ supervisor: heartbeat files, exponential-backoff respawn of    │
│  backend, meminfo sampler, crash-loop breaker → user-visible state  │
└────────────────────────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────────┐
│  proot (Debian userland) — a separate process tree, NOT an         │
│  android:process; survives UI+backend death (reparented to init)    │
│                                                                   │
│   tmux server (sessions survive everything)                        │
│   ├─ shell sessions (user terminals)                              │
│   ├─ gradle daemons / build workers                               │
│   ├─ agentd (spawns claude/opencode/codex/aider CLIs)             │
│   └─ LSP servers (kotlin-ls, clangd, jdt.ls, jsonls) — idled off  │
└────────────────────────────────────────────────────────────────────┘
```

Key properties:
- UI ↔ backend over localhost HTTP/WS with a random port + per-boot token (stored in encrypted prefs); consider Unix-domain sockets later (junixsocket) to drop the TCP stack and harden the boundary.
- The proot tree is started detached (`setsid`) so it survives UI *and* backend death; backend re-attaches on restart (tmux `attach`, `gradle --status`, journal replay).
- `:forge.watchdog` exists so that a wedged backend can be reaped even if Android's service restart is slow/delayed.

### 4.2 Gradle module map

```
forge/
├─ app/                        # :app — Activity, nav host, DI wiring, process decls
├─ core/
│  ├─ designsystem/            # theme, M3 tokens, typography, icon set, Styles API
│  ├─ common-ui/               # panels, tabs, breadcrumbs, command palette, dialogs
│  ├─ editor/                  # editor core: view, lexers, gestures, IME accessory
│  ├─ terminal/                # VT100 core + emulator view + session mgmt
│  ├─ files/                   # file explorer, tree VM, git overlays, watchers
│  ├─ backend/                 # Ktor server, services, governor, DB (Room)
│  ├─ backend-api/             # shared DTOs (kotlinx.serialization) — pure Kotlin
│  ├─ native/                  # C++: pty, supervisor, meminfo, proot exec helpers
│  └─ networking/              # WS/REST client, reconnect/backoff, event bus
├─ feature/
│  ├─ files/  editor/  terminal/  build/  agent/  git/  plugins/  settings/  search/
├─ benchmark/                  # macrobenchmark: startup, scroll, editor open, build sim
└─ test-utils/                 # fakes, fixtures, device-lab harness
```

Rules: `backend-api` is the contract module (no Android deps) so UI and backend can evolve independently. Feature modules depend on `common-ui` + `backend-api` only.

### 4.3 Tech stack

| Concern | Choice | Note |
|---|---|---|
| Language | Kotlin 2.x (K2) | Coroutines/Flow everywhere |
| UI | Jetpack Compose, Material 3, `androidx.compose` Styles API | edge-to-edge, JankStats |
| Nav | Navigation 3 (type-safe routes, scenes) | two-pane on large screens |
| DI | Hilt | process-scoped components (`@Singleton` per process) |
| Local server | Ktor 3 (CIO) server + client | WS with backpressure |
| Persistence | Room + DataStore (encrypted) | journals, settings, agent sessions |
| Serialization | kotlinx.serialization | protocol + plugin manifests |
| Images/IO | Coil 3 (bounded caches) | RAM ceilings enforced |
| Native | C++17, NDK, JNI | pty, watchdog, meminfo |
| Editor core | Rosemoe `code-editor` (View-based) in `AndroidView` **or** in-house core — decision gate M0.5 (§7.1) | proven virtualization vs. control |
| Terminal core | In-house VT100-lite parser + View renderer **or** vendored GPL component — decision gate M0.5 (§8.1) | |
| Build | Gradle + AGP 9 baseline; version catalog | see §6 |
| CI | GitHub Actions + device-lab (Firebase Test Lab or self-hosted cheap phones) | real 3 GB devices in the loop |

### 4.4 Wire protocol (sketch)

One WS connection, JSON envelopes, idempotent `requestId`, server-push events:

```
→ {"id":1,"method":"fs.list","params":{"path":"/projects/demo","depth":2}}
← {"id":1,"result":{"entries":[…]}}
→ {"id":2,"method":"term.open","params":{"cols":80,"rows":24,"attach":"main"}}
→ {"id":3,"method":"term.input","params":{"session":"main","data":"ls\n"}}
← {"event":"term.output","params":{"session":"main","data":"…\r\n"}}
→ {"id":4,"method":"build.start","params":{"task":"assembleDebug","project":"demo"}}
← {"event":"build.progress","params":{"phase":"kotlin-compile","pct":42}}
← {"event":"build.finished","params":{"artifacts":["…/demo-debug.apk"],"ok":true}}
→ {"id":5,"method":"agent.run","params":{"cli":"claude","prompt":"fix the NPE in MainActivity"}}
← {"event":"agent.event","params":{"kind":"tool_use","tool":"Bash","input":{…}}}
← {"event":"agent.permission.request","params":{"id":"p1","cmd":"rm -rf build/"}}
→ {"id":6,"method":"agent.permission.respond","params":{"id":"p1","allow":false}}
```

Rules: every mutating op carries `idempotencyKey`; events are journaled to disk so a reconnecting client can request `since=<seq>` replay. File payloads > 256 KB stream in chunks with backpressure (`file.read.chunk` / `file.write.chunk`).

---

## 5. The Backend Environment: proot Rootfs + Toolchain

This chapter is the heart of the product and the biggest technical risk (see Spike S0).

### 5.1 Why proot-Debian (not bionic ports)

The official Android SDK build tools (aapt2, zipalign, d8's native helpers, NDK clang lld) ship **glibc** Linux binaries. On Android (bionic) they do not run. Two options:

| Option | Cost | Perf | Risk |
|---|---|---|---|
| **A. proot + Debian (chosen)** | near-zero porting; everything just works | ptrace tax ~20–50 % on syscall-heavy ops (fine for builds; hurts tight REPL loops) | proot+wine-class problems on some kernels; must validate on 3 GB devices across vendors |
| B. bionic-native ports (Termux-style) | must port JDK+JNI-heavy tools, compile aapt2 for bionic | best | months of native toolchain work; blocked on vendor kernels |

Plan A ships; Plan B is a *performance* project in M4+ that swaps **hot** components (JDK, clang, git, python) to bionic-native builds one by one, behind an A/B switch per tool. Keep the toolchain manifest abstract (§5.4) so this is a data change, not a rewrite.

### 5.2 Rootfs assembly & delivery

**Rootfs contents (installed layer, on demand):**

```
/forge-rootfs/                      # app-private external storage (see §14 on policy)
├─ debian-base/        # ~210 MB dl: dash, coreutils, bash, grep/sed/awk, tar, xz, curl, ca-certs, procps, psmisc, ncurses
├─ langs/              # openjdk-17-jre-headless (~150 MB), python3, nodejs (optional)
├─ vcs/                # git, git-lfs, openssh-client, ripgrep, tmux, jq
├─ android-sdk/        # cmdline-tools (~130 MB) + platform-tools + build-tools;34/36 + platforms;android-36
├─ ndk/                # optional, ~1 GB — explicit user choice
└─ forge-tools/        # agentd, guard hook, template projects, gradle dist (~130 MB, or use wrapper dist cached)
```

**Delivery mechanics:**
- ABI variants: `arm64-v8a` (primary), `armeabi-v7a` (older 3 GB phones!), `x86_64` (emulator/ChromeOS devices). One rootfs per ABI; SDK build-tools are per-ABI too.
- Packaging as **Play Asset Delivery** (install-time or fast-follow packs) *and* an in-app downloader (resumable, resume-on-reboot, delta via xdelta3) for GitHub/F-Droid users. Storage-first design: never extract twice; verify with SHA-256 manifest.
- First-run wizard with explicit size disclosures and a "Download over Wi-Fi only" guard + estimated time on the device's measured throughput.
- Cleanup tool: per-layer uninstall, "reclaim" button, cache eviction for old build-tools/platforms.

### 5.3 Boot sequence (and re-attach)

```
cold start:
  watchdog (native) starts → backend service starts →
    1. resolve rootfs (verify manifest, else offer setup)
    2. exec: proot -r rootfs -b /proc -b /system -b /dev -b /sdcard … /bin/bash -lc
             "tmux new-session -d -s forge-main ; true"      # detached
    3. wait for tmux ready (probe with a sentinel command)
    4. start Ktor server → persist {port, token} → notify UI
warm start / backend death:
    1. detect live tmux (tmux has-session) → attach, no data loss
    2. verify gradle daemons (gradle --status) → rebuild orchestration state from journal
    3. UI gets "reattached n sessions, m builds resumed"
```

### 5.4 Toolchain manifest (data-driven)

```yaml
# forge-toolchain.yaml — the seam for bionic-native swaps later
layers:
  debian-base:
    abi: [arm64-v8a, armeabi-v7a]
    packages: [bash, coreutils, grep, sed, gawk, tar, xz-utils, curl, ca-certificates,
               procps, psmisc, ncurses-base, ncurses-bin, less, findutils]
    backend: apt-in-proot        # apt-get install inside rootfs, no-network resolver
  openjdk:
    source: debian-repo          # later: termux-bionic build
    packages: [openjdk-17-jre-headless]
  android-sdk:
    source: dl.google.com        # cmdline-tools + sdkmanager (glibc, unmodified)
    components: [platform-tools, build-tools;36.0.0, platforms;android-36]
    accept-licenses: true
  tmux: { packages: [tmux] }
  agentd: { source: bundled }    # our supervisor scripts + agent adapters
tools:                            # provider registry — how UI finds a binary
  gradle:   { layer: openjdk,     probe: "gradle -v" }
  kotlinc:  { layer: agentd-bundle or debian, probe: "kotlinc -version" }
  aapt2:    { layer: android-sdk, probe: "aapt2 version" }
  apksigner:{ layer: android-sdk, probe: "apksigner --version" }
  zipalign: { layer: android-sdk, probe: "zipalign" }
  clangd:   { layer: debian-repo, probe: "clangd --version" }
  kotlin-ls:{ layer: agentd-bundle, probe: "kotlin-language-server --help" }
  node:     { layer: langs, optional: true }
  python:   { layer: langs, probe: "python3 -V" }
```

The **tool registry** is also the plugin surface for "tool plugins" (§11.4): a plugin = a layer + probe command + UI metadata.

### 5.5 Environment invariants to validate in S0
1. `aapt2`, `zipalign`, `d8`, `apksigner` execute correctly under proot on target devices (Samsung A-series, Pixel 4a/6a class, Xiaomi/MIUI quirks with exec/spawn policies).
2. `gradle assembleDebug` for a template Compose app finishes on a 3 GB device within budget and without LMK interference.
3. tmux survives UI process death; re-attach works.
4. inotify (file watches) sees changes made inside proot (they're real files on the same fs).
5. Android's exec/spawn restrictions (SELinux `execmod`/`noexec` on /sdcard, W^X) don't block running binaries from app-private storage. **Fallback:** execute from a path under the app's native lib dir; or ship binaries via `android:extractNativeLibs` + copy-on-first-run.

---

## 6. Build System Engineering

The difference between "compiles an APK" and "compiles an APK on a 3 GB phone without OOM-death" is this chapter.

### 6.1 Memory tiers (detected at first run + re-evaluated per session)

Measured signals: `ActivityManager.MemoryInfo.totalMem`, `/proc/meminfo MemAvailable`, thermal status, storage class, `Build.SOC` tier. Emit a `DeviceProfile` used by every policy in the app.

```properties
# gradle.properties.tier-L   (3 GB class — the floor we optimize for)
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.caching=true
org.gradle.configuration-cache=true          # validate per AGP version
kotlin.incremental=true
kotlin.compiler.execution.strategy=in-process # NO second daemon (saves a JVM)
android.enableBuildCache=true
android.useAndroidX=true
android.defaults.buildfeatures.buildconfig=false
android.defaults.buildfeatures.aidl=false
android.defaults.buildfeatures.rendererscript=false
android.defaults.buildfeatures.shaders=false
# debug variant defaults injected by us into the user project:
#   minifyEnabled=false (R8 only for explicit release builds)
#   packagingOptions excludes for license metadata to cut dex work
```

```properties
# gradle.properties.tier-M   (4–6 GB)
org.gradle.jvmargs=-Xmx1536m
org.gradle.parallel=true ; workers.max=2
kotlin.compiler.execution.strategy=daemon
# gradle.properties.tier-H   (8 GB+) — close to desktop defaults
```

Templates ship `gradle.properties` per tier + a `forgeProfile { tier = "auto" }` block in the template's `build.gradle.kts` that flips debug speed flags (no R8, no resource shrinker, `debugSymbolLevel=NONE`, ABI splits per device, dragonbones-free Compose profile installer optional).

### 6.2 Build modes

| Mode | Use | Behavior |
|---|---|---|
| **Debug-fast** (default) | iteration | no minify, no shrinker, `assembleDebug`, incremental |
| **Typecheck** | sanity | `compileDebugKotlin compileDebugJavaWithJavac lint` — no dexing, ~40 % of build time |
| **Release-signed** | shipping | R8 (fullMode off by default), zipalign, apksigner v2+v3, AAB optional |
| **Lite/degraded** | thermal or MemAvailable floor | kicks in automatically: shrinks heap further, workers=1, pauses LSPs, serializes with agent runs, throttles log streaming |
| **Clean** | nuclear | wipes build dirs + daemon registry |

### 6.3 Build Orchestrator (backend service)

Responsibilities:
- **Queue with priorities**: user-initiated > agent-initiated > background (git fetch). Visible queue UI with cancel/reorder.
- **Arbitration with Resource Governor**: a build is a "heavy lease"; starting a build suspends LSP servers (SIGSTOP them — cheap, instant, reversible) and warns/blocks concurrent agent runs unless user opts into "shared mode" with the lite profile.
- **Streaming**: `gradle --console=plain` output parsed into structured progress events; persisted to `~/.forge/builds/<id>.log` so the UI can tail the file after any restart. **Log files are the source of truth, not in-memory buffers.**
- **Daemon management**: `gradle --stop` when device profile demands (before big clean builds); expose daemon count/health in UI.
- **Artifacts registry**: scan `*/build/outputs/**`, hash artifacts, present install/share/export actions; keep last N builds with eviction policy.
- **Install & verify**: session-based `pm install` via `cmd package install` (Termux-style RUN_COMMAND isn't available to us — we run `pm`/`cmd` as shell uid in our own process, which works for APKs the user built), plus logcat overlay filtered to the installed package (`--pid` tracking).
- **Failure intelligence**: parse OOM/Metaspace/gradle worker errors → one-tap "Retry with safer profile" (heap −256 MB, workers 1) + hint text. This is the single highest-leverage UX of the whole app.

### 6.4 Release pipeline on-device

- Keystore stored in app-private encrypted storage (AES-GCM, key in Android Keystore). Password prompt per release build unless "unlock for this session".
- `assembleRelease` → R8 → `zipalign -p 4` → `apksigner sign --ks … --v2 --v3` → verify (`apksigner verify --verbose`) → artifact registry.
- Version bumping UI (versionCode/versionName), changelog capture, optional git tag.
- AAB (`bundleRelease`) + `bundletool build-apks` deferred to M5 (heavy JVM, low-RAM-hostile; ship "universal APK" interim).

### 6.5 Performance playbook (concrete)

1. Cache warm: `~/.gradle/caches` on fast internal storage; never on sdcardfs.
2. Kotlin daemon off on Tier-L (single JVM does compile).
3. R8 off for debug; resource shrinking off.
4. `--build-cache` local + optional user-configured remote cache.
5. Build only the needed ABI/variant (single-variant sync like AS's "project structure" defaults).
6. First build pre-warms caches during the setup wizard (template build runs in background while user reads docs).
7. Thermal governor: poll `/sys/class/thermal/thermal_zone*/temp`; at throttle threshold → switch to lite mode + notify; at critical → pause build with checkpointed state (gradle daemon keeps its own state; safe to pause between tasks, not mid-task).
8. Expected honest numbers (validate in S0, publish in docs): small Compose app Tier-L debug ≈ 4–10 min first build, ≈ 1.5–4 min incremental; Tier-M roughly halved; bionic-JVM optimization targets 2–4× on Kotlin compile.

---

## 7. Code Editor

### 7.1 Decision gate M0.5 — editor core

Evaluate: (a) Rosemoe `io.github.Rosemoe:code-editor` in `AndroidView` — mature virtualization, lexers, find/replace, used by ACode/Xed; (b) custom Compose `LazyLayout` editor. Choose (a) for M1 (proven), keep a Compose-compatible façade so we can replace it later without touching UI code. Non-negotiables: virtualization (no per-line View inflation), no main-thread IO during scroll, undo/redo via content snapshots with delta compression.

### 7.2 Feature set (M1–M2)

- Tabs (dirty markers, drag-reorder, pinned), breadcrumb bar (`package / File.kt / symbol`), split view (M4), minimap (M4).
- Lexers: Kotlin, Java, XML, JSON/YAML, C/C++, Gradle (Groovy+KTS), Markdown, shell, Python. Grammar source = our own JFlex-style lexers (Rosemoe-style) + TextMate JSON importer (M4, from plugin grammars).
- Editing: multi-cursor, block select (with soft keyboard via long-press drag), auto-indent, bracket matching, comment toggling, format-on-save (delegates to `ktlint`/`google-java-format` in rootfs, reuses the PTY layer).
- Undo/redo with 500-step cap + autosave journal every 2 s and on blur (L3: never lose the typed sentence).
- File size policy: > 2 MB → read-only preview with warning (RSS guard), configurable.
- Symbols pane: from LSP `documentSymbol` when available; fallback = ripgrep-based index (§7.4).
- Diff editor: side-by-side/unified, intra-line highlights, per-hunk apply/revert, word-level diff (Myers) — reused by git review and agent changes.
- Go-to-definition / find-references / rename via LSP bridge; degraded mode = workspace symbol search.

### 7.3 IME engineering (the make-or-break for a *mobile* IDE)

- Custom accessory bar above keyboard: Tab, Esc, Ctrl (sticky modifier + chord overlay), arrows, `|`, `/`, `$`, `{}`, quotes, paste-clipboard-history, "send to terminal" toggle. Ctrl must work as chord (`Ctrl+C` kills foreground process in terminal) — implement by translating accessory chords into the PTY byte stream.
- Hardware keyboard & mouse: full modifier support, `Ctrl+Shift+P`-style palette chords, right-click context menus; declare `keyboardHidden` configs; support Samsung DeX/desktop-mode windowing (free-form windows, resize).
- Fullscreen/focus mode, keyboard height animation sync (WindowInsets ime animation), and "editor-only" landscape mode that hides side panels below 400 dp width.
- Text input latency budget: keystroke→pixels < 100 ms including LSP debounce; all completion requests cancel-in-flight per keystroke.

### 7.4 Language intelligence bridge (M2)

- Serves `kotlin-language-server` (JVM — suspend when idle; expect heavy RSS, Tier-L may restrict to "on-demand only"), `clangd` (deb package), `jdt.ls` (very heavy — Tier-H only, warn), `json-language-server`/`vscode-jsonrpc` (node — only if node layer installed), `lemminx` for XML (Java, moderate).
- Lifecycle policy: start on first `textDocument/didOpen` of matching type, idle-kill after 10 min, SIGSTOP during builds, crash → auto-restart once, then degrade with a visible banner.
- Diagnostics pipeline: LSP → Room journal → editor underlines + Problems panel peeking to build errors from Gradle output too (single Problems surface from two sources).
- Fallback indexing: ripgrep + lightweight regex symbol scan for go-to-file/symbol when LSP is unavailable (3 GB reality).

---

## 8. Terminal

### 8.1 Decision gate M0.5 — terminal core
Evaluate vendoring GPL components (termux terminal-emulator/terminal-view are proven) vs. in-house VT100-lite. Recommendation: **in-house core (~1,500 lines: parser + screen buffer + View renderer)**, because we need tmux re-attach, accessibility, scrollback caps tuned to RSS, and a Compose-friendly façade; GPL vendoring conflicts with the Play-track distribution story. Keep protocol test suite ported from public VT suites (vt100.net / esctest).

### 8.2 Architecture
- **PTY via JNI/C++**: `openpty`/`forkpty`, window-size ioctls, non-blocking reads, SIGWINCH on keyboard show. Child = `proot … bash -l` **inside tmux**: `tmux new -s <id>` then `tmux attach -t <id>`.
- **Sessions are first-class durable objects**: id, name, cwd, env snapshot, scrollback (ring buffer capped at configurable lines, default 8k on Tier-L / 20k on higher), created/last-active. Stored in Room + persisted scrollback to disk (rotating, compressed, max ~2 MB/session).
- **Session switcher UI**: list, create (shell/agent/git/node/python presets), rename, kill, "keep alive in background" (session persists even if UI closed — it's just tmux).
- **Crash story**: if the PTY process dies, UI shows "session detached — reattach" button; tmux server is the survivor.

### 8.3 Mobile-specific UX
- Accessory row shared with editor (§7.3): Ctrl chords, Esc, Tab, nav keys, quick commands (`./gradlew`, `git status`, `clear`, `exit`).
- Text selection → copy/share/send-to-editor ("open file at line"), tap URLs → in-app browser sheet.
- Zoom via pinch (font 8–28 px), persisted per session.
- Pipe output to a file; long-output "pause output" button (SIGSTOP the reader, NOT the child — keeps the pipe honest on resume with catch-up flush).
- Performance: renderer draws glyphs on a single Canvas with per-line run caching; no recomposition of surrounding Compose UI on output (isolate terminal view in its own composable subtree with stable keys); 10 MB/s output budget with adaptive coalescing (drop frames, never block the pty reader thread — grows the ring buffer instead).

---

## 9. File Explorer & Project Model

### 9.1 Project model
- **Workspace = a rootfs-visible directory** (`~/projects/<name>` by default; optionally under shared storage when the user grants it, see §14.2).
- Project detection: `.git` presence, `settings.gradle*`/`build.gradle*` (Android/Gradle), `package.json`, `pyproject.toml`, `.forge/project.json` overrides.
- Recent workspaces, pinned, per-workspace state (open tabs, cursor positions, expanded tree nodes, last build config) — persisted, restored idempotently.

### 9.2 Explorer features (M1)
- Virtualized tree with lazy directory streaming (children loaded in pages; 1,000+ entry dirs never block).
- Multi-select (long-press), create/rename/delete/move (with undo for delete via trash dir `.forge/trash/`), sort modes, hidden-files toggle, filter by extension, file size/date columns in list view.
- **Git status overlays** from `git status --porcelain` + inotify-driven refresh (debounced 400 ms, coalesced).
- Storage usage bar per workspace (build dirs are the hog — one-tap "clean build outputs").
- Share via SAF/`ACTION_SEND`, "open with", download to shared Downloads dir.
- Search: ripgrep whole workspace, regex, file globs, preview with hits, replace-all with diff preview and per-file confirm.

### 9.3 Safety rails
- Destructive ops (delete, replace-all, `rm` from agent) route through the permission broker (§10.4) with clear paths.
- No symlink traversal outside workspace root without explicit consent.
- Binary files: guarded open (hex preview ≤ 1 MB).

---

## 10. Agentic Coding Host (run ANY CLI agent)

### 10.1 Concept
Rather than writing a bespoke agent, Forge is a **host** for the CLI agents users already trust — Claude Code, OpenCode, Codex CLI, Aider, Crush, "DeepSeek harness"es, etc. The host provides what those CLIs lack on a phone: managed lifecycle, streaming UI, a permission broker, checkpoints, and integration with the editor/diff/build systems.

```
UI (Agent panel)                 Backend (agentd)                  rootfs
┌──────────────┐   WS    ┌──────────────────┐   pty/jsonl   ┌─────────────────┐
│ transcript   │◀──────▶│ session supervisor │────────────▶│ claude / opencode│
│ diffs+apply  │ events │ adapter (per CLI)  │◀────────────│ --output-format  │
│ permission   │        │ journal + resume   │  hooks       │ stream-json …   │
│ queue/cancel │        │ checkpoint store   │  guard.sh ──▶│ PreToolUse hook │
└──────────────┘        └──────────────────┘              └─────────────────┘
```

### 10.2 Adapter tiers

| Tier | CLIs | Mechanism |
|---|---|---|
| **Structured** | Claude Code (`claude -p … --output-format stream-json --verbose`), OpenCode (`opencode run --print-logs` / `opencode serve`), Codex (`codex exec --json`), Aider (`--json`) | parse JSONL into normalized `AgentEvent` (message, tool_use, tool_result, diff, usage, error) |
| **Hook-mediated** | Claude Code (`PreToolUse`/`PostToolUse` hooks), OpenCode (permission config) | host installs a `guard.sh` that POSTs to the backend; backend raises a UI prompt and exits 0/2 — the CLI blocks until the human answers. **This is the permission story.** |
| **Generic PTY** | any TUI agent (Crush, gemini-cli, qwen-code, deepseek CLIs…) | full terminal embedding + transcript capture via screen-scrape; no structured events, but everything else (permissions via keywords, checkpoints, kill) still works |

Install flow for a CLI: `agentd install <name>` runs a rootfs recipe (npm/pip/curl installer) inside the environment — same mechanism as tool plugins (§11.4). Users can also point at any binary already in the rootfs ("custom agent" = command + args + event parser profile).

### 10.3 Session model
- Session = {cli, args, cwd, model config, env, created, status, pid, journalPath, checkpointIds[]}.
- **Journal**: every normalized event appended as JSONL to `<ws>/.forge/agents/<sessionId>.jsonl` → transcript survives crash; replay on reattach (`since=seq`).
- **Resume**: on UI/backend death, if the child is alive → re-adopt (tail its output again via the pty/pipe held by a detached runner); if dead → offer CLI-native resume (`claude --resume <id>`, `opencode` session id, `aider --restore-chat-history`). The runner that owns the child is **detached from the UI process** (spawned by backend; backend itself can die and the child keeps running under init, with output to the journal file — we re-attach by reopening the log).
- Concurrency: Resource Governor allows one agent session by default; "parallel agents" (worktrees, 2–3 sessions) is Tier-M+ opt-in and a late roadmap item (it multiplies RAM risk).

### 10.4 Permission broker
- Policy file (editable): `allow` globs (e.g. `git status`, `./gradlew *`), `ask` rules, `deny` (e.g. `rm -rf /`, `curl … | sh`, writes outside workspace, keystore access, network egress commands), `auto-approve small diffs (< 3 files, < 100 lines)` toggle.
- Enforcement points: (1) hook `guard.sh` for structured agents; (2) generic PTY mode = prompt-*watcher* (regex on output like "Allow command?" with number-keyed answers) — best-effort, clearly labeled; (3) our own tool calls never bypass the broker.
- Every decision (allow/deny + timestamp + rule) journaled → "Agent audit" screen. A prominent **STOP** button (SIGTERM→SIGKILL escalation, 3 s grace) + "rollback to checkpoint".

### 10.5 Checkpoints & rollback
- Before each agent *turn* (and before any agent-issued build), snapshot the workspace's dirty files into `.forge/checkpoints/<id>/` (hardlinks where the fs allows → cheap; size-capped, LRU evicted at 500 MB), plus `git stash create`-equivalent if the workspace is a repo.
- UI: checkpoint list per session, "restore" (copy back with journal of what changed), "diff vs checkpoint" reusing the diff editor.
- Guarantee: a runaway agent max costs one checkpoint's diff, which is reviewable and revertible in two taps.

### 10.6 Context & UX panel
- Panel layout: session list | transcript (virtualized, code blocks with syntax highlight, collapsible tool calls, token/cost meter) | permission dock (modal-ish, blocking, never auto-dismissing mid-critical-path) | diff summary card.
- "Attach context": current file, selection, open tabs, build error, terminal tail, git diff — one-tap chips inserted into the prompt.
- Prompt composer with slash-commands passthrough, history, queue-while-running.
- Agent + build integration: "fix build errors" action auto-attaches last build log; agent bash commands run inside the same proot env (so `./gradlew` just works).
- Accessibility: all agent events also land in a text log a screen reader can follow; permission prompts are focus-trapping dialogs with clear button labels.

---

## 11. Plugin System & Marketplace

### 11.1 Honest scope: not the VS Code extension API
A VS Code-compatible extension host (Node + Electron APIs) is out of reach on this platform and RAM class. We implement a **documented subset** and call it that, plainly, in docs.

### 11.2 Supported plugin kinds (M4)

| Kind | Source format | What it does |
|---|---|---|
| Theme | VSCode `*.json` color theme (dark/light) | converted to our M3 token set; importer from Open VSX |
| Icon theme | VSCode icon theme (minimal subset) | file icons in explorer/tabs |
| Keybindings | VSCode `keybindings.json` subset (when-clauses: `editorFocus`, `terminalFocus`, `isMac=false`) | bound to our command registry |
| Snippets | VSCode snippet JSON | editor completion source |
| Grammar | TextMate `.tmLanguage.json` plist subset | highlighting for "long tail" languages |
| Tool | Forge manifest yaml (§5.4 style) | installs LSP/runtime/formatter/CLI into the rootfs; appears in tool registry |
| Repo template | Forge manifest | "new project from template" entries (Compose, KMP-lite, CLI, lib) |

### 11.3 Plugin format & sandboxing

```yaml
# plugin.yaml
id: com.example.kotlin-snippets
version: 1.4.0
kind: snippet
minApp: 1.0
entry: snippets/kotlin.json
capabilities: [editor.completion]
```

- Install = manifest + assets in a versioned dir; registry in Room; update = replace dir atomically (dir swap, then DB flip). Uninstall = delete.
- **No third-party native code in v1** (security + review burden). Tool plugins may install *known-good* packages via our curated recipes only. This is a deliberate trust boundary; revisit with a real sandbox (separate uid via `android:process` + no fs write outside plugin dir) post-1.0.
- Permissions model per plugin capability, grant UI, revoke in settings.

### 11.4 Marketplace
- Catalog = signed JSON index (self-hosted + GitHub Releases assets), browsable in-app (search, categories, screenshots, size, verified badge), install/update/rollback.
- **Open VSX mirror**: a backend job imports metadata (extension id, version, publisher, license) for themes/grammars/keybindings/snippets and auto-converts compatible assets into Forge plugins. This gives the "npm-registry-style breadth" moment without pretending to run VS Code extensions. Legal: respect licenses, display them, don't redistribute incompatible binaries.
- Offline mode: catalog cache + install from file (`plugin.fpk` zip).

---

## 12. Adaptive UI & Design System

### 12.1 Form factors
- **Phone (compact, < 600 dp):** bottom navigation / mode switcher (Files · Edit · Terminal · Agent · Build), single-pane with full-screen panel transitions; editor gets priority when a file is open (back returns to tree).
- **Foldable/tablet (medium-expanded):** list-detail — explorer + editor side by side; terminal and agent as bottom sheets or second column; use `WindowSizeClass` + fold posture (`FoldingFeature` OCULUS/OCCLUDED) to avoid the hinge.
- **Desktop mode / large (expanded, ≥ 840 dp):** three-column IDE layout (explorer | editors stack | utility rail), resizable splitters, keyboard-first (command palette, all chords), external monitor support.
- **TV/Auto/XR: out of scope.**

### 12.2 Design system
- Material 3 with dynamic color, full dark/light, per-editor themes (editor colors independent from app theme).
- Density/typography scale tuned for one-hand use: primary targets ≥ 48 dp; long-press for secondary actions.
- Edge-to-edge with proper insets (status/nav/IME/cutout), gesture-nav friendly; back handling per Navigation-3 with predictive-back.
- Motion: shared-axis transitions ≤ 200 ms; no layout animation on the editor/terminal hot paths.
- Theming from plugins (§11) maps into the same token graph, so plugin themes and app themes stay coherent.

### 12.3 Jank & startup budgets (gates, not wishes)
| Metric | Target (Tier-L phone) |
|---|---|
| Cold start → interactive | ≤ 2.5 s (Baseline Profiles + no IO on main) |
| Open 50k-line file | ≤ 800 ms to first paint, scroll 60 fps |
| Terminal output scroll | 60 fps at 10 MB/s with frame coalescing |
| Panel switch | ≤ 120 ms |
| ANR/crash rate | 0 crashes from UI process under lab stress |
| Frame drops during build log streaming | < 1 % dropped frames |

---

## 13. Stability, Resumability & Performance Engineering

This chapter answers the user's hard requirement: **no lag, no crash, no restarts of the server** — by making every component disposable and every operation resumable.

### 13.1 Survival stack
1. **Foreground service** (`dataSync` type) for the backend, with a persistent, honest notification ("Forge server running · 2 terminals, 1 build"). Sticky. Battery-optimization exemption dialog with clear rationale (a dev server must not be reaped mid-build).
2. **Native watchdog process** (`:forge.watchdog`): heartbeats every 5 s into a shared file; missed 3 → kill+respawn backend with exponential backoff (1 s, 2 s, 4 s … cap 60 s); 5 consecutive crashes → stop respawning, surface a recoverable error state in the UI with diagnostics attached. Prevents crash-loop battery drain.
3. **Detached long-runners:** tmux server, gradle daemons, and agent CLIs are spawned `setsid` so they are *not* in the backend's process group; backend death does not kill them.
4. **Attach-or-recover on every subsystem:**
   - Terminal: `tmux attach-session -t <id>` (or `has-session` probe) → zero loss.
   - Build: tail `<id>.log` from disk + `gradle --status` → rebuild queued/running state; if the daemon survived, the *build itself* never even stopped.
   - Agent: re-adopt live child (reopen its output fd journal); if dead, offer CLI-native resume.
   - Editor: autosave journal every 2 s + on blur.
5. **Idempotent client:** UI assigns `requestId`s, dedupes, and replays missed events via `since=<seq>`; reconnect with jittered backoff, never blocking the UI thread.

### 13.2 Crash containment matrix

| Component crashes | Effect on UI | Recovery |
|---|---|---|
| UI Activity | none (backend lives) | state restored from Room |
| Backend service | banner "reconnecting" | watchdog respawn ≤ 1–2 s; attach all subsystems |
| tmux/shell | session marked detached | reattach button; scrollback intact |
| Gradle daemon | build marked interrupted | retry/reattach; caches intact |
| Agent CLI | session marked interrupted | one-tap resume via CLI session id |
| LSP server | diagnostics pause banner | auto-restart once, then degrade to grep-index |
| Editor view | none | recreate from autosave journal |
| Watchdog | nothing | it's restarted by backend |

### 13.3 Memory watchdog (the OOM-prevention system)
- Backend samples `MemAvailable` + cgroup `memory.current` + per-process RSS every 2 s; maintains a 60 s EWMA.
- Thresholds: **green** > 600 MB, **amber** < 450 MB → suspend LSPs, cap terminal scrollback flush, suggest "free RAM" quick actions; **red** < 250 MB → pause builds between tasks, evict editor undo journals, shed plugin services, notify user with reason.
- Kill-prevention for our own processes: `onTrimMemory` handlers aggressively drop caches; editor caps undo stack; Coil caches shrink; terminal ring buffers flush to disk.
- The governor enforces **one heavy lease** (build XOR agent XOR LSP-index) with an explicit user override ("I know what I'm doing") that switches to lite mode rather than refusing.

### 13.4 Performance engineering practice
- Baseline Profiles for startup + editor; JankStats in dogfood builds; frame-lookback CI gate (macrobenchmark: `startup`, `scrollEditor`, `terminalFlood`, `panelSwitch`).
- LeakCanary in debug; `StrictMode` disk/network on main in debug; BlockCanary-style main-thread IO watchdog reporting top offenders to a dev screen.
- Allocation hot spots reviewed in editor scroll path (preallocated line painters, no boxing in layout math).
- Strict library budget: every dependency justified by RAM (this is why Coil over Glide choices etc. get reviewed for cache ceilings).

---

## 14. Security, Privacy & Compliance

### 14.1 Sandboxing
- Backend binds **127.0.0.1 only** with a random port + per-boot bearer token in `EncryptedSharedPreferences`/DataStore. No LAN exposure by default; explicit opt-in for LAN (phone-as-server for tablets) with token rotation.
- Agent CLIs run with the app's uid (unavoidable without root) — mitigations: permission broker, denylist, network-egress warnings, and honest docs stating agents run with app privileges. Do not oversell security; document it.
- Editor never renders untrusted HTML; link opening goes through a confirm sheet.

### 14.2 Storage & Play policy
- Default: everything in app-private dirs (no permission needed). Optional "Grant access to shared storage" via SAF tree or `MANAGE_EXTERNAL_STORAGE` for power users.
- **Distribution policy note (important):** Google Play restricts apps that download executable code. Forge downloads a *user-invoked development toolchain* (like Termux's bootstrap), which sits in a gray area — the plan assumes **F-Droid + GitHub Releases as primary channels**, Play as a secondary track with policy review (or Play Asset Delivery–delivered toolchain if compliant review passes). License hygiene: aapt2/d8/Apache-2.0 tools, proot GPL (source + offer), Gradle Apache-2.0, Android SDK license acceptance flow, Open VSX assets per-extension licenses displayed in-app.

### 14.3 Secrets
- Keystores, SSH keys, API tokens (agent models) in encrypted storage; Keystore-backed master key; biometric unlock option; redaction in logs and transcripts (`guard.sh` scrubs patterns in persisted journals).
- Agent transcripts may contain sensitive code → default no-telemetry; opt-in crash reports only (no workspace content).

---

## 15. Packaging & Delivery

- **Per-ABI splits** (`arm64-v8a`, `armeabi-v7a`, `x86_64`) — the 32-bit ABI matters for old 3 GB phones.
- APK size discipline: core app ≤ 45 MB (native supervisor + proot + base assets); toolchain via asset packs/in-app downloader.
- Distribution matrix: GitHub Releases (universal + per-ABI), F-Droid (reproducible builds where possible), Play (internal → closed → open testing; policy review for toolchain delivery).
- Update strategy: in-app updater for the environment layers (xdelta3 deltas, resume-on-reboot) separate from app updates; environment version pinned in `forge-toolchain.yaml`; compatibility checks before apply.
- Reproducibility: signed builds, published checksums, changelog automation from conventional commits.

---

## 16. Testing Strategy & Quality Gates

### 16.1 Test pyramid
- **Unit (JVM):** protocol serialization, governor policy, tier resolution, permission policy engine, checkpoint store, VT parser (esctest port), diff engine, TextMate converter, theme converter.
- **Integration:** backend server + fake rootfs (no proot) — WS contract tests, attach/recover flows, journal replay, build log parser (golden corpus of real gradle outputs incl. OOM traces).
- **UI (Compose):** screen tests for explorer/editor/terminal/agent panels; accessibility checks (talkback traversal, touch targets, contrast).
- **Macrobenchmark:** startup, editor scroll, terminal flood, panel switch, large-tree expansion — with `FrameTiming` gates on a real Tier-L device.
- **End-to-end on device lab:** the real deal — create project → edit → build → sign → install → logcat → agent makes a change → checkpoint → rollback. Runs on a device matrix (at least: one 3 GB Exynos/MediaTek phone, one 4–6 GB, one 8 GB+, one foldable).

### 16.2 Device lab (must-have, cheap)
- 4–6 physical phones on a shelf + ADB-over-Wi-Fi farm scripted from CI; thermal soak test (20 consecutive builds), memory soak (24 h agent+terminal idle), LMK stress (background other apps during build).
- Crash/bug funnel: Crashlytics (opt-in) + in-app bug report that bundles device profile, governor events, backend log tail (scrubbed).

### 16.3 Definition-of-done gates per milestone
- 0 P0/P1 bugs, crash-free sessions ≥ 99.5 % in lab, benchmark deltas within 10 % of baseline, memory ceilings from §3 measured and reported, docs/changelog updated.

---

## 17. Roadmap — 18 Months

Effort in dev-weeks for 1–2 devs; each milestone has hard exit criteria.

### S0 — De-risk spike (Mo 0–1.5) · ~7 weeks
**Goal: prove the platform before building the product.**
- proot Debian rootfs builder script; install JDK + SDK cmdline-tools + build-tools.
- **Gate G1:** template Compose app `assembleDebug` succeeds **on a 3 GB device**; record wall time & peak RSS.
- **Gate G2:** `aapt2/d8/zipalign/apksigner` all run under proot on ≥ 2 vendor devices.
- **Gate G3:** tmux session survives UI-process kill; re-attach works.
- **Gate G4:** `claude` and `opencode` CLIs install and run in the rootfs (any model via key).
- **Gate G5:** inotify sees proot-side file changes from the app side.
- Decide editor core + terminal core (M0.5 gates, §7.1/§8.1).
- **Kill criteria:** if G1/G2 fail on stock kernels of both major vendor devices → pivot to bionic ports (scope +16 weeks) or cloud-build hybrid.

### M1 — Alpha IDE (Mo 1.5–5) · ~14 weeks
Feature scope: workspace/project model, file explorer, editor (tabs, lexers, undo, find), terminal (sessions, tmux, accessory row), command palette, go-to-file, build orchestration (debug), artifacts + install + logcat, settings/device-profile screen, backend + watchdog + governor v1, reconnect flows.
**Exit:** on a 3 GB device: create template → edit → `assembleDebug` → install → run, end-to-end, ≤ 3 user-blocking bugs; cold start ≤ 2.5 s; 60 fps editor scroll on 50k lines.

### M2 — Language & VCS (Mo 5–8) · ~13 weeks
Git UI (status/diff/stage/commit/pull/push/branch), LSP bridge + Problems panel (Kotlin/C/JSON/XML), ripgrep search & replace, editor diffs & minimap, split view, device-profile tiers wired into every policy, build modes (typecheck/release-signed), failure intelligence ("retry safer").
**Exit:** LSP-driven go-to-def works on-device for Kotlin; signed release APK produced & verified; governor demonstrably keeps MemAvailable > red line in soak.

### M3 — Agentic host (Mo 8–12) · ~17 weeks
agentd + adapters (Claude Code structured+hooks, OpenCode, Codex, Aider; generic PTY), permission broker + audit, checkpoints/rollback, diff review UI, journal/replay, resume flows, context chips, token/cost meter, multi-session list.
**Exit:** "fix this bug" agent session on a 3 GB device: prompt → diff → user applies selective hunks → build passes → rollback verified; audit log complete; agent survives UI+backend death with re-attach.

### M4 — Hardening & adaptive (Mo 12–15) · ~13 weeks
Plugin system (themes/keybindings/snippets/grammars/tools) + catalog + Open VSX importer, adaptive layouts (foldable/tablet/desktop mode, DeX), bionic-native swap of the hottest tools (JDK first) behind tool-registry switch, performance sprint (jank gates, scroll, startup), i18n, docs site, permissions/policy review, thermal/battery polish.
**Exit:** plugin installs from catalog; desktop mode usable; measurable build-time improvement from bionic swaps; benchmark gates green on Tier-L.

### M5 — Beta & launch (Mo 15–18) · ~13 weeks
Closed beta (TestFlight-equivalent: Play internal + direct APK), device-lab soak, template gallery stability, onboarding polish, update system for env layers, support flows, marketing/docs, public release (F-Droid/GitHub primary, Play if cleared), post-launch plan (cloud build, AAB, parallel agents).
**Exit:** public builds out; crash-free ≥ 99.5 %; documented "known limits" honest list; CI green across device matrix.

### Cross-cutting workstreams (continuous)
- Skills to load during implementation: `claude-android-ninja` (module/Hilt/Gradle patterns), `compose-expert` (editor/terminal Compose façades), `adaptive` (size classes, foldables), `navigation-3`, `styles`, `edge-to-edge`, `testing-setup`, `r8-analyzer` (release pipeline), `android-profiler` (perf gates).
- Weekly: benchmark + memory report; monthly: risk-register review.

---

## 18. Risk Register

| # | Risk | Sev | Mitigation |
|---|---|---|---|
| R1 | aapt2/d8/Gradle unreliable under proot on some vendor kernels | **High** | S0 gates G1–G2 on ≥ 2 vendors; bionic-port fallback; cloud-build seam |
| R2 | OOM kills during builds on 3 GB | High | tiers, in-process Kotlin, lite mode, governor, red-line watchdog, "retry safer" |
| R3 | UI lag from heavy streaming (logs/agent output) | High | journal-to-disk + tailing, virtualization everywhere, frame coalescing, jank CI |
| R4 | Solo-dev timeline slip | High | ruthless MVP (M1 ships with debug builds only), agents layer is reusable OSS, monthly shippable internal builds |
| R5 | Play policy blocks toolchain download | Med | F-Droid/GitHub primary; asset packs; compliance review before Play push |
| R6 | Licensing (GPL vendoring, SDK terms) | Med | in-house cores where it matters; per-asset license display; proot source offer |
| R7 | Agent CLIs change their CLIs/flags | Med | adapter layer with capability probing + version pinning + graceful degrade to generic PTY |
| R8 | 32 GB storage insufficient for toolchain | Med | on-demand layers, NDK optional, cleanup tools, cloud-build later |
| R9 | Android 15/16+ exec & background restrictions tighten | Med | test on canaries each release; foreground-service types kept current; rooted-path fallbacks |
| R10 | Battery/thermal complaints | Med | thermal governor, honest notifications, pause-between-tasks, "eco mode" |

---

## 19. Appendices

### 19.1 Directory layout on device
```
/sdcard/Android/data/<pkg>/files/
├─ rootfs-<abi>/          # debian + langs + sdk + ndk (on-demand layers)
├─ projects/<workspace>/  # user projects (own git repos, .forge state)
├─ forge-home/            # ~/.gradle, ~/.cache, ~/.claude, ~/.config/opencode
├─ toolchain/             # downloaded layer packs + manifests + deltas
└─ forge-state/           # journals, checkpoints, build logs, buffers (internal)
```

### 19.2 Core data model (Room)
```
Workspace(id, rootUri, displayName, kind, gitBranch, lastOpenedAt, stateJson)
TerminalSession(id, workspaceId, name, tmuxTarget, cwd, cols, rows, scrollbackPath, status)
BuildJob(id, workspaceId, task, mode, status, startedAt, finishedAt, logPath, profile, artifactsJson)
AgentSession(id, workspaceId, cliName, argsJson, status, pid?, journalPath, checkpointIds, usageJson)
Checkpoint(id, agentSessionId, createdAt, filesJson, sizeBytes, gitRef?)
PermissionDecision(id, ts, sessionId?, tool, command, decision, rule, actor)
Plugin(id, kind, version, source, installPath, capabilitiesJson, enabled)
DeviceProfile(totalRamMb, tier, abi, thermalState, storageClass, measuredAt)
```

### 19.3 Build-mode quick reference (user-facing docs)
Debug-fast → Typecheck → Release-signed; lite auto-engagement rules; "why was my build paused?" explanations tied to governor events.

### 19.4 First-run checklist (wizard)
1. ABI + space check (≥ 4.5 GB free recommended) → 2. download layers (Wi-Fi guard, resumable, progress with ETA) → 3. warm template build in background → 4. agent CLI install (optional, keys skipped) → 5. quick tour (gestures, accessory row, agent panel) → 6. open template project.

### 19.5 Open questions for S0 output
- proot perf tax on Kotlin compile: measure vs. bionic JDK prototype.
- `configuration-cache` compatibility with chosen AGP on this RAM tier.
- Exact LMK behavior per vendor (OEM killers — Xiaomi/Huawei/Oppo need explicit user steps; document per-OEM guides).
- Whether `pm install` from shell uid is viable for sideloading on target OEM builds (else use session installer intent).

---

*End of plan. Next action: run Spike S0, gates G1–G5, and bring results back before committing M1 scope.*

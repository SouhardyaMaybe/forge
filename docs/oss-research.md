# Open-source landscape research

Survey taken 2026-10-09 while choosing Forge's foundations. The conclusion: no single
project gives us the whole product, so Forge is a from-scratch app that borrows
*architecture and lessons* from these projects rather than vendoring their code.

## Candidates

### AndroidIDE (`AndroidIDEOfficial/AndroidIDE`) — GPL-3.0, archived 2024
The closest prior art: a real Gradle-based Android IDE **that runs on Android**.

- Ships a Termux-based terminal, JDK 11/17 via `idesetup`, Gradle builds, Java + XML
  language servers, log reader, git, and a UI designer.
- Uses Rosemoe's CodeEditor for editing and Termux's terminal emulator for the shell.
- **Archived, unmaintained.** Its successor is *Code on the Go* (`mcikadu-dev/CodeOnTheGo`).
- Lessons we take: the `idesetup` provisioning flow, the "install build tools once"
  model, and the proof that glibc SDK binaries + JDK 17 can be made to work on-device.
- Lessons we avoid: no NDK (glibc NDK binaries are hard), Kotlin LSP missing,
  process/lifecycle fragility, single-process design.

### Code on the Go (`mcikadu-dev/CodeOnTheGo`)
Actively maintained successor to AndroidIDE, weekly updates. Useful reference for
toolchain packaging (how they ship JDK/SDK for Android) and for realistic
build-time expectations on mid-range hardware.

### Acode (`Acode-Foundation/Acode`, fork `Acode2`) — MIT, very active
The most popular Android code editor (7.3k stars, 478 plugins).

- **Web-based**: Ace editor inside a WebView, plugins in JavaScript.
- Excellent plugin *distribution* story (plugin.json manifest, community store,
  encrypted per-plugin secrets) that we mirror conceptually in `plugin.yaml`
  (see plan §11) — but its runtime (WebView + JS) is exactly what we reject
  for the 3 GB / 60 fps target.

### MobileIDE (`scto/MobileIDE`)
A Compose-based IDE with a custom APK builder (aapt2/D8 pipeline in `:core:apk-builder`).
Evidence that a fully-native Compose IDE is viable; useful reference for the
"compile without full AGP" path that our Lite build mode may reuse.

### Sora Editor / Rosemoe `code-editor` — Apache-2.0
Mature, virtualized native Android editor (used by AndroidIDE and others).
Leading candidate for the editor core (plan §7.1 decision gate M0.5).

### Termux + proot-distro — GPL
- Terminal emulator view/parser components are battle-tested; license is GPL, so
  Forge keeps an in-house VT100 core (plan §8.1) to stay Apache-2.0 friendly.
- proot-distro is the model for our Debian userland under proot; proot itself is GPL
  and is shipped as a binary dependency with source available, which is compatible
  with distributing Forge under Apache-2.0.

## What Forge adopts from where

| Source | Adopted |
|---|---|
| AndroidIDE / Code on the Go | Toolchain provisioning model (`scripts/idesetup.sh`), build-tools-on-device proof |
| Acode | Plugin manifest/store concepts, onboarding UX ideas |
| Sora Editor | Editor core (pending M0.5 gate) |
| Termux/proot-distro | Environment strategy; terminal UX patterns (accessory row, sessions) |
| VS Code / Open VSX | Plugin asset formats (themes, TextMate grammars, keybindings, snippets) |

## License hygiene

Forge is Apache-2.0. Any GPL component must stay a *separate binary* (proot) or be
reimplemented (terminal core). Per-asset licenses from Open VSX are displayed in-app
when plugins are installed.

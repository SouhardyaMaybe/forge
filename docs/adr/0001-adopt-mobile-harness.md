# ADR 0001: Adopt Mobile Harness (MIT) as Forge's base

- **Status:** Accepted
- **Date:** 2026-10-10
- **Supersedes:** the from-scratch scaffold committed 2026-10-09 (retained in git history under `main` before this commit)

## Context

The original Forge plan called for a from-scratch native Compose IDE on a PRoot
backend. We built the skeleton (backend service, REST/WS protocol, native PTY,
file tree, terminal client, CI) and got a green APK in ~12 CI iterations — but
almost all of that time went to infrastructure (AGP 9 migration, CI memory
ceilings, dexing), and the resulting app was still months of work away from the
user's actual requirements: an agentic IDE where Claude Code / DeepSeek Harness
/ OpenCode-style CLIs are first-class citizens, with real Android builds.

Research into the OSS landscape then found three projects that overlap heavily
with the requirements:

| Project | License | What it has | Why not the base |
|---|---|---|---|
| **Mobile Harness** (techjarves) | **MIT** | Compose UI + PRoot Ubuntu userspace + **isolated agent drivers for Claude Code, DeepSeek Harness and Antigravity CLI** + **on-device Android builds** (JDK 17, SDK 36, build-tools, Gradle, offline Maven) + diff review + web preview + keystore-encrypted keys | — |
| Android Code Studio (AndroidCSOfficial) | GPL-3.0 | The live AndroidIDE successor: full Gradle builds on device, Kotlin LSP, UI designer, AI agent | GPL + a Java/legacy-architecture codebase; its *toolchain* (acs-androidtools, acs-language-servers) is a useful reference, but forking the IDE is heavier |
| Acode | MIT | Polished editor + 478-plugin store | WebView/Ace-based — exactly the architecture we reject for a 3 GB target |

Mobile Harness matched the requirements so closely — including the
"download any agentic CLI" hosting model, via per-agent runtime bundles with
isolated drivers and resumable conversations — that continuing from scratch
would have meant re-implementing it, worse.

## Decision

**Adopt Mobile Harness v1.0.6 as Forge's base.**

Concretely:

1. Replace our scaffold with the upstream `main` tree (single `app/` module),
   keeping our CI workflow, docs and plan.
2. Rebrand the package `com.jarves.mh` → `com.forge.ide` (including JNI
   symbols) and the applicationId to `com.forge.ide`.
3. Re-license the repository to MIT with dual attribution, per upstream's
   license.
4. Keep CI building the **online** edition: `assembleOnlineDebug`, fetching the
   runtime bundle tarballs referenced by `dist/runtime-bundles/manifest.json`
   from upstream GitHub releases.
5. Layer our roadmap on top: Resource Governor + memory tiers, plugin
   marketplace, adaptive multi-pane UI, durable checkpoints.

## Consequences

**Gained immediately:** a working product with the hard parts already solved —
the PRoot userspace, three agent integrations, on-device Android builds, and a
polished Compose UI. Weeks of work collapse into "adopt and extend".

**Accepted trade-offs:**
- **AGP 8 baseline** (`compileSdk 36`, `kotlinOptions`, hardcoded dependency
  versions) instead of our AGP 9 + version-catalog scaffold. Migrating to AGP 9
  built-in Kotlin is a follow-up task, not a prerequisite.
- **`targetSdk 28`** for the direct APK, which upstream chose deliberately to
  keep the proven PRoot execution path. Raising it is a separate, testable
  project touching the runtime launcher.
- **Single module, 28k lines, large files** (`PocketDevApp.kt` is 7.4k lines).
  Our multi-module plan (core/feature split) becomes a refactoring roadmap.
- **ARM64 only** (upstream's PRoot + runtime bundles are arm64); 32-bit devices
  are out of scope until someone ports the bundles.
- **Upstream dependency for runtime bundles**: the Ubuntu rootfs, JDK, SDK and
  agent CLIs are release assets. We should mirror them into our own releases
  before publishing widely, and the offline edition still needs local tarballs.

**License obligations:** MIT requires preserving the copyright notice — done in
`LICENSE`. GPL contamination is avoided because we chose the MIT base; if we
later import code from Android Code Studio (GPL), that import must be isolated
and the licensing consequences reviewed.

## Alternatives considered

- **Keep building from scratch and adopt only pieces.** Rejected: the agent
  runtime bridges and the Android build bundle are the expensive parts and are
  already done, in a shipping product, under a permissive license.
- **Fork Android Code Studio instead.** Rejected for now: GPL, legacy
  architecture, and its value (Kotlin LSP, UI designer) is complementary rather
  than foundational. Its toolchain repos remain references.
- **Fork Acode.** Rejected: WebView architecture fails the 3 GB / no-lag
  requirement; its plugin store design is worth copying, not its shell.

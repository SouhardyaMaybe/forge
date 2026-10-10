# Forge

**A native, agentic IDE for Android** — code on your phone with real Linux tooling, hosted coding agents, and on-device Gradle builds. No root required.

> **Built on [Mobile Harness](https://github.com/techjarves/Mobile-Harness)** (MIT) — see [`docs/adr/0001-adopt-mobile-harness.md`](docs/adr/0001-adopt-mobile-harness.md) for what we inherited and why.

## What's already here

Forge is not a blank slate. The base product (upstream Mobile Harness v1.0.6) already ships:

| Capability | Status |
|---|---|
| Jetpack Compose UI: file browser, editor, terminal, diff review, web preview | ✅ inherited |
| PRoot Ubuntu 20.04 ARM64 userspace, app-private, no root | ✅ inherited |
| Cached/isolated agent drivers: **Claude Code**, **DeepSeek Harness (DSH)**, **Antigravity CLI** with resumable conversations | ✅ inherited |
| On-device **Android builds**: Temurin JDK 17, Android SDK 36, build-tools 35, Gradle 8.14, offline Maven repo, AAPT2 override | ✅ inherited |
| Keystore-backed AES-256-GCM credential storage, logcat reader, APK install/run | ✅ inherited |
| ARM64, Android 9+ (API 28), online (87 MB) / offline (888 MB) editions, F-Droid metadata | ✅ inherited |

## What Forge adds on top

Our roadmap layers the differentiators that upstream does not have yet — see
[`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for the full plan:

1. **Resource Governor + memory tiers** — the plan's 3 GB / 6 GB / 8 GB device
   profiles, serialising builds/agents/LSPs so a 3 GB phone never OOMs.
2. **Plugin system** — a curated marketplace (themes, grammars, snippets,
   keybindings, tool installs) modelled on Open VSX + Acode's plugin store.
3. **Adaptive multi-pane UI** — foldables, tablets and desktop mode, not just
   the phone layout.
4. **Pipeline hardening** — durable sessions, checkpoints and rollback for
   every agent run, and the S0 device-gate scripts (`scripts/`).

## Building

CI is the source of truth — GitHub Actions assembles the `onlineDebug` APK and
uploads it as an artifact. The build fetches the runtime bundles referenced by
`dist/runtime-bundles/manifest.json` from upstream releases.

Local build (needs Android SDK + NDK 28.2):

```bash
./gradlew :app:assembleOnlineDebug     # online edition
./gradlew :app:assembleOfflineDebug    # bundles everything (needs the tarballs)
```

## License

MIT, with attribution to the Mobile Harness authors. See [`LICENSE`](LICENSE).

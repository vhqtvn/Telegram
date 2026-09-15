# Telegram for Android — Mission & Product Rules

This repository builds **Telegram for Android** — the official FOSS Android client for the Telegram messenger (package `org.telegram.messenger`). This checkout tracks upstream DrKLO/Telegram releases; local work focuses on keeping the build healthy and applying repo-specific changes on top of upstream.

## Must read

- `README.md` (upstream) — compilation guide, toolchain pins, reproducible-build notes
- `CLAUDE.md` (repo root) — rendered project summary + harness pointers
- `docs/planning/backlog.md` — current source of truth on local task status
- `.gitmodules` — every vendored dependency and where it lives

## Read when relevant (project-specific)

- `TMessagesProj_App/build.gradle` — flavors, signing, build variants
- `TMessagesProj/jni/CMakeLists.txt` + `Application.mk` — native build surface
- `Tools/` — release/CI helper scripts

## Mission

Keep this fork a healthy, buildable overlay on upstream Telegram for Android so a user can:

- install a working messenger APK built from this tree (`./gradlew` in Android Studio or CLI)
- receive upstream features/fixes by merging upstream releases with minimal conflict surface
- benefit from repo-specific local changes that stay small, documented, and rebasable

## Non-negotiable product rules

1. **Upstream tracking**
   - Upstream (DrKLO/Telegram) history is the base; local commits sit on top.
   - Never reformat, rename, or "clean up" upstream code that local work doesn't touch — it inflates merge conflict surface for the next upstream bump (e.g. the `update to 12.10.1 (7038)` commits).
   - Keep local changes minimal and localized; prefer additive edits over rewrites.

2. **Toolchain pins**
   - Android Studio 2025.1.4, Android SDK 36, NDK 27.2.12479018 (per upstream README).
   - Submodules are required: `git submodule update --init --recursive --depth=1` before any build.

3. **No real secrets in the tree**
   - The repo ships reproducible-build placeholders (dummy `release.keystore`, `google-services.json`, filled `BuildVars.java` values under `TMessagesProj/config` and friends).
   - Never commit real keystores, store/key passwords, or real API credentials; real values live in `gradle.properties` / local config only.

4. **Match the existing code idiom**
   - App code is Java, not Kotlin — follow the existing style of the file you're editing.
   - Native code is C/C++ under `TMessagesProj/jni`, built via CMake/NDK.

## Target architecture

Gradle **multi-project Android build** (monorepo shape).

### Apps

- `TMessagesProj_App` — the main messenger app (debug/release; standalone/beta variants)
- `TMessagesProj_AppHuawei` — Huawei flavor
- `TMessagesProj_AppHockeyApp` — HockeyApp-crash-reporting flavor
- `TMessagesProj_AppStandalone` — standalone (no Google services) flavor
- `TMessagesProj_AppTests` — UI/ instrumentation tests app

### Packages

- `TMessagesProj` — the core library module: nearly all UI + business logic (Java, `src/main/java/org/telegram/...`), resources, assets, and native code (`jni/`: `tgnet` native MTProto networking, `voip`, `tde2e`, `tlottie`, `ffmpeg`, sqlite, etc.; vendored deps under `jni/third_party/` are git submodules)
- `buildSrc` — Gradle build logic
- `Tools/` — release/CI helper scripts

## First release scope

N/A — this is an established upstream-tracked app, not a greenfield product. The standing local scope is: reproducible debug/release builds from this tree, plus the small set of changes tracked in `docs/planning/backlog.md`.

## Repo-level engineering defaults (project-specific)

- Build from the repo root with `./gradlew` (e.g. `assembleDebug` for the debug APK).
- Don't bump Gradle/AGP/NDK/SDK versions casually — upstream pins them for reproducible builds.
- `apkdiff.py` / `apkfrombundle.py` (repo root) are the upstream reproducibility-check helpers.

## Dataset / asset governance rules

Not applicable — no datasets or heavy assets are managed here.

## Component promotion and evaluation rules

Not applicable — no promotable models/analyzers.

## Preferred implementation order

Not applicable — work is driven by `docs/planning/backlog.md` and upstream release bumps, not a phased greenfield plan.

## Demo API authentication and routes

Not applicable — no demo API. Protocol surface is MTProto (`tgnet` native layer); API docs: https://core.telegram.org/api and https://core.telegram.org/mtproto.

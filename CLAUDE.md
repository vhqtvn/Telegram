# Telegram

Telegram messenger for Android — the official FOSS Android client (org.telegram.messenger). Tracks upstream DrKLO/Telegram releases; local work focuses on keeping the build healthy and applying repo-specific changes on top of upstream.

## Architecture

- Gradle multi-project Android build; core code lives in the TMessagesProj library module (Java UI + business logic, plus C/C++ JNI under TMessagesProj/jni: tgnet (native MTProto networking), voip, tde2e, tlottie, ffmpeg, sqlite, etc.).
- Thin app modules layer on top: TMessagesProj_App (main messenger, debug/release + standalone/beta flavors), TMessagesProj_AppHuawei, TMessagesProj_AppHockeyApp, TMessagesProj_AppTests.
- buildSrc holds Gradle build logic; third_party deps are git submodules (see .gitmodules: TMessagesProj/jni/third_party/{libvpx,dav1d,ffmpeg,openh264,libyuv,xiph/*}, jni/tlottie, lib/jlatexmath); Tools/ holds release/CI helper scripts.
- Key entry points: LaunchActivity / ApplicationLoader in TMessagesProj/src/main/java/org/telegram/; network layer is MTProto (ConnectionsManager via native code).
- Toolchain: Android Studio 2025.1.4, SDK 36, NDK 27.2.12479018; build via ./gradlew (assembleDebug etc.); reproducible-build placeholders live in TMessagesProj/config.

## How to work here

- Read `AGENTS.md` for the full harness rules (term-contract, shell hygiene,
  command hygiene, delegation, commit gate, memory model).
- Run all commands through `vh-agent-harness exec`. Do not rely on host-installed tooling.
- The coordinator is read-only; delegate all coding/research/git to specialists.
- Git mutations route through the `committer` subagent (gated-commit protocol). Pass only this session's explicit file list; a concurrently-dirty tree is normal and unrelated dirty files are excluded by the private-index gate. To revert a stray file, use `commit-gate.sh revert <paths>`.
- Keep scratch under `./tmp/` (repo-relative). Never absolute home-dir paths.

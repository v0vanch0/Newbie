# NimarkoMAX — native Android port of NimarkoGram to MAX

## What this repository contains

This is a source-code port **based on the actual NimarkoGram Android project** and the standalone Kolibri Kotlin/JNI MAX transport. It does **not** build or embed the Komet Flutter app.

The reproducible GitHub Actions workflow:
1. Fetches NimarkoGram at pinned upstream commit `a0eab5ca820509b6351d6e8af9b56a5247d25d63`.
2. Fetches Kolibri (MIT/Apache-2.0) at pinned upstream commit `a6cdce9e0e75d33aa0c398988d3b45e25c8908ab`.
3. Copies the Kotlin/JNI Kolibri binding into NimarkoGram and adds the native `MaxNativeClient` transport.
4. Injects a MAX Android entry screen into NimarkoGram's `LaunchActivity` and registers it in its standalone manifest.
5. Compiles `libkolibri_kotlin.so` with `cargo-ndk`, placing it in NimarkoGram's existing ARM64 JNI library directory.
6. Runs the NimarkoGram Gradle Android build. A successful CI job publishes **NimarkoMAX-native-arm64-apk**.

All of NimarkoGram's Android Java/Kotlin sources, its custom media/camera implementation and its Python/DEX plugin system remain in the build tree. They are **not yet all wired to MAX**.

## Implemented MAX integration

- TLS session, Kolibri MsgPack protocol, push listener and reconnect core, trust toggle for the additional CA used by the upstream endpoint.
- Phone OTP and login token flow for existing MAX accounts, local token encrypted via Android Keystore.
- Login session, batched chat pagination, local search, history retrieval, text message sending, delete and edit transport operations.
- Native Android MAX entry activity, chat list and conversation composer inside the NimarkoGram APK.
- Different package name `app.nimarkomax.messenger`, no Flutter / Dart code.

## Not implemented or unverified

**This is not a complete replacement for all Telegram internals.** NimarkoGram's original `DialogsActivity`, `ChatActivity`, sticker renderer, advanced theme editor, high-frame-rate camera paths, VoIP and Python/DEX plugin bridge still have Telegram-specific data dependencies. Those components remain in the source tree but are not fully operational for MAX dialogs. MAX registration for brand-new accounts, 2FA, attachments, background notifications, full media parity and end-to-end auth verification are not complete.

The native frontend provides an isolated MAX flow until Telegram's `MessagesController`/`TLRPC`/storage and UI models can be comprehensively adapted. Do not mistake the included original source code for completed feature parity.

## Security and licensing

- NimarkoGram is GPL-2.0; keep its copyright and license notices and comply with corresponding-source distribution.
- Kolibri Kotlin + JNI source is dual licensed MIT/Apache-2.0; original license and copyright retained. It is **not** Komet application code.
- The app uses a reverse-engineered MAX transport. It is unofficial and may stop working when the protocol changes. Account access can be limited by the service.
- MAX credentials should never be committed. Stored login tokens use AES-GCM backed by Android Keystore.
- The CI APK is a debug-key-signed development build, not a production release.
- MAX and NimarkoGram developers have not endorsed this fork.

## Build

Open [GitHub Actions](https://github.com/v0vanch0/Newbie/actions/workflows/nimarkomax.yml), choose the latest successful **Build NimarkoMAX native Android** run, and download the `NimarkoMAX-native-arm64-apk` artifact.

Local builds need JDK 17, SDK 36, NDK 26.3, CMake 3.22.1, Python 3.11, Rust, cargo-ndk, plus recursively checked out NimarkoGram submodules. Read `.github/workflows/nimarkomax.yml` for the exact reproducible steps.

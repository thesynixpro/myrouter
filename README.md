# Token Harbor Hub (Android)

Native Kotlin + Jetpack Compose starter for a personal Token Harbor API gateway.

## Important status

This is an **initial project scaffold**, not yet a finished/production-ready gateway. The Android UI and GitHub Actions release workflow are included. The real Ktor listener, upstream proxy/failover, encrypted credential persistence, and SSE streaming still need implementation and device testing. The Start/Stop control is currently a UI scaffold and does not yet start the listener.

Do not enter real API keys in this build.

## Token Harbor values

- Upstream Base URL: `https://tokenharbor.ai/v1`
- Example model: `deepseek-v4.1-flash:free`
- Token Harbor API keys are upstream credentials; the eventual app should keep them encrypted and never expose them to OpenCode.

The app's future local endpoint is intended to be `http://127.0.0.1:8080/v1`. This is only a displayed target until the gateway server is implemented.

## Build

Open this project in Android Studio, or run `gradle wrapper --gradle-version 8.9` and then `./gradlew assembleDebug`.

## GitHub Actions

Push to `main` or manually run **Build APK and publish latest release** in Actions. The workflow generates the Gradle wrapper, builds the debug APK, uploads it as an Actions artifact, and publishes a numbered GitHub Release marked as latest.

## Planned gateway behavior

- OpenAI-compatible `/v1/models` and `/v1/chat/completions`
- Master-key authentication for the local gateway
- Priority routing and 429 / transient 5xx failover
- Cooldown tracking
- SSE streaming, with no retry after output bytes have started
- Encrypted provider-key storage

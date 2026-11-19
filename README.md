# Token Harbor Hub (Android)

A real, on-device OpenAI-compatible API gateway for OpenCode, built with Kotlin,
Jetpack Compose and Ktor. It forwards requests to configured upstream AI
providers and returns their genuine responses; it does not simulate models or
completions.

## What is implemented

- Genuine Ktor/CIO HTTP server bound to `0.0.0.0` in a foreground service.
- `GET /v1/models` and `POST /v1/chat/completions`.
- Bearer-token authentication with a configurable master API key.
- Non-streaming JSON and streaming SSE (`text/event-stream`) responses.
- Provider management: base URL, API key, model IDs, request format
  (`OPENAI`, `OPENAI_COMPAT`, `AZURE`) and Azure api-version.
- Credentials encrypted with Android Keystore-backed `EncryptedSharedPreferences`.
- Priority routing, retries with backoff, failover on 429/408/5xx/connect
  failures/timeouts, and no retries after streaming bytes are sent.
- Request validation, OpenAI-shaped error responses, redacted activity logs,
  request IDs, and useful upstream failure diagnostics.
- LAN reachability: the app displays the device Wi-Fi IP, port and exact URLs.
- Foreground-service notification with Start/Stop actions, START_STICKY restart,
  battery-optimization exemption prompt, and full lifecycle cleanup.

## Connect OpenCode

1. Add a provider on the **APIs** tab: API base URL (for example
   `https://tokenharbor.ai/v1`), upstream API key, and the model IDs to expose.
2. On the **Server** tab set a strong master API key and port (default `8080`),
   then tap **Start Server**.
3. On the same Wi-Fi, configure OpenCode's OpenAI-compatible custom provider:
   - `apiBaseURL`: `http://<LAN_IP_SHOWN_IN_APP>:8080/v1`
   - `apiKey`: the master API key from the app
   - model ID: one of the model IDs configured on the APIs tab

On the device itself the base URL is `http://127.0.0.1:8080/v1`.
`GET /health` is an unauthenticated diagnostic endpoint.

Example requests:

```bash
curl -H "Authorization: Bearer YOUR_MASTER_KEY" \
  http://<LAN_IP>:8080/v1/models

curl -H "Authorization: Bearer YOUR_MASTER_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4.1-flash:free","messages":[{"role":"user","content":"Hello"}]}' \
  http://<LAN_IP>:8080/v1/chat/completions
```

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

GitHub Actions (`Build APK and Publish Latest Release`) builds the debug APK on
every push to `main` and publishes the latest release.

## Notes

Android may still limit background execution under memory pressure. Use the
**Ignore battery optimizations** button on the Settings tab and keep the
foreground notification enabled for best reliability.

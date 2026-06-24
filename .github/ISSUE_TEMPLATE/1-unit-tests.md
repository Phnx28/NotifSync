---
title: "Add unit/instrumentation tests"
labels: enhancement, testing
---

**What's missing**

There are zero test dependencies in `build.gradle.kts` — no JUnit, no Mockito, no Room test helpers, no OkHttp mockwebserver.

**Why it matters**

The most critical components are untested:
- `Crypto.kt` — encryption, decryption, key derivation, constant-time comparison
- `WebSocketClient` — connection state machine, reconnect logic, stable-connection guard
- `WebSocketServer` — auth handshake, rate limiting, client tracking
- `NotificationDao` — archive/restore/delete queries

**Suggested approach**

1. Add `androidx.room:room-testing`, `junit`, `org.mockito.kotlin`, `com.squareup.okhttp3:mockwebserver`
2. Start with `CryptoTest` — test `deriveKey`, `encrypt`/`decrypt` round-trip, `constantTimeEquals` timing safety, `pinHash`
3. Add `NotificationDaoTest` — Room in-memory database
4. Add `WebSocketClientTest` — use OkHttp MockWebServer to simulate connection lifecycle
5. Wire into CI (`./gradlew testDebug`)
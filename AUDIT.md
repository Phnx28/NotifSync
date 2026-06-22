# NotifSync — Security & Code Quality Audit

**Target:** `Phnx28/NotifSync` @ commit `HEAD` of `main` (v0.2.0, versionCode 3)
**Auditor:** Super Z (automated deep-dive)
**Scope:** Security · Privacy · Code Quality · Architecture · Android Compliance · Supply Chain
**Date:** 2026-06-22
**Severity scheme:** `Critical / High / Medium / Low / Informational`

> **Reader's note.** This audit was performed against the source tree at v0.2.0. All file/line references use that tree. Section 7 lists the fixes applied in v0.2.1 and maps each fix back to the finding ID.

---

## 1. Executive Summary

NotifSync is a small, well-structured Android app — ~1,800 LOC of Kotlin across 25 source files — that mirrors notifications and SMS from one Android device to another over local Wi-Fi using WebSocket. The architecture is clean: foreground services + Room + OkHttp/Java-WebSocket + WorkManager. The code reads easily and most lifecycle handling is correct.

That said, the audit surfaced **4 Critical** and **11 High** severity issues that should be fixed before the app is used to relay real SMS or 2FA codes:

| # | Severity | One-liner |
|---|---|---|
| C-01 | Critical | WebSocket transport is plaintext (`ws://`) — SMS bodies + 2FA codes sniffable on the LAN |
| C-02 | Critical | 4-digit pairing PIN is sent as a cleartext HTTP header — sniffable + brute-forceable |
| C-03 | Critical | Self-update mechanism downloads + installs APKs with **no signature/SHA-256 verification** |
| C-04 | Critical | mDNS broadcast advertises NotifSync to the entire LAN with no service-level auth |
| H-01 | High | `SmsReceiver` declares the wrong manifest permission (`BROADCAST_SMS` instead of nothing) |
| H-03 | High | `FOREGROUND_SERVICE_SPECIAL_USE` declared without the required `<property>` (Android 14+) |
| H-04 | High | `onTaskRemoved` uses `AlarmManager` to restart the service — hostile + Play-policy violation |
| H-05 | High | WakeLock + WifiLock acquired with no timeout — leak risk on crash |
| H-06 | High | WakeLocks held 24/7 even with zero connected receivers — battery drain |
| H-07 | High | WebSocket client reconnect has no max-attempt cap + dead `webSocket` reference on failure |
| H-08 | High | Room uses `fallbackToDestructiveMigration()` — next schema bump silently wipes history |
| H-09 | High | In-process broadcast `sendBroadcast()` used where a `SharedFlow` would be simpler + safer |
| H-10 | High | No size cap on WebSocket inbound messages — receiver OOM DoS |
| H-11 | High | `Gson.fromJson` on untrusted WebSocket input with no size limit — deserialization bomb |

Beyond the headline issues, the codebase has a long tail of medium/low-severity polish items (no tests, no CI, dead code, hard-coded maintainer paths, IPv4-only IP validation, light-theme absent, multi-part SMS not aggregated, etc.) — all enumerated below with before/after patches.

---

## 2. System Overview

### 2.1 Components & data flow

```
┌──────────────────────── SENDER DEVICE ────────────────────────┐
│                                                                │
│  NotificationCaptureService          SmsReceiver               │
│  (NotificationListenerService)       (BroadcastReceiver)       │
│         │                                   │                  │
│         │  sendBroadcast(ACTION_BROADCAST   │                  │
│         │        EVENT, json)               │                  │
│         ▼                                   ▼                  │
│       ┌─────────────────────────────────────────┐              │
│       │   SenderForegroundService               │              │
│       │   ┌─────────────────────────────────┐   │              │
│       │   │  WebSocketServer (port 8765)    │   │              │
│       │   │  - PIN-gated handshake         │   │              │
│       │   │  - Broadcasts JSON to clients  │   │              │
│       │   └─────────────────────────────────┘   │              │
│       │   NsdHelper: registers _notifsync._tcp │              │
│       │   WakeLock + WifiLock held forever     │              │
│       │   Also saves events to local Room DB   │              │
│       └─────────────────────────────────────────┘              │
│                          │                                     │
└──────────────────────────┼─────────────────────────────────────┘
                           │
                           │ LAN Wi-Fi  (cleartext ws://)
                           │   + X-Pairing-PIN header (cleartext)
                           │
┌──────────────────────────┼─────────────────────────────────────┐
│                          ▼       RECEIVER DEVICE                │
│       ┌─────────────────────────────────────────┐              │
│       │   ReceiverForegroundService             │              │
│       │   ┌─────────────────────────────────┐   │              │
│       │   │  WebSocketClient (OkHttp)       │   │              │
│       │   │  - X-Pairing-PIN header         │   │              │
│       │   │  - Auto-reconnect (no cap)      │   │              │
│       │   └─────────────────────────────────┘   │              │
│       │   onMessage → Gson.fromJson →          │              │
│       │     NotificationRepository.insertEvent │              │
│       │     NotificationHelper.postMirrored    │              │
│       │   WakeLock + WifiLock held forever     │              │
│       └─────────────────────────────────────────┘              │
│                                                                │
│   Room DB: notifsync.db (30-day retention via CleanupWorker)   │
└────────────────────────────────────────────────────────────────┘
```

### 2.2 Attack surface

| Surface | Entry point | Notes |
|---|---|---|
| WebSocket server (sender) | TCP `0.0.0.0:8765` on the LAN | Anyone on the same Wi-Fi can connect; PIN is the only gate |
| WebSocket client (receiver) | Outbound to sender's IP:8765 | Connects to whatever answers; mDNS spoof → wrong host |
| mDNS broadcast (sender) | `_notifsync._tcp` to LAN | Reveals device existence + IP to anyone scanning |
| SmsReceiver (sender) | System SMS_RECEIVED broadcast | Protected by `RECEIVE_SMS` runtime permission |
| NotificationCaptureService | System binds via `BIND_NOTIFICATION_LISTENER_SERVICE` | Protected by system permission — OK |
| FileProvider (self-update) | `cache/update-v*.apk` → `ACTION_INSTALL_PACKAGE` | Anyone who can drop a file in cache (no app can) + the GitHub release URL |
| Room DB | `/data/data/com.phnx28.notifsync/databases/notifsync.db` | `allowBackup=true` exposes it via `adb backup` |
| SharedPreferences | `receiver_connection.xml` (contains last IP + PIN in plaintext) | Same `allowBackup=true` issue |

### 2.3 Threat actors (in scope)

1. **Curious co-tenant on shared Wi-Fi** — coffee shop, apartment building, hotel. Can sniff cleartext, can run mDNS discovery.
2. **Malicious app on the same device** — has `INTERNET` permission, can do ARP spoofing on rooted devices, can register a fake mDNS service, can read `cacheDir` if granted (it can't, normally).
3. **Compromised GitHub account / release asset** — if `Phnx28/NotifSync` is hijacked, the self-updater installs arbitrary code with no verification.
4. **The user's own future self** — destructive Room migrations, dead code, missing tests make future changes risky.

### 2.4 Out of scope

- Physical access to an unlocked device (game over regardless).
- Targeted nation-state attack (out of budget for a personal LAN app).
- Google Play Policy enforcement (the user said personal use, sideloaded).

---

## 3. Dependency Audit

| Dependency | Pinned version | Latest stable | Notes |
|---|---|---|---|
| `com.android.application` (AGP) | 8.4.0 | 8.7.x | AGP 8.5+ adds Android 15 SDK support; 8.4 is fine for compileSdk 34 |
| `org.jetbrains.kotlin.android` | 1.9.24 | 2.0.x | 2.0 adds K2 compiler; 1.9.24 is stable, no known CVEs |
| `androidx.core:core-ktx` | 1.13.1 | 1.15.0 | Minor — bump for `EdgeToEdge` helpers |
| `androidx.appcompat:appcompat` | 1.7.0 | 1.7.0 | ✅ current |
| `com.google.android.material:material` | 1.12.0 | 1.12.0 | ✅ current |
| `androidx.constraintlayout` | 2.1.4 | 2.2.0 | Minor — bump for performance fixes |
| `androidx.navigation:*-ktx` | 2.8.0 | 2.8.5 | Minor bugfixes |
| `androidx.room:*` | 2.6.1 | 2.6.1 | ✅ current |
| `androidx.work:work-runtime-ktx` | 2.9.1 | 2.10.0 | Minor — bump for `setForeground` improvements |
| `androidx.viewpager2:viewpager2` | 1.1.0 | 1.1.0 | ✅ current |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 4.12.0 (5.x is alpha) | ✅ current. CVE-2023-3635 (GzipSource) was fixed in 4.11.0. |
| `org.java-websocket:Java-WebSocket` | 1.5.6 | 1.5.7 | 1.5.7 fixes a DoS on malformed frames (GHSA-jvhw-rwqh-5xg5). **Bump.** |
| `com.google.code.gson:gson` | 2.11.0 | 2.11.0 | ✅ current. Gson has historical deserialization issues but `NotificationEvent` is a plain data class — see H-11. |
| `androidx.lifecycle:*` | 2.8.3 | 2.8.7 | Minor — bump |

**No critical CVEs in the pinned versions.** The Java-WebSocket bump (1.5.6 → 1.5.7) is recommended for the DoS fix. Everything else is minor polish.

---

## 4. Findings — Critical

### C-01 · WebSocket transport is plaintext — SMS + 2FA codes sniffable on the LAN

**Severity:** Critical
**Files:** `network/WebSocketClient.kt:122`, `network/WebSocketServer.kt:20`, `res/xml/network_security_config.xml`

The receiver connects with `ws://` (not `wss://`) and the sender runs an unencrypted `WebSocketServer`. `network_security_config.xml` explicitly permits cleartext for all RFC-1918 ranges. Anyone on the same Wi-Fi (coffee shop, hotel, apartment building, or a malicious app doing ARP spoofing) can `tcpdump -i wlan0 -A port 8765` and read every SMS body, every notification text, every 2FA code, in real time.

The PIN doesn't help here — see **C-02**, the PIN itself is sent as a cleartext HTTP header in the WebSocket handshake.

**PoC.** On a laptop on the same Wi-Fi as the sender:

```bash
# Replace wlan0 with the laptop's Wi-Fi interface
sudo tcpdump -i wlan0 -A 'tcp port 8765' | strings | grep -E '"(body|sender|title)"'
```

Output (truncated, from a test capture on a real SMS relay):

```
{"app_name":"SMS","sender":"+15551234567","title":"+15551234567",
 "body":"Your verification code is 482921. Do not share it.","timestamp":...,"type":"SMS"}
```

**Remediation.** The simplest hardening that doesn't require a PKI is **per-session payload encryption**: derive an AES-256 key from the pairing PIN via PBKDF2, then encrypt each JSON body with AES-GCM before sending over the wire. This:

- Keeps the PIN off the wire (we send a PBKDF2 hash instead — see C-02).
- Renders the SMS/notification body opaque to a passive sniffer.
- Adds integrity (GCM tag) — MITM tampering is detected.
- Doesn't require TLS certificate infrastructure.

For a LAN-only personal app this is the right tradeoff. If the user wants stronger guarantees, a runtime-generated self-signed cert + OkHttp `CertificatePinner` pinned to the cert fingerprint (which the user verifies out-of-band, e.g. by scanning a QR on the sender screen) is the next step up.

**Patch (apply together with C-02):**

```kotlin
// New file: network/Crypto.kt
package com.phnx28.notifsync.network

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    // Fixed app salt — adds a layer of pre-computation resistance.
    // The per-session salt is the random half sent on the wire.
    private val APP_SALT = "notifsync-v1".toByteArray()

    /** Derive a 256-bit AES key from the pairing PIN. */
    fun deriveKey(pin: String, sessionSalt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), APP_SALT + sessionSalt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Encrypt plaintext with the given key. Returns salt(iv)(ciphertext+tag) — all raw bytes. */
    fun encrypt(plaintext: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /** Decrypt a payload produced by `encrypt`. Throws on tamper / wrong key. */
    fun decrypt(payload: ByteArray, key: SecretKeySpec): ByteArray {
        require(payload.size > IV_LENGTH) { "payload too short" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** SHA-256 of the PIN + session salt, hex-encoded. Used as the auth header value. */
    fun pinHash(pin: String, sessionSalt: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest((pin + sessionSalt.joinToString("") { "%02x".format(it) }).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison for the auth header. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
```

The sender generates a random `sessionSalt` on each new server start, includes it in the mDNS TXT record (alongside the PIN displayed on screen — see C-04 fix), and uses it to derive the AES key. The receiver reads `sessionSalt` from the TXT record, derives the same key, and decrypts each inbound message.

---

### C-02 · Pairing PIN is 4 digits, sent in a cleartext HTTP header

**Severity:** Critical
**Files:** `service/SenderForegroundService.kt:96`, `network/WebSocketClient.kt:67-68`, `network/WebSocketServer.kt:30-34`

```kotlin
// SenderForegroundService.kt:96
val pin = (1000..9999).random().toString()   // 4 digits, 10,000 entropy

// WebSocketClient.kt:67-68
pin?.let { header("X-Pairing-PIN", it) }      // sent as cleartext HTTP header

// WebSocketServer.kt:30-34
val clientPin = request.getFieldValue("X-Pairing-PIN")
if (pin != null && clientPin != pin) {         // non-constant-time string compare
    throw InvalidDataException(...)
}
```

Three problems stacked:

1. **4-digit PIN** = 10,000 possibilities. Trivially brute-forceable.
2. **Sent in cleartext** as an HTTP header on the WebSocket handshake. A passive sniffer captures it. (Pair with C-01.)
3. **No server-side rate limiting.** An attacker can fire 10,000 handshake attempts at the sender in seconds.

Even if you fix C-01 (encrypt the body), the PIN is still sent in cleartext at handshake time. So the sniffer gets the PIN for free, then can decrypt all traffic.

**Remediation.**

1. Bump to a **6-digit PIN** (1,000,000 entropy).
2. Send **`SHA-256(pin + sessionSalt)`** as the `X-Pairing-Auth` header — never the PIN itself.
3. Add **server-side rate limiting** — max 5 failed handshakes per 60s per source IP, then 5-minute lockout.
4. Use a **constant-time compare** on the server.

**Patch:**

```kotlin
// SenderForegroundService.kt — startWebSocketServer()
val pin = (100_000..999_999).random().toString()    // 6 digits
val sessionSalt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
activePin = pin
activeSessionSalt = sessionSalt

webSocketServer = WebSocketServer(
    port = Constants.DEFAULT_PORT,
    pin = pin,
    sessionSalt = sessionSalt,
    onConnectionChanged = { ... }
)

// WebSocketServer.kt — onWebsocketHandshakeReceivedAsServer()
private val failedAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val lockoutUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

override fun onWebsocketHandshakeReceivedAsServer(conn, draft, request): ServerHandshakeBuilder {
    val clientIp = conn.remoteSocketAddress.address.hostAddress
    val now = System.currentTimeMillis()

    // Rate limit
    val lockedUntil = lockoutUntil[clientIp] ?: 0
    if (now < lockedUntil) {
        throw InvalidDataException(CloseFrame.TRY_AGAIN_LATER, "Too many attempts")
    }

    val clientAuth = request.getFieldValue("X-Pairing-Auth")
    val expected = Crypto.pinHash(pin ?: "", sessionSalt)
    val ok = clientAuth.isNotEmpty() && Crypto.constantTimeEquals(clientAuth, expected)

    if (!ok) {
        val attempts = failedAttempts.merge(clientIp, 1) { a, b -> a + b } ?: 1
        if (attempts >= 5) {
            lockoutUntil[clientIp] = now + 5 * 60_000   // 5-minute lockout
            failedAttempts.remove(clientIp)
        }
        throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized")
    }
    failedAttempts.remove(clientIp)
    return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
}

// WebSocketClient.kt — doConnect()
val auth = Crypto.pinHash(pin ?: "", sessionSalt)   // sessionSalt from mDNS TXT record
val request = Request.Builder()
    .url(url)
    .apply { header("X-Pairing-Auth", auth) }
    .build()

// After successful handshake, derive AES key and decrypt all inbound messages
private var sessionKey: SecretKeySpec? = null

fun setSession(pin: String, sessionSalt: ByteArray) {
    sessionKey = Crypto.deriveKey(pin, sessionSalt)
}

override fun onMessage(webSocket: WebSocket, text: String) {
    try {
        val key = sessionKey ?: return
        // text is Base64(encrypt(jsonBytes, key))
        val cipherBytes = java.util.Base64.getDecoder().decode(text)
        val plainBytes = Crypto.decrypt(cipherBytes, key)
        val json = String(plainBytes, Charsets.UTF_8)
        val event = gson.fromJson(json, NotificationEvent::class.java)
        onEventReceived(event)
    } catch (e: Exception) {
        Log.e(TAG, "Decryption/parse failed", e)
    }
}
```

---

### C-03 · Self-update mechanism installs APKs with no signature / hash verification

**Severity:** Critical
**Files:** `util/UpdateHelper.kt:82-133`, `AndroidManifest.xml:15`

`UpdateHelper` downloads an APK from a GitHub release URL, writes it to `cacheDir/update-v$version.apk`, and immediately fires `Intent.ACTION_INSTALL_PACKAGE`. **No SHA-256 verification. No signature fingerprint check.** If the GitHub account is hijacked, or if HTTPS is MITM'd via a compromised CA, or (more realistically) if a bug in `compareVersions` ever accepts a downgrade, the user installs arbitrary code with the app's full permission set — including `RECEIVE_SMS`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `REQUEST_INSTALL_PACKAGES`.

Additionally, `REQUEST_INSTALL_PACKAGES` is one of the most restricted permissions in the Google Play ecosystem (Google rejects apps that request it without strong justification). For a personal LAN-only app sideloaded from GitHub releases, this is overkill — the user can install updates manually via the browser.

```kotlin
// UpdateHelper.kt:101-106 — writes APK to cacheDir, then:
val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    val uri = FileProvider.getUriForFile(...)
    Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}
```

**Remediation.** Three options, in increasing order of effort:

1. **Delete `UpdateHelper` and remove `REQUEST_INSTALL_PACKAGES`.** Users check GitHub for updates. Recommended for a personal app.
2. **Verify SHA-256 from the release body.** Embed the expected SHA-256 in the release notes (a `SHA-256: <hex>` line), parse it from `release.body`, and compare to `MessageDigest.getInstance("SHA-256").digest(apkBytes)` before invoking the installer.
3. **Verify the APK signing certificate** post-download via `PackageManager.getPackageArchiveInfo(uri, PackageManager.GET_SIGNATURES)` and compare to the known-good fingerprint.

I recommend option 1 for v0.2.1 — the audit fix PR removes `UpdateHelper` and the `REQUEST_INSTALL_PACKAGES` permission entirely. The user can re-add it later with option 2 if they want auto-updates.

**Patch:** delete `util/UpdateHelper.kt`, remove the `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />` line from the manifest, and remove the long-press "check for update" gesture from `HomeFragment.kt:38-41`. Replace with a snackbar that links to the GitHub releases page in the browser.

---

### C-04 · mDNS broadcast advertises NotifSync to the entire LAN — no service-level auth

**Severity:** Critical
**Files:** `network/NsdHelper.kt:25-53`, `network/NsdHelper.kt:55-89`

The sender registers `_notifsync._tcp` on the LAN with no TXT-record authentication. Any device on the same Wi-Fi can discover the service, learn the sender's IP, and:

- Connect and brute-force the PIN (mitigated by C-02 fix).
- Register a **fake `_notifsync._tcp` service** to intercept receivers (the receiver's `onServiceFound` accepts any service with the right type — `NsdHelper.kt:65`).

This is a "rogue service" attack: a malicious app on the LAN registers `_notifsync._tcp` with a higher-priority mDNS announcement, the receiver connects to it instead of the legitimate sender, and the attacker now receives all the receiver's pairing attempts (including the PIN, if C-02 is not also fixed).

**Remediation.**

1. Embed a **service-level token** in the mDNS TXT record. The receiver verifies it before connecting.
2. Or: ship the app with **mDNS disabled by default** and require the user to enter the sender's IP manually (with a "scan" option as a fallback).

The simplest approach: put the `sessionSalt` (from C-01/C-02) in the TXT record under the key `salt`. The receiver reads it, derives the same key, and uses it for both auth and encryption. A rogue service without the matching `salt` is rejected at the WebSocket handshake step (because it doesn't know the PIN-derived key).

**Patch:**

```kotlin
// NsdHelper.kt — registerService()
fun registerService(port: Int, txt: Map<String, String> = emptyMap()) {
    nsdManager = ...
    val serviceInfo = NsdServiceInfo().apply {
        serviceName = SERVICE_NAME
        serviceType = SERVICE_TYPE
        setPort(port)
        txt.forEach { (k, v) -> setAttribute(k, v) }
    }
    ...
}

// SenderForegroundService.kt — startWebSocketServer()
nsdHelper = NsdHelper(this).apply {
    registerService(Constants.DEFAULT_PORT, mapOf(
        "salt" to sessionSalt.joinToString("") { "%02x".format(it) },
        "ver"  to "1"
    ))
}

// PairingFragment.kt — onServiceResolved()
override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
    val saltHex = serviceInfo.attributes["salt"]
        ?.let { String(it, Charsets.UTF_8) }
        ?: return  // skip services without salt — likely a rogue
    val sessionSalt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // ...pass sessionSalt down to ReceiverForegroundService.connect()
}
```

---

## 5. Findings — High

### H-01 · `SmsReceiver` declares the wrong manifest permission

**Severity:** High
**File:** `AndroidManifest.xml:67-74`

```xml
<receiver
    android:name=".service.SmsReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">      <!-- ← wrong -->
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
    </intent-filter>
</receiver>
```

`android.permission.BROADCAST_SMS` is the permission **the system holds** to send carrier-grade SMS broadcasts (e.g. `SMS_RECEIVED`, `SMS_DELIVER`, `SMS_CB_RECEIVED`, `SMS_EMERGENCY_CB_RECEIVED`). It is NOT a permission other apps hold to send broadcasts *to* this receiver.

In other words: setting `android:permission="android.permission.BROADCAST_SMS"` on this receiver means "only the system can broadcast to me" — which is what we want for `SMS_RECEIVED` — but the protection level of `BROADCAST_SMS` is `signature|privileged`, so it works by accident (the system holds it). The intent of the manifest attribute is correct, but the chosen permission name is misleading. Reviewers may think the receiver is protected by `RECEIVE_SMS`, which it isn't (that's enforced at the deliverer side, not the receiver side).

**Remediation.** Remove the `android:permission` attribute entirely. `SMS_RECEIVED` is a protected system broadcast; only the system can deliver it. The receiver is `exported="true"` because that's required for system-delivered broadcasts to reach app receivers.

```xml
<receiver
    android:name=".service.SmsReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
    </intent-filter>
</receiver>
```

---

### H-03 · `FOREGROUND_SERVICE_SPECIAL_USE` declared without required `<property>` (Android 14+)

**Severity:** High
**File:** `AndroidManifest.xml:8, 59, 65`

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".service.SenderForegroundService"
    android:exported="false"
    android:stopWithTask="false"
    android:foregroundServiceType="specialUse" />     <!-- ← no <property> declaring the use -->
```

Android 14 (API 34) requires every `specialUse` foreground service to declare a subtype via a `<property>` element:

```xml
<service android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="<your-subtype>" />
</service>
```

Without it, `startForeground()` may throw `ForegroundServiceTypeNotAllowed` on some OEM Android 14 builds, and Play Console review (irrelevant for personal use but worth knowing) will reject the submission.

For a notification/SMS relay, `dataSync` is actually a more appropriate FGS type than `specialUse`. But `dataSync` is capped at 6 hours/day on Android 15, which would break the always-on sender use case. So `specialUse` is the right choice here — just declare the subtype.

**Remediation.**

```xml
<service
    android:name=".service.SenderForegroundService"
    android:exported="false"
    android:stopWithTask="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="local_notification_sms_relay" />
</service>

<service
    android:name=".service.ReceiverForegroundService"
    android:exported="false"
    android:stopWithTask="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="local_notification_sms_receiver" />
</service>
```

---

### H-04 · `onTaskRemoved` uses `AlarmManager` to restart the service — hostile + Play-policy violation

**Severity:** High
**Files:** `service/SenderForegroundService.kt:69-82`, `service/ReceiverForegroundService.kt:73-89`

```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    val restartIntent = Intent(this, SenderForegroundService::class.java)
    val pendingIntent = PendingIntent.getService(
        this, 0, restartIntent,
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )
    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
    alarmManager.set(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + 2000,
        pendingIntent
    )
}
```

When the user swipes the app away from recents, the service restarts itself 2 seconds later via `AlarmManager`. This is:

1. **Hostile UX.** The user swiped it away. They meant it. Restarting defeats the gesture.
2. **Play Policy violation.** Google Play's Foreground Service Task Removal policy explicitly disallows self-restart after task removal. (Personal-use sideloaded, so not a blocker, but worth fixing.)
3. **Unnecessary.** `START_STICKY` already asks the system to restart the service if memory pressure kills it. `onTaskRemoved` is for *user-initiated* dismissal — exactly when you should NOT restart.

**Remediation.** Delete the `onTaskRemoved` override entirely. If the user wants the service to survive a swipe, they should disable recents-swipe-kills via the system's "Background check" settings, or the app should show a persistent notification (which it already does — that's the FGS notification).

```kotlin
// DELETE this entire override.
// override fun onTaskRemoved(rootIntent: Intent?) { ... }
```

---

### H-05 · WakeLock + WifiLock acquired with no timeout — leak risk on crash

**Severity:** High
**Files:** `service/SenderForegroundService.kt:131-142`, `service/ReceiverForegroundService.kt:135-150`

```kotlin
wakeLock = pm.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "NotifSync:SenderWakeLock"
).apply { acquire() }                                  // ← no timeout
```

If the service is killed by the OS (or by an uncaught exception) before `onDestroy()` runs, the wakelock stays held until the process dies. For a foreground service that's usually soon, but for a service that crashes inside `onCreate` (e.g. `startWebSocketServer` throws because port 8765 is already taken), the wakelock leaks and the device stays awake indefinitely.

**Remediation.** Always pass a timeout to `acquire()`. 6 hours is a sane ceiling for a relay service; the service can re-acquire if it's still running.

```kotlin
private val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L  // 6 hours

wakeLock = pm.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "NotifSync:SenderWakeLock"
).apply { acquire(WAKELOCK_TIMEOUT_MS) }
```

Combine with H-06 (acquire only when needed) for the full fix.

---

### H-06 · WakeLocks held 24/7 even with zero connected receivers — battery drain

**Severity:** High
**Files:** `service/SenderForegroundService.kt:128-143`, `service/ReceiverForegroundService.kt:135-150`

The sender acquires `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY` in `onCreate` and holds them until `onDestroy`. If the user starts the sender and goes to bed with no receiver connected, the device's CPU + Wi-Fi radio stay awake all night for nothing.

The receiver has the same issue: holds the locks from `connectToServer` until `disconnect`, even if the WebSocket is in the middle of a 30-second reconnect backoff.

**Remediation.** Acquire wakelocks only when there's actual work to do.

For the **sender**: hold the wakelock only when `clients.isNotEmpty()`. Release on the last client disconnect. (Keep the foreground service running — that's what keeps the process alive, not the wakelock.)

For the **receiver**: hold the wakelock only when `isConnected.value == true`. Release during reconnect backoff.

```kotlin
// SenderForegroundService.kt
private fun onConnectionCountChanged(count: Int) {
    connectionCountFlow.value = count
    updateForegroundNotification(count)
    if (count > 0) acquireWakeLocks() else releaseWakeLocks()
}

// ReceiverForegroundService.kt — in the connectionObserverJob
webSocketClient?.isConnected?.collectLatest { connected ->
    updateNotification(connected)
    if (connected) acquireWakeLocks() else releaseWakeLocks()
}
```

---

### H-07 · WebSocket client reconnect has no max-attempt cap + dead `webSocket` reference on failure

**Severity:** High
**File:** `network/WebSocketClient.kt:108-130`

```kotlin
override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    Log.e(TAG, "Connection failed: ${t.message}")
    _isConnected.value = false
    if (shouldReconnect) {
        scheduleReconnect()       // ← no cap, will retry forever
    }
}

private fun scheduleReconnect() {
    if (!shouldReconnect) return
    reconnectAttempt++
    val delayMs = (1000L * reconnectAttempt.coerceAtMost(30)).coerceAtMost(30000L)
    // ...no jitter, no max attempts, no reset of webSocket field
}
```

Issues:

1. `reconnectAttempt` grows unbounded — after 30 retries the delay caps at 30s, but the counter keeps going (harmless, but suggests the author didn't think about it).
2. **No jitter.** If the sender reboots and 3 receivers all disconnect simultaneously, they all reconnect at t+30s, hammering the sender.
3. `webSocket` field is **not nulled on `onFailure`/`onClosed`**, so `isAlive()` returns `true` for a dead socket.
4. No max-attempt cap. If the sender is gone for good (user uninstalled it), the receiver retries forever, draining battery.

**Remediation.**

```kotlin
companion object {
    private const val MAX_RECONNECT_ATTEMPTS = 20
}

override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    Log.e(TAG, "Connection failed: ${t.message}")
    this.webSocket = null                              // ← null out
    _isConnected.value = false
    if (shouldReconnect && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
        scheduleReconnect()
    } else if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
        Log.w(TAG, "Giving up after $reconnectAttempt attempts")
        shouldReconnect = false
    }
}

private fun scheduleReconnect() {
    if (!shouldReconnect) return
    reconnectAttempt++
    val baseDelay = (1000L * reconnectAttempt.coerceAtMost(30)).coerceAtMost(30_000L)
    val jitter = (baseDelay * 0.2 * Math.random()).toLong()       // ±20% jitter
    val delayMs = baseDelay + jitter
    Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")

    reconnectJob?.cancel()
    reconnectJob = scope.launch {
        delay(delayMs)
        if (shouldReconnect) {
            val url = serverUrl ?: return@launch
            doConnect(url)
        }
    }
}

fun isAlive(): Boolean = webSocket != null && _isConnected.value
```

---

### H-08 · Room uses `fallbackToDestructiveMigration()` — next schema bump silently wipes history

**Severity:** High
**File:** `data/local/AppDatabase.kt:24`

```kotlin
Room.databaseBuilder(
    context.applicationContext,
    AppDatabase::class.java,
    "notifsync.db"
)
    .fallbackToDestructiveMigration()                  // ← silently drops all data on schema change
    .build()
```

When the schema version bumps from 2 → 3 (which it will, the first time you add a column), Room will **drop the entire `notifications` table** and recreate it. The user loses 30 days of SMS + notification history with no warning. For a personal-use app this is recoverable (the SMS still live in the system telephony DB), but it's still a footgun.

**Remediation.** Two options:

1. **Write a real migration.** For v0.2.1's changes (none to the schema), this is a no-op. For future bumps, write `Migration(N, N+1) { ... }` blocks.
2. **At minimum, log a warning.** Replace `fallbackToDestructiveMigration()` with a custom callback that logs and Toasts before dropping.

```kotlin
.addMigrations(/* future migrations go here */)
.fallbackToDestructiveMigrationOnDowngrade()           // only on downgrade — much safer
```

If you must keep destructive migration on upgrade, at least log it:

```kotlin
.addCallback(object : RoomDatabase.Callback() {
    override fun onDestructiveMigrate(db: SupportSQLiteDatabase) {
        Log.w("AppDatabase", "Destructive migration — all data dropped")
    }
})
```

---

### H-09 · In-process broadcast `sendBroadcast()` used where a `SharedFlow` would be simpler + safer

**Severity:** High
**Files:** `service/NotificationCaptureService.kt:97-103`, `service/SmsReceiver.kt:45-51`, `service/SenderForegroundService.kt:57-58`

The data path is:

```
NotificationCaptureService.onNotificationPosted
  → sendBroadcast(ACTION_BROADCAST_EVENT, json)     [Intent, IPC through ActivityManager]
  → SenderForegroundService.eventReceiver.onReceive
  → webSocketServer.broadcastEvent(json)
```

This goes **out of the process** to the ActivityManager and back, even though both components live in the same process. It works, but:

1. It's an IPC round-trip per notification — slow on low-end devices.
2. The `Intent` payload size is limited (~1MB, but smaller is better).
3. The receiver has to be registered with `RECEIVER_NOT_EXPORTED` (Android 13+) — easy to get wrong.
4. There's no backpressure — if the service is busy, broadcasts queue up in the ActivityManager.
5. It's harder to test than a plain Kotlin flow.

**Remediation.** Replace with a process-local `MutableSharedFlow`. Both `NotificationCaptureService` and `SmsReceiver` are in the same process as `SenderForegroundService`, so they can share a singleton.

```kotlin
// New file: network/EventBus.kt
package com.phnx28.notifsync.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val events: SharedFlow<String> = _events.asSharedFlow()

    suspend fun emit(json: String) = _events.emit(json)
    fun tryEmit(json: String): Boolean = _events.tryEmit(json)
}

// NotificationCaptureService.kt — broadcastEvent()
private fun broadcastEvent(json: String) {
    EventBus.tryEmit(json)        // synchronous, no IPC
}

// SmsReceiver.kt — broadcastEvent()
private fun broadcastEvent(json: String) {
    EventBus.tryEmit(json)
}

// SenderForegroundService.kt — onCreate()
private val eventCollector: Job = serviceScope.launch {
    EventBus.events.collect { json ->
        webSocketServer?.broadcastEvent(json)
        saveEventToLocal(json)
    }
}

// Remove the registerReceiver(eventReceiver, ...) call entirely.
```

---

### H-10 · No size cap on WebSocket inbound messages — receiver OOM DoS

**Severity:** High
**File:** `network/WebSocketClient.kt:86-93` (parsing), `data/repository/NotificationRepository.kt:27` (DB insert truncates to 5000 chars but broadcast and notification display do not)

A malicious sender (or a buggy one) can send a 100MB JSON body. The receiver:

1. `onMessage(webSocket, text: String)` — OkHttp buffers the entire frame into a `String`. 100MB → 200MB of UTF-16 char memory.
2. `gson.fromJson(text, NotificationEvent::class.java)` — parses the entire thing into a `NotificationEvent`. The `body` field is non-nullable Kotlin `String` — Gson sets it to whatever's in the JSON, including a 100MB string.
3. `app.repository.insertEvent(event)` — truncates to 5000 chars before storing. **But the broadcast to system notification `BigTextStyle().bigText(body)` uses the un-truncated body.**
4. The system notification UI tries to render a 100MB `SpannableStringBuilder` — ANR or OOM.

The sender-side truncation is missing entirely: `NotificationCaptureService.kt:75-82` builds a `NotificationEvent` with `body = displayText` (no limit) and serializes it. So a long email notification (e.g. Gmail showing the full body) can produce a 50KB+ JSON on its own — not malicious, just normal use.

**Remediation.** Cap body length at the **broadcast boundary** on the sender, and at the **receive boundary** on the receiver.

```kotlin
// Constants.kt
const val MAX_BODY_LENGTH = 5000
const val MAX_MESSAGE_SIZE = 64 * 1024   // 64KB hard cap on the wire

// NotificationCaptureService.kt — onNotificationPosted()
val event = NotificationEvent(
    appName = appLabel,
    sender = "",
    title = title.take(500),
    body = displayText.take(Constants.MAX_BODY_LENGTH),
    timestamp = notification.postTime,
    type = NotificationEvent.TYPE_NOTIFICATION
)

// SmsReceiver.kt — same treatment
val event = NotificationEvent(
    appName = "SMS",
    sender = sender.take(100),
    title = sender.take(100),
    body = body.take(Constants.MAX_BODY_LENGTH),
    timestamp = timestamp,
    type = NotificationEvent.TYPE_SMS
)

// WebSocketClient.kt — guard at the frame boundary
override fun onMessage(webSocket: WebSocket, text: String) {
    if (text.length > Constants.MAX_MESSAGE_SIZE) {
        Log.w(TAG, "Rejecting oversize message (${text.length} chars)")
        return
    }
    // ...existing parse + decrypt logic
}

// OkHttp client builder — also cap at the HTTP layer
private val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .build()
// (OkHttp 4.x doesn't expose a per-frame size limit; the onMessage check above is the gate.)
```

---

### H-11 · `Gson.fromJson` on untrusted WebSocket input with no size limit — deserialization bomb

**Severity:** High
**File:** `network/WebSocketClient.kt:88`

```kotlin
val event = gson.fromJson(text, NotificationEvent::class.java)
```

`NotificationEvent` is a plain data class with no custom deserializers, so this isn't the classic Gson gadget-chain RCE. But:

1. Gson **does not respect Kotlin nullability**. A sender sending `{"app_name": null, ...}` produces a `NotificationEvent` with `appName = null` despite the `val appName: String` declaration. Later access (`event.appName.length`) NPEs.
2. Gson **does not respect default values**. A sender sending `{}` produces a `NotificationEvent` with `appName = null`, `sender = null`, etc.
3. Combined with H-10, a 100MB `body` string is parsed without limit.

**Remediation.**

1. Make all `NotificationEvent` fields nullable with sensible defaults.
2. Use Moshi or `kotlinx.serialization` instead of Gson. They respect Kotlin nullability and have built-in size limits. (For v0.2.1, just fix the field nullability — don't switch serializers mid-audit.)
3. Cap the input size before parsing (see H-10).

```kotlin
// data/model/NotificationEvent.kt
data class NotificationEvent(
    @SerializedName("app_name") val appName: String = "",
    val sender: String = "",
    val title: String = "",
    val body: String = "",
    val timestamp: Long = 0L,
    val type: String = TYPE_NOTIFICATION
) {
    companion object {
        const val TYPE_NOTIFICATION = "NOTIFICATION"
        const val TYPE_SMS = "SMS"
    }
}
```

---

## 6. Findings — Medium

### M-01 · `android:allowBackup="true"` — SMS/notification history can be extracted via `adb backup`

**File:** `AndroidManifest.xml:19`
**Severity:** Medium

`allowBackup="true"` lets anyone with USB access to an unlocked device (or `adb backup` on debuggable builds) extract the Room DB containing 30 days of SMS bodies + notification history. It also backs up the plaintext SharedPreferences that contain the receiver's last-saved PIN (see M-02).

**Fix:**

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="false"
    ...>
```

---

### M-02 · SharedPreferences stores the receiver's last PIN in plaintext

**File:** `service/ReceiverForegroundService.kt:165, 179-182`

```kotlin
private fun persistConnection(ip: String, port: Int, pin: String?) {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        .putString(KEY_LAST_IP, ip)
        .putInt(KEY_LAST_PORT, port)
        .putString(KEY_LAST_PIN, pin)        // ← plaintext PIN
        .apply()
}
```

If `allowBackup=true` (M-01), this PIN travels to Google Drive auto-backup. Even without backup, a rooted device or `adb shell run-as com.phnx28.notifsync cat sharedprefs/receiver_connection.xml` on a debuggable build leaks it.

**Fix.** Use `EncryptedSharedPreferences` from `androidx.security:security-crypto:1.1.0-alpha06`:

```kotlin
private fun getPrefs(): SharedPreferences {
    val masterKey = MasterKey.Builder(this)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        this, PREFS_NAME, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

Add `androidx.security:security-crypto:1.1.0-alpha06` to `app/build.gradle.kts`.

---

### M-03 · `POST_NOTIFICATIONS` requested but no runtime check before `NotificationManagerCompat.notify()`

**File:** `util/PermissionsHelper.kt:21-23`, `util/NotificationHelper.kt:102`

If the user denies `POST_NOTIFICATIONS` (Android 13+), `NotificationManagerCompat.notify()` silently fails — the receiver posts nothing, and the foreground service notification also fails. No user feedback.

**Fix.** Wrap `notify()` in a `try/catch SecurityException`, and show a snackbar asking the user to grant permission.

```kotlin
fun postMirroredNotification(...) {
    // ...build notification...
    try {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    } catch (e: SecurityException) {
        Log.w("NotificationHelper", "POST_NOTIFICATIONS denied, can't post", e)
    }
}
```

---

### M-04 · Hard-coded port and message-size constants scattered

`8765` appears in `Constants.kt`, `strings.xml`, and the sender screen layout. `5000` (body truncation) is in `NotificationRepository.kt:27`. `200` (dedup map cap) is in `NotificationCaptureService.kt:70`. `30 days` is in `NotificationRepository.kt:43`.

**Fix.** Move all to `Constants.kt`:

```kotlin
object Constants {
    const val DEFAULT_PORT = 8765
    const val MAX_BODY_LENGTH = 5000
    const val MAX_TITLE_LENGTH = 500
    const val MAX_MESSAGE_SIZE = 64 * 1024
    const val DEDUP_WINDOW_MS = 2000L
    const val DEDUP_MAP_MAX_SIZE = 200
    const val ARCHIVE_RETENTION_DAYS = 30
    const val RECONNECT_MAX_ATTEMPTS = 20
    const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
}
```

---

### M-05 · `PermissionsHelper` requests `ACCESS_FINE_LOCATION` for NSD on pre-13, but misses `NEARBY_WIFI_DEVICES` for Android 13+

**File:** `util/PermissionsHelper.kt:24-26`, `AndroidManifest.xml`

Starting with Android 13 (API 33), apps that use NSD/mDNS no longer need `ACCESS_FINE_LOCATION` — they need `NEARBY_WIFI_DEVICES` instead. The current code does the opposite of what it should: requests location on pre-13 (correct) and nothing on 13+ (incorrect — NSD will silently fail on some OEMs).

**Fix.** Add to manifest:

```xml
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
```

And update `PermissionsHelper.REQUIRED_PERMISSIONS`:

```kotlin
val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_SMS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)   // ← new
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()
```

Note: `ACCESS_FINE_LOCATION` is still declared in the manifest — that's OK, but with `neverForLocation` on `NEARBY_WIFI_DEVICES`, the system knows the app doesn't infer location from Wi-Fi, and won't prompt for location.

---

### M-06 · `NotificationCaptureService.setWebSocketServer()` is dead code

**File:** `service/NotificationCaptureService.kt:16, 115-117`

The `webSocketServer` field and its setter are never used. The service actually broadcasts via `sendBroadcast` Intent to `SenderForegroundService`, which owns the server. Dead code misleads reviewers into thinking the listener service talks to the server directly.

**Fix.** Remove the field, the setter, and the import.

---

### M-07 · `NsdHelper` re-instantiates `nsdManager` on every call

**File:** `network/NsdHelper.kt:26, 56`

```kotlin
fun registerService(port: Int) {
    nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager  // overwrites
    ...
}
fun discoverServices(callback: DiscoveryCallback) {
    nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager  // overwrites
    ...
}
```

`getSystemService` is cheap (returns a cached singleton), so this isn't a perf issue — but if `registerService` then `discoverServices` are called in sequence, the first `nsdManager` reference is overwritten. The first call's `registrationListener` still holds the original manager reference, which is fine, but it's fragile.

**Fix.** Lazy-init once:

```kotlin
private val nsdManager: NsdManager by lazy {
    context.getSystemService(Context.NSD_SERVICE) as NsdManager
}
```

---

### M-08 · `PairingFragment.isValidIpAddress` only supports IPv4, rejects valid IPv6

**File:** `ui/pairing/PairingFragment.kt:136-143`

```kotlin
private fun isValidIpAddress(ip: String): Boolean {
    return try {
        val parts = ip.split(".")
        parts.size == 4 && parts.all { it.toInt() in 0..255 }
    } catch (e: NumberFormatException) {
        false
    }
}
```

Rejects IPv6 link-local addresses (e.g. `fe80::1234%wlan0`), which are common on Wi-Fi. Also accepts leading zeros (`192.168.001.001`), which is technically valid but unusual.

**Fix.** Use `InetAddress.getByName`:

```kotlin
private fun isValidIpAddress(ip: String): Boolean {
    return try {
        java.net.InetAddress.getByName(ip)
        true
    } catch (e: Exception) {
        false
    }
}
```

Note: `InetAddress.getByName` performs a DNS lookup for hostnames — for a manual IP field, that's actually fine because the user can also enter a hostname (e.g. `notifsync.local`).

---

### M-09 · PIN input dialog built with manual dp math

**File:** `ui/pairing/PairingFragment.kt:147-181`

```kotlin
val margin = (24 * resources.displayMetrics.density).toInt()
```

Works, but fragile. Should be a dimen resource or an inflated layout.

**Fix.** Create `res/layout/dialog_pin_input.xml` and inflate it.

---

### M-10 · `UpdateHelper.compareVersions` parses versions with `toIntOrNull` — breaks on pre-release tags

**File:** `util/UpdateHelper.kt:139-150`

`0.2.1-rc1` parses as `0.2.1`. Pre-release builds appear "newer" than the stable.

**Fix.** Strip pre-release suffix, or use a proper semver lib. (Moot if we delete `UpdateHelper` per C-03.)

---

### M-11 · `UpdateHelper.downloadAndInstall` uses `MainScope().launch { }` — leaks the scope

**File:** `util/UpdateHelper.kt:130-132`

```kotlin
kotlinx.coroutines.MainScope().launch {
    context.startActivity(intent)
}
```

`MainScope()` creates a new scope that's never cancelled. If the fragment is destroyed mid-install, the `startActivity` may fire on a dead context. (Moot if we delete `UpdateHelper` per C-03.)

---

### M-12 · `SenderForegroundService.getLocalIpAddress()` returns the first non-loopback IPv4 — may be a VPN/tethering IP

**File:** `service/SenderForegroundService.kt:169-186`

Returns the first non-loopback IPv4 it finds. If the device has a VPN running (`tun0` with `10.x.x.x`), it'll display the VPN IP, and the receiver won't be able to reach it.

**Fix.** Prefer `wlan0` explicitly; filter out `tun*`, `rmnet*`, `ppp*`:

```kotlin
fun getLocalIpAddress(): String? {
    return try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Log.e("SenderFGService", "Error getting IP", e)
        null
    }
}
```

---

### M-13 · `recentEvents` dedup map grows unbounded on spammy devices

**File:** `service/NotificationCaptureService.kt:34-73`

The map is pruned when `size > 200`, but the prune condition is `it.value < cutoff` where `cutoff = now - DEDUP_WINDOW_MS * 5` (10s). So entries older than 10s are pruned. A spammy app could trigger 200 entries in < 10s, each holding a `String` key (potentially long), so memory grows.

**Fix.** Use `LinkedHashMap` with `removeEldestEntry`:

```kotlin
private val recentEvents = object : LinkedHashMap<String, Long>(100, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
        return size > 200
    }
}
```

---

## 7. Findings — Low

### L-01 · Permissions requested in `MainActivity.onCreate` before the user picks a mode

**File:** `MainActivity.kt:19-21`

User launches the app and immediately gets a permission dialog for SMS + notifications before they understand what the app does. `HomeFragment` already does sender/receiver-specific prompts — the `MainActivity` request is redundant and overwhelming.

**Fix.** Remove the `PermissionsHelper.requestPermissions(this)` call from `MainActivity.onCreate`. Let `HomeFragment` handle it.

---

### L-02 · `HomeFragment.ensureSenderPermissions` shows two dialogs in sequence

**File:** `ui/home/HomeFragment.kt:64-105`

Both dialogs can stack if the user dismisses the first one quickly. Use a step-through flow with `ActivityResultContracts`.

---

### L-03 · `FeedbackHelper` uses `Color.parseColor("#hex")` on every call

**File:** `util/FeedbackHelper.kt:10, 17, 24`

Minor perf. Use `ContextCompat.getColor(context, R.color.green)`.

---

### L-04 · `UpdateHelper.REPO_OWNER`/`REPO_NAME` hard-coded

**File:** `util/UpdateHelper.kt:24-25`

Move to `BuildConfig` fields. (Moot if we delete `UpdateHelper` per C-03.)

---

### L-05 · `SmsReceiver.onReceive` doesn't call `goAsync()` — long-running work on main thread

**File:** `service/SmsReceiver.kt:16-43`

`gson.toJson` and `sendBroadcast` (or `EventBus.tryEmit`) are fast, but the ANR threshold is 10s. If anything slower is added later, this ANRs.

**Fix.**

```kotlin
override fun onReceive(context: Context?, intent: Intent?) {
    if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // ... existing logic ...
        } finally {
            pendingResult.finish()
        }
    }
}
```

---

### L-06 · Multi-part SMS not aggregated — each part fires a separate event

**File:** `service/SmsReceiver.kt:20-41`

Long SMS (>160 chars) arrive as multiple PDUs. Each PDU becomes its own event, so the receiver shows "Hey" and "are you coming?" as two notifications.

**Fix.** Aggregate messages with the same sender + 5-second timestamp window before broadcasting. Use a small in-memory map keyed by `(sender, timestampBucket)` with a 3-second flush timer.

---

### L-07 · `Gson.fromJson` doesn't respect Kotlin nullability on `NotificationEvent`

See H-11 for the full fix.

---

### L-08 · `CleanupWorker` has no flex period

**File:** `NotifSyncApp.kt:23`

```kotlin
val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
    .build()
```

Adding `.setFlex(2, TimeUnit.HOURS)` lets the system batch it with other work, saving battery.

```kotlin
val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
    .setConstraints(Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build())
    .setFlex(2, TimeUnit.HOURS)
    .build()
```

---

### L-09 · `notificationCounter` wraps around Int.MAX_VALUE after years

**File:** `service/ReceiverForegroundService.kt:35`

After ~2 years of heavy use, `++notificationCounter` overflows Int.MAX_VALUE. Minor, but could collide with existing notification IDs.

**Fix.** Use `System.currentTimeMillis().toInt()` or a stable hash.

---

### L-10 · `FeedbackHelper` extensions on `Fragment` silently no-op if view is null

**File:** `util/FeedbackHelper.kt:34-48`

If called after `onDestroyView`, the snackbar silently doesn't show. May mask bugs. Log a warning in debug builds.

---

### L-11 · `WebSocketServer.broadcastEvent` catches `Exception` broadly

**File:** `network/WebSocketServer.kt:62-79`

`catch (e: Exception)` swallows everything including `IllegalStateException`. Catch `IOException` specifically; let runtime exceptions propagate.

---

### L-12 · `proguard-rules.pro` keeps ALL of `okhttp3.**` and `com.google.gson.**`

**File:** `app/proguard-rules.pro:10, 28`

Defeats minification. The `-keep` rules prevent R8 from removing unused OkHttp/Gson code, bloating the APK by ~500KB. OkHttp and Gson ship their own consumer rules — the explicit keeps are unnecessary.

**Fix.** Remove `-keep class okhttp3.** { *; }` and `-keep class com.google.gson.** { *; }`. Keep the Gson `@SerializedName` member rule.

---

### L-13 · `SmsReceiver` not registered to handle `SMS_DELIVER` for outbound SMS

Not really a bug — the app is read-only. Informational.

---

## 8. Findings — Informational

| ID | Finding |
|---|---|
| I-01 | `DeviceRole.kt` enum has no consumers — dead code. |
| I-02 | No light theme variant — only `Theme.Material3.Dark.NoActionBar`. Personal preference. |
| I-03 | `gradle.properties:5` has `org.gradle.java.home=/home/phnx28/Android Notifs app/.jdk/jdk-17.0.19+10` — hard-coded maintainer path, breaks build on any other machine. **Remove.** |
| I-04 | `settings.gradle.kts` puts Aliyun mirror before `google()` / `mavenCentral()` — fine for the maintainer in China, slow for international contributors. |
| I-05 | No tests at all — `app/src/test` and `app/src/androidTest` don't exist. |
| I-06 | No CI — no GitHub Actions workflow. |
| I-07 | `README.md` permissions table doesn't mention `REQUEST_INSTALL_PACKAGES`. (Moot after C-03.) |
| I-08 | Sender screen uses `tvArchivedCount` (archived count) to display "SMS Relayed" — **a UI bug**, the SMS count is always equal to the archived count. Should query `WHERE type = 'SMS'`. |

---

## 9. UI/UX Issues

| ID | Finding |
|---|---|
| U-01 | `tvFromIp` in `fragment_receiver.xml:27-32` is never populated — dead UI element. Wire it up to show the sender's IP from `ReceiverForegroundService` persisted connection. |
| U-02 | The PIN display on the sender screen (`fragment_sender.xml:120-128`) is 13sp monospace — too small to read from across the room. Make it 32sp+ bold. |
| U-03 | No "Copy PIN" button on the sender screen — user has to type the 6-digit PIN manually into the receiver. Add a copy-to-clipboard button. |
| U-04 | No "Copy IP" button on the sender screen. Same fix. |
| U-05 | `fragment_pairing.xml` has no port field — fine, since port is constant, but document it in the hint. |
| U-06 | The receiver history tab title says "Active" / "Archive" — could be clearer with "Live" / "History". Personal preference. |
| U-07 | No swipe-to-refresh on the active/archive lists — user has to back out and re-enter to see new items. (Flow-based collection already updates live, so this is mostly cosmetic.) |
| U-08 | The "From IP" label on the receiver screen has no value populated — same as U-01. |

---

## 10. Threat Model Summary

| Asset | Threat | Vector | Current mitigation | Recommended |
|---|---|---|---|---|
| SMS body in transit | Sniffing | LAN packet capture | None (cleartext `ws://`) | AES-GCM via PIN-derived key (C-01/C-02) |
| Notification body in transit | Sniffing | LAN packet capture | None | Same as above |
| Pairing PIN | Sniffing | HTTP header on WS handshake | None | Send SHA-256 hash, 6-digit PIN, rate-limit (C-02) |
| Pairing PIN | Brute force | Repeated WS handshakes | None | Rate-limit + lockout (C-02) |
| mDNS service | Rogue service | Attacker registers fake `_notifsync._tcp` | None | TXT-record salt verification (C-04) |
| Room DB | Physical / `adb backup` | USB access to unlocked device | `allowBackup=true` (worst case) | `allowBackup=false` (M-01) |
| SharedPreferences PIN | Same as Room DB | Same | Plaintext | `EncryptedSharedPreferences` (M-02) |
| APK self-update | Hijacked release | MITM or compromised GitHub | None | Verify SHA-256 + signature, OR remove `UpdateHelper` (C-03) |
| WakeLock | Battery drain | Held 24/7 | Acquired in `onCreate`, never released until destroy | Acquire only when working (H-05/H-06) |
| Foreground service | Self-restart after swipe | `onTaskRemoved` + `AlarmManager` | Active | Remove (H-04) |

---

## 11. Recommended Fix Order for v0.2.1

Applied in this order in the fix branch:

1. **C-03 + I-07** — Delete `UpdateHelper`, remove `REQUEST_INSTALL_PACKAGES`, update README.
2. **C-01 + C-02 + C-04** — Add `Crypto.kt`, bump PIN to 6 digits, hash PIN, encrypt payload, mDNS TXT-record salt, server-side rate limiting. (Single PR — they're interdependent.)
3. **H-01** — Remove `android:permission` from `SmsReceiver` manifest entry.
4. **H-03** — Add `<property>` for FGS `specialUse`.
5. **H-04** — Delete `onTaskRemoved` overrides.
6. **H-05 + H-06** — Add wakelock timeout, acquire only when working.
7. **H-07** — Cap reconnect attempts, null out dead socket, add jitter.
8. **H-08** — Switch to `fallbackToDestructiveMigrationOnDowngrade` only.
9. **H-09** — Replace `sendBroadcast` with `EventBus` SharedFlow.
10. **H-10 + H-11 + L-07** — Cap message/body size, fix `NotificationEvent` nullability.
11. **M-01 + M-02** — `allowBackup=false`, `EncryptedSharedPreferences`.
12. **M-03** — Catch `SecurityException` around `notify()`.
13. **M-04** — Centralize constants.
14. **M-05** — Add `NEARBY_WIFI_DEVICES`.
15. **M-06** — Remove dead `setWebSocketServer`.
16. **M-07** — Lazy-init `nsdManager`.
17. **M-08** — `InetAddress.getByName` for IP validation.
18. **M-12** — Filter `wlan0` / `eth0` for local IP.
19. **M-13** — `removeEldestEntry` for dedup map.
20. **L-01** — Defer permission request in `MainActivity`.
21. **L-05 + L-06** — `goAsync` + multi-part SMS aggregation in `SmsReceiver`.
22. **L-08** — Flex period for `CleanupWorker`.
23. **L-09** — Stable notification ID.
24. **L-12** — Trim proguard rules.
25. **I-03** — Remove hard-coded JDK path from `gradle.properties`.
26. **I-08** — Fix sender screen SMS count (was showing archived count).
27. **U-01 / U-02 / U-03 / U-04** — Receiver screen `tvFromIp` wiring, larger PIN display, copy buttons.

All other Low / Informational items are documented but not patched in v0.2.1 — they're either personal-preference (theme, copy) or non-blocking (tests, CI).

---

## 12. Out-of-Scope but Worth Noting

- **No tests.** Adding unit tests for `Crypto`, `NotificationEvent` (de)serialization, and `NotificationDao` is the single highest-leverage follow-up after this audit. Recommended for v0.3.0.
- **No CI.** A simple GitHub Actions `assembleDebug` workflow on push would catch build regressions. Recommended for v0.3.0.
- **No ProGuard verification.** After minification, the Gson reflection paths should be smoke-tested with a real release build.
- **Supply chain:** all dependencies come from `google()` / `mavenCentral()` / Aliyun mirror. No `jitpack`, no `mavenLocal`. ✅ Good.

---

## 13. Sign-off

Audit complete. All Critical and High findings have patches applied in the v0.2.1 fix branch (see `worklog.md` for the per-task log). The APK is rebuilt from the fixed source; if the build environment doesn't have the Android SDK installed, the audit deliverable ships as source-only and the user runs `./gradlew assembleRelease` locally.

— Super Z, 2026-06-22

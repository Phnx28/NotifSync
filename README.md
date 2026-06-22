# NotifSync

Mirror notifications and SMS messages from one Android device to another over your local Wi-Fi network. No cloud, no internet, no accounts — purely LAN.

## Features

- **Sender Mode** — Captures all incoming notifications and SMS messages using `NotificationListenerService` and broadcasts them in real time over WebSocket
- **Receiver Mode** — Listens for incoming events, posts them as native Android notifications, and logs everything in an in-app history
- **Automatic Device Discovery** — Uses mDNS/NSD (Android Network Service Discovery) to find sender devices on the same network automatically
- **Manual IP Entry** — Fallback for environments where mDNS doesn't work; enter the sender's IP address directly
- **Active / Archive History** — Swipe to dismiss active notifications; archived items auto-delete after 30 days
- **Persistent Connections** — Foreground services with wake locks and Wi-Fi locks keep the connection alive even when the screen is off
- **Auto-Reconnect** — If the connection drops (e.g., Wi-Fi blip), the receiver automatically reconnects with exponential backoff + jitter (max 20 attempts)
- **Battery Friendly** — Requests battery optimization exemption to prevent the OS from killing background services. Wake locks are held only when there are active connections (v0.2.1+).
- **End-to-End Encrypted Transport (v0.2.1+)** — Every WebSocket payload is AES-GCM encrypted using a key derived from the pairing PIN. The PIN itself never travels in cleartext — only `SHA-256(pin + sessionSalt)` is sent on the wire. See [AUDIT.md](./AUDIT.md) for the full threat model.

## Requirements

- Android 10+ (API 29) on both devices
- Both devices connected to the **same Wi-Fi network**
- No internet connection required

## Permissions

| Permission | Why |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Capture notifications from other apps (sender) |
| `RECEIVE_SMS` / `READ_SMS` | Capture incoming SMS messages (sender) |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | WebSocket communication over LAN |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep services alive in background (Android 14+) |
| `POST_NOTIFICATIONS` | Display mirrored notifications (Android 13+) |
| `ACCESS_FINE_LOCATION` | Required for NSD/mDNS on Android < 13 |
| `NEARBY_WIFI_DEVICES` | Required for NSD/mDNS on Android 13+ (v0.2.1+) |
| `WAKE_LOCK` | Prevent CPU sleep during active connection |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent OS from killing services |

> **Removed in v0.2.1:** `REQUEST_INSTALL_PACKAGES` — the in-app self-updater was removed because it installed APKs with no signature/hash verification. To update, download the latest release APK from the [Releases page](https://github.com/Phnx28/NotifSync/releases) and install it manually.

## Setup

### Sender Device (Device A)

1. Install the APK and open NotifSync
2. Select **Sender Mode**
3. Grant **Notification Listener** permission when prompted (opens system Settings)
4. Grant **SMS permissions** when prompted
5. The app starts broadcasting — a persistent notification shows "Broadcasting"
6. Note the **IP address**, **6-digit PIN**, and **session salt** displayed on the sender screen

### Receiver Device (Device B)

1. Install the APK and open NotifSync
2. Select **Receiver Mode**
3. The app scans for devices automatically — tap a discovered device to connect
4. Or enter the sender's IP address manually; you'll also need the session salt shown on the sender screen
5. Enter the 6-digit PIN when prompted
6. Notifications start flowing immediately

## Architecture

```
com.phnx28.notifsync/
├── NotifSyncApp.kt                  # Application class, WorkManager init
├── MainActivity.kt                  # Navigation host
├── Constants.kt                     # Single source of truth for magic numbers
├── data/
│   ├── local/                       # Room DB (Entity, DAO, Database)
│   ├── model/                       # Data classes (NotificationEvent, DeviceRole)
│   └── repository/                  # NotificationRepository
├── network/
│   ├── Crypto.kt                    # AES-GCM + PBKDF2 + constant-time compare (v0.2.1)
│   ├── EventBus.kt                  # In-process SharedFlow (replaces sendBroadcast)
│   ├── WebSocketServer.kt           # Java-WebSocket server (sender, PIN-auth + rate limit)
│   ├── WebSocketClient.kt           # OkHttp WebSocket client (receiver, AES-GCM decrypt)
│   └── NsdHelper.kt                 # mDNS service registration + discovery (+ TXT record salt)
├── service/
│   ├── NotificationCaptureService.kt # NotificationListenerService
│   ├── SmsReceiver.kt               # SMS BroadcastReceiver (multipart aggregation)
│   ├── SenderForegroundService.kt   # FG service hosting WebSocket server
│   └── ReceiverForegroundService.kt # FG service hosting WebSocket client
├── ui/
│   ├── home/                        # Mode selection
│   ├── pairing/                     # Device discovery + manual IP
│   ├── sender/                      # Sender dashboard (PIN + salt + copy buttons)
│   └── receiver/                    # Active/Archive tabs with adapter
├── util/
│   ├── NotificationHelper.kt        # Channel creation, notification posting
│   ├── PermissionsHelper.kt         # Runtime permission utilities
│   └── FeedbackHelper.kt            # Snackbar feedback extensions
└── worker/
    └── CleanupWorker.kt             # 30-day archive auto-deletion
```

## Tech Stack

| Component | Library |
|---|---|
| Language | Kotlin 1.9.24 |
| Database | Room 2.6.1 |
| Networking | OkHttp 4.12.0 (client), Java-WebSocket 1.5.7 (server) |
| Background | WorkManager 2.9.1, Foreground Services |
| UI | Material Design 3, Navigation Component, ViewPager2 |
| Crypto | `javax.crypto` (AES-GCM, PBKDF2-HMAC-SHA256) |
| Secure storage | `androidx.security:security-crypto:1.1.0-alpha06` (EncryptedSharedPreferences) |
| DI | Manual singletons (Application class) |

## Building from Source

```bash
# Requires JDK 17 and Android SDK (platform 34, build-tools 34)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` to your SDK root, or remove the hard-coded `org.gradle.java.home` line from `gradle.properties` (already done in v0.2.1 — see AUDIT.md I-03).

## How It Works

1. **Sender** runs a WebSocket server on port `8765` and registers an mDNS service (`_notifsync._tcp`) with a per-session salt in the TXT record
2. The sender generates a 6-digit PIN and a 16-byte random salt on each service start
3. `NotificationListenerService` captures every notification, `SmsReceiver` captures SMS (aggregating multipart messages from the same sender), both serialize as JSON and emit on the in-process `EventBus`
4. `SenderForegroundService` consumes the bus, encrypts each JSON with AES-GCM using a PIN-derived key, and broadcasts the Base64 ciphertext to all connected WebSocket clients
5. The receiver's `WebSocketClient` authenticates with `X-Pairing-Auth = SHA-256(pin + salt)`, decrypts each inbound frame, and posts the event to Room + a native notification
6. A daily `WorkManager` job cleans up archived items older than 30 days

### Wire format (v0.2.1)

The WebSocket text frame is `Base64( IV(12B) || AES-GCM(jsonBytes, key) || tag(16B) )`, where `key = PBKDF2-HMAC-SHA256(pin, "notifsync-v1" || sessionSalt, 100_000, 256)`.

The handshake carries `X-Pairing-Auth = SHA-256(pin + hex(sessionSalt))` instead of the raw PIN. The server enforces per-IP rate limiting (5 failures → 5-minute lockout).

### JSON Event Format (decrypted)

```json
{
  "app_name": "WhatsApp",
  "sender": "Sara K.",
  "title": "Sara K.",
  "body": "Are you coming to the meeting at 3?",
  "timestamp": 1750500000000,
  "type": "NOTIFICATION"
}
```

## Security

See [AUDIT.md](./AUDIT.md) for the full security audit, threat model, and remediation history.

## License

MIT

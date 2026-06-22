# NotifSync

Mirror notifications and SMS messages from one Android device to another over your local Wi-Fi network. No cloud, no internet, no accounts — purely LAN.

## Features

- **Sender Mode** — Captures notifications and SMS using `NotificationListenerService` and broadcasts them over WebSocket
- **Receiver Mode** — Listens for incoming events, posts them as native notifications, and logs them in an in-app history
- **Automatic Device Discovery** — mDNS/NSD finds sender devices on the same network automatically
- **Manual IP Entry** — Fallback when mDNS doesn't work
- **Active / Archive History** — Swipe to dismiss; archived items auto-delete after 30 days
- **Auto-Reconnect** — Exponential backoff with jitter (max 20 attempts)
- **End-to-End Encrypted Transport** — Every WebSocket payload is AES-GCM encrypted with a key derived from the pairing PIN. The PIN never travels in cleartext. See [AUDIT.md](./AUDIT.md) for details.

## Requirements

- Android 10+ (API 29) on both devices
- Both devices on the **same Wi-Fi network**
- No internet connection required

## Setup

### Sender Device (Device A)

1. Install the APK and open NotifSync
2. Select **Sender Mode**
3. Grant **Notification Listener** and **SMS permissions** when prompted
4. Note the **IP address**, **6-digit PIN**, and **session salt** shown on the sender screen

### Receiver Device (Device B)

1. Install the APK and open NotifSync
2. Select **Receiver Mode**
3. Tap a discovered device to connect — or enter the sender's IP and session salt manually
4. Enter the 6-digit PIN when prompted
5. Notifications start flowing immediately

## Building from Source

```bash
# Requires JDK 17 and Android SDK (platform 34, build-tools 34)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Releases are built automatically by GitHub Actions on every `v*.*.*` tag. See [Actions](https://github.com/Phnx28/NotifSync/actions) for build history and [Releases](https://github.com/Phnx28/NotifSync/releases) for downloadable APKs.

## Tech Stack

| Component | Library |
|---|---|
| Language | Kotlin 1.9.24 |
| Database | Room 2.6.1 |
| Networking | OkHttp 4.12.0, Java-WebSocket 1.5.7 |
| Background | WorkManager 2.9.1, Foreground Services |
| UI | Material Design 3, Navigation Component, ViewPager2 |
| Crypto | AES-GCM, PBKDF2-HMAC-SHA256, EncryptedSharedPreferences |

## Security

See [AUDIT.md](./AUDIT.md) for the full security audit, threat model, and remediation history.

## License

MIT

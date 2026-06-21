# NotifSync

Mirror notifications and SMS messages from one Android device to another over your local Wi-Fi network. No cloud, no internet, no accounts — purely LAN.

## Features

- **Sender Mode** — Captures all incoming notifications and SMS messages using `NotificationListenerService` and broadcasts them in real time over WebSocket
- **Receiver Mode** — Listens for incoming events, posts them as native Android notifications, and logs everything in an in-app history
- **Automatic Device Discovery** — Uses mDNS/NSD (Android Network Service Discovery) to find sender devices on the same network automatically
- **Manual IP Entry** — Fallback for environments where mDNS doesn't work; enter the sender's IP address directly
- **Active / Archive History** — Swipe to dismiss active notifications; archived items auto-delete after 30 days
- **Persistent Connections** — Foreground services with wake locks and Wi-Fi locks keep the connection alive even when the screen is off
- **Auto-Reconnect** — If the connection drops (e.g., Wi-Fi blip), the receiver automatically reconnects with exponential backoff
- **Battery Friendly** — Requests battery optimization exemption to prevent the OS from killing background services

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
| `FOREGROUND_SERVICE` | Keep services alive in background |
| `POST_NOTIFICATIONS` | Display mirrored notifications (Android 13+) |
| `ACCESS_FINE_LOCATION` | Required for NSD/mDNS on some devices |
| `WAKE_LOCK` | Prevent CPU sleep during active connection |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent OS from killing services |

## Setup

### Sender Device (Device A)

1. Install the APK and open NotifSync
2. Select **Sender Mode**
3. Grant **Notification Listener** permission when prompted (opens system Settings)
4. Grant **SMS permissions** when prompted
5. The app starts broadcasting — a persistent notification shows "Broadcasting"
6. Note the IP address displayed in the sender notification or settings

### Receiver Device (Device B)

1. Install the APK and open NotifSync
2. Select **Receiver Mode**
3. The app scans for devices automatically — tap a discovered device to connect
4. Or enter the sender's IP address manually and tap **Connect**
5. Notifications start flowing immediately

## Architecture

```
com.phnx28.notifsync/
├── NotifSyncApp.kt                  # Application class, WorkManager init
├── MainActivity.kt                  # Navigation host
├── data/
│   ├── local/                       # Room DB (Entity, DAO, Database)
│   ├── model/                       # Data classes (NotificationEvent, DeviceRole)
│   └── repository/                  # NotificationRepository
├── network/
│   ├── WebSocketServer.kt           # Java-WebSocket server (sender)
│   ├── WebSocketClient.kt           # OkHttp WebSocket client (receiver)
│   └── NsdHelper.kt                 # mDNS service registration + discovery
├── service/
│   ├── NotificationCaptureService.kt # NotificationListenerService
│   ├── SmsReceiver.kt               # SMS BroadcastReceiver
│   ├── SenderForegroundService.kt   # FG service hosting WebSocket server
│   └── ReceiverForegroundService.kt # FG service hosting WebSocket client
├── ui/
│   ├── home/                        # Mode selection
│   ├── pairing/                     # Device discovery + manual IP
│   ├── sender/                      # Sender dashboard
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
| Language | Kotlin |
| Database | Room |
| Networking | OkHttp (client), Java-WebSocket (server) |
| Background | WorkManager, Foreground Services |
| UI | Material Design 3, Navigation Component, ViewPager2 |
| DI | Manual singletons (Application class) |

## Building from Source

```bash
# Requires JDK 17 and Android SDK (platform 34, build-tools 34)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## How It Works

1. **Sender** runs a WebSocket server on port `8765` and registers an mDNS service (`_notifsync._tcp`)
2. `NotificationListenerService` captures every notification, serializes it as JSON, and broadcasts it
3. `SmsReceiver` captures incoming SMS and serializes them the same way
4. **Receiver** discovers the sender via mDNS (or manual IP), connects as a WebSocket client
5. Each received JSON event is stored in Room DB and posted as a native Android notification
6. A daily `WorkManager` job cleans up archived items older than 30 days

### JSON Event Format

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

## License

MIT

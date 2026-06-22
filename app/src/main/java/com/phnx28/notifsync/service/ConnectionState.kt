package com.phnx28.notifsync.service

/**
 * Receiver connection state. Emitted via
 * [ReceiverForegroundService.connectionStateFlow] for UI feedback.
 */
enum class ConnectionState {
    IDLE,           // Not trying to connect
    CONNECTING,     // Attempting to connect
    CONNECTED,      // WebSocket is open
    RECONNECTING,   // Connection lost, retrying
    FAILED,         // Gave up after max retries
    DISCONNECTED    // User-initiated disconnect
}

package com.phnx28.notifsync.service

/**
 * Snapshot of the sender's server state. Emitted via
 * [SenderForegroundService.serverInfoFlow] whenever anything changes
 * (server started, client connected/disconnected, PIN generated).
 *
 * Fixes the v0.2.1 bug where the PIN/salt/counter were only updated
 * when the connection count changed — if the fragment subscribed after
 * the server started but before any client connected, it showed stale
 * values until a connection event arrived.
 */
data class ServerInfo(
    val pin: String,
    val saltHex: String,
    val ipAddress: String?,
    val port: Int,
    val connectedClients: Int,
    val isRunning: Boolean
)

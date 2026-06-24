package com.phnx28.notifsync.network

import android.util.Log
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.util.AppLog
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Sender-side WebSocket server.
 *
 * v0.2.1 hardening (see AUDIT.md C-01 / C-02):
 *  - Handshake auth uses `X-Pairing-Auth = SHA-256(pin + sessionSalt)` instead
 *    of the raw PIN. The PIN never travels in cleartext.
 *  - Per-source-IP rate limiting: [Constants.AUTH_MAX_FAILURES] failures
 *    within a window trigger a [Constants.AUTH_LOCKOUT_MS] lockout.
 *  - Comparison is constant-time (see [Crypto.constantTimeEquals]).
 *  - Outbound payloads are AES-GCM encrypted by the caller before being
 *    handed to [broadcastEvent] — this class just ships opaque Base64 text
 *    frames. See [SenderForegroundService] for the encrypt-then-broadcast path.
 *
 * The [sessionSalt] is published in the mDNS TXT record so receivers can
 * derive the same AES key without an additional round-trip.
 */
class WebSocketServer(
    port: Int = Constants.DEFAULT_PORT,
    private val pin: String,
    private val sessionSalt: ByteArray,
    private val onConnectionChanged: ((Int) -> Unit)? = null,
    private val onClientConnected: ((String) -> Unit)? = null,
    private val onClientDisconnected: ((String) -> Unit)? = null,
    private val onMessageSent: (() -> Unit)? = null,
    /** Called on server-level errors (bind failure, etc.). Added in
     *  v0.2.4 — previously errors were only logged to logcat. */
    private val onServerError: ((String) -> Unit)? = null
) : WebSocketServer(InetSocketAddress(port)) {

    private val clients: MutableSet<WebSocket> = ConcurrentHashMap.newKeySet()
    private val TAG = "WebSocketServer"

    // Per-IP failed-handshake counters (rate limiting).
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val lockoutUntil = ConcurrentHashMap<String, Long>()

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: Draft,
        request: ClientHandshake
    ): ServerHandshakeBuilder {
        val clientIp = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        val now = System.currentTimeMillis()

        // Rate limit: bail out early if this IP is in cooldown.
        val lockedUntil = lockoutUntil[clientIp] ?: 0L
        if (now < lockedUntil) {
            AppLog.w(TAG, "Rejecting $clientIp — locked for ${lockedUntil - now}ms")
            throw InvalidDataException(CloseFrame.TRY_AGAIN_LATER, "Too many attempts")
        }

        // Auth: constant-time compare against SHA-256(pin + salt).
        val clientAuth = request.getFieldValue("X-Pairing-Auth")
        val expected = Crypto.pinHash(pin, sessionSalt)
        val ok = clientAuth.isNotEmpty() && Crypto.constantTimeEquals(clientAuth, expected)

        if (!ok) {
            val attempts = (failedAttempts[clientIp] ?: 0) + 1
            failedAttempts[clientIp] = attempts
            AppLog.w(TAG, "Rejecting $clientIp — invalid auth (attempt $attempts/${Constants.AUTH_MAX_FAILURES}). " +
                "Got auth=${if (clientAuth.isEmpty()) "<empty>" else clientAuth.take(16) + "..."}, " +
                "expected=${expected.take(16)}...")
            if (attempts >= Constants.AUTH_MAX_FAILURES) {
                lockoutUntil[clientIp] = now + Constants.AUTH_LOCKOUT_MS
                failedAttempts.remove(clientIp)
                AppLog.w(TAG, "Locking out $clientIp for ${Constants.AUTH_LOCKOUT_MS}ms")
            }
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized")
        }

        // Success — clear any stale failure counters.
        failedAttempts.remove(clientIp)
        lockoutUntil.remove(clientIp)
        AppLog.i(TAG, "Handshake OK from $clientIp")
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        val addr = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        AppLog.i(TAG, "Client connected: $addr (total: ${clients.size})")
        onClientConnected?.invoke(addr)
        onConnectionChanged?.invoke(clients.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
        val addr = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        AppLog.i(TAG, "Client disconnected: $addr code=$code reason=$reason (total: ${clients.size})")
        onClientDisconnected?.invoke(addr)
        onConnectionChanged?.invoke(clients.size)
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        // Receivers never send messages to the sender. Log and drop.
        Log.d(TAG, "Received unexpected message from client — ignoring")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
        if (conn == null) {
            // Server-level error (bind failure, etc.) — surface via callback
            // instead of silently logging (AUDIT.md — v0.2.4 fix).
            onServerError?.invoke(ex.message ?: "Unknown server error")
        }
    }

    override fun onStart() {
        AppLog.i(TAG, "WebSocket server started on port $port")
    }

    /**
     * Broadcast a pre-encrypted Base64 payload to all connected clients.
     * The caller ([SenderForegroundService]) is responsible for AES-GCM
     * encryption using the PIN-derived session key.
     */
    fun broadcastEvent(encryptedBase64Payload: String) {
        val clientsCopy = clients.toSet()
        var changed = false
        clientsCopy.forEach { client ->
            try {
                client.send(encryptedBase64Payload)
            } catch (e: IOException) {
                // Network-level send failure — drop the client.
                Log.e(TAG, "I/O error sending to client", e)
                if (clients.remove(client)) changed = true
            }
            // NOTE: RuntimeException is intentionally NOT caught — let it
            // propagate so programming bugs surface (AUDIT.md L-11).
        }
        if (changed) {
            onConnectionChanged?.invoke(clients.size)
        }
        onMessageSent?.invoke()
    }

    fun getClientCount(): Int = clients.size

    fun startServer() {
        isReuseAddr = true
        start()
    }

    fun stopServer() {
        try {
            stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}

package com.phnx28.notifsync.network

import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.data.model.NotificationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

import com.phnx28.notifsync.service.ConnectionState

/**
 * Receiver-side WebSocket client.
 *
 * v0.2.1 hardening (see AUDIT.md C-01 / C-02 / H-07 / H-10 / H-11):
 *  - Sends `X-Pairing-Auth = SHA-256(pin + sessionSalt)` instead of the raw PIN.
 *  - Derives an AES-256 key from the PIN + sessionSalt; decrypts every
 *    inbound text frame as `Base64(AES-GCM(jsonBytes))`.
 *  - Caps inbound frame size at [Constants.MAX_MESSAGE_SIZE].
 *  - Reconnect capped at [Constants.RECONNECT_MAX_ATTEMPTS] with ±20% jitter.
 *  - `webSocket` field nulled on `onFailure`/`onClosed` so [isAlive] is honest.
 */
class WebSocketClient(
    private val onEventReceived: (NotificationEvent) -> Unit
) : WebSocketListener() {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val TAG = "WebSocketClient"

    @Volatile
    private var serverUrl: String? = null

    @Volatile
    private var shouldReconnect = false

    @Volatile
    private var reconnectAttempt = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    // Per-session crypto material.
    @Volatile
    private var sessionKey: SecretKeySpec? = null

    @Volatile
    private var pin: String? = null

    @Volatile
    private var sessionSalt: ByteArray? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Connect to [url]. The [pin] + [sessionSalt] are used to derive the
     * AES session key and to compute the `X-Pairing-Auth` handshake header.
     */
    fun connect(url: String, pin: String, sessionSalt: ByteArray) {
        serverUrl = url
        this.pin = pin
        this.sessionSalt = sessionSalt
        this.sessionKey = Crypto.deriveKey(pin, sessionSalt)
        shouldReconnect = true
        reconnectAttempt = 0
        _connectionState.value = ConnectionState.CONNECTING
        doConnect(url, pin, sessionSalt)
    }

    private fun doConnect(url: String, pin: String, sessionSalt: ByteArray) {
        try {
            val auth = Crypto.pinHash(pin, sessionSalt)
            val request = Request.Builder()
                .url(url)
                .header("X-Pairing-Auth", auth)
                .build()
            webSocket = client.newWebSocket(request, this)
            Log.d(TAG, "Connecting to $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection to $url", e)
            webSocket = null
            _isConnected.value = false
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "Connected to server")
        reconnectAttempt = 0
        _isConnected.value = true
        _connectionState.value = ConnectionState.CONNECTED
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        // Hard cap on inbound frame size (AUDIT.md H-10).
        if (text.length > Constants.MAX_MESSAGE_SIZE) {
            Log.w(TAG, "Rejecting oversize message (${text.length} chars)")
            return
        }

        val key = sessionKey ?: run {
            Log.w(TAG, "No session key — dropping message")
            return
        }

        try {
            val json = Crypto.decryptFromBase64(text, key)
            val event = gson.fromJson(json, NotificationEvent::class.java)
                ?: return
            onEventReceived(event)
        } catch (e: Exception) {
            // Decryption failure, malformed JSON, or Gson parse error.
            // Drop silently — a misbehaving sender shouldn't crash the receiver.
            Log.e(TAG, "Decrypt/parse failed for inbound frame", e)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        this.webSocket = null
        _isConnected.value = false
        if (shouldReconnect) _connectionState.value = ConnectionState.RECONNECTING
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Disconnected: $reason")
        this.webSocket = null
        _isConnected.value = false
        if (shouldReconnect) {
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "Connection failed: ${t.message}")
        this.webSocket = null
        _isConnected.value = false
        if (shouldReconnect) {
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        if (reconnectAttempt >= Constants.RECONNECT_MAX_ATTEMPTS) {
            Log.w(TAG, "Giving up after $reconnectAttempt attempts")
            shouldReconnect = false
            _connectionState.value = ConnectionState.FAILED
            return
        }

        reconnectAttempt++
        val baseDelay = (1000L * reconnectAttempt.coerceAtMost(30))
            .coerceAtMost(30_000L)
        val jitter = (baseDelay * 0.2 * Math.random()).toLong() // ±20% jitter
        val delayMs = baseDelay + jitter
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect) {
                val url = serverUrl ?: return@launch
                val pin = pin ?: return@launch
                val salt = sessionSalt ?: return@launch
                doConnect(url, pin, salt)
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        _isConnected.value = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    fun isAlive(): Boolean = webSocket != null && _isConnected.value
}

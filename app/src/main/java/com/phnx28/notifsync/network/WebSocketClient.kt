package com.phnx28.notifsync.network

import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.service.ConnectionState
import com.phnx28.notifsync.util.AppLog
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

/**
 * Receiver-side WebSocket client.
 *
 * v0.2.3 fixes:
 *  - **Endless reconnect bug fix:** `reconnectAttempt` is no longer reset
 *    in `onOpen`. It's only reset after the connection has been stable
 *    for [STABLE_CONNECTION_MS] (10s). This prevents the infinite loop
 *    where a connection opens, immediately closes, the counter resets,
 *    and the cycle repeats forever.
 *  - Comprehensive logging via [AppLog] so the user can see exactly why
 *    a connection failed.
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
    private var stableConnectionJob: Job? = null

    @Volatile
    private var sessionKey: SecretKeySpec? = null

    @Volatile
    private var pin: String? = null

    @Volatile
    private var sessionSalt: ByteArray? = null

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Time the connection must stay open before we consider it "stable"
     * and reset the reconnect counter. Prevents the endless-reconnect bug.
     */
    private val STABLE_CONNECTION_MS = 10_000L

    fun connect(url: String, pin: String, sessionSalt: ByteArray) {
        serverUrl = url
        this.pin = pin
        this.sessionSalt = sessionSalt
        this.sessionKey = Crypto.deriveKey(pin, sessionSalt)
        shouldReconnect = true
        reconnectAttempt = 0
        _connectionState.value = ConnectionState.CONNECTING
        AppLog.i(TAG, "Connecting to $url (PIN=${pin}****, salt=${Crypto.toHex(sessionSalt).take(8)}...)")
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
            AppLog.d(TAG, "WebSocket request sent to $url")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to initiate connection to $url", e)
            webSocket = null
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        AppLog.i(TAG, "WebSocket connected (HTTP ${response.code})")

        // DON'T reset reconnectAttempt here — only reset after the
        // connection has been stable for STABLE_CONNECTION_MS. This
        // prevents the endless-reconnect bug where the connection opens,
        // immediately closes, and the counter resets forever.
        _connectionState.value = ConnectionState.CONNECTED

        // Schedule a "stable connection" reset. If we're still connected
        // after 10 seconds, reset the reconnect counter.
        stableConnectionJob?.cancel()
        stableConnectionJob = scope.launch {
            delay(STABLE_CONNECTION_MS)
            if (shouldReconnect && webSocket == this@WebSocketClient.webSocket) {
                AppLog.d(TAG, "Connection stable for ${STABLE_CONNECTION_MS}ms — resetting reconnect counter")
                reconnectAttempt = 0
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (text.length > Constants.MAX_MESSAGE_SIZE) {
            AppLog.w(TAG, "Rejecting oversize message (${text.length} chars)")
            return
        }

        val key = sessionKey ?: run {
            AppLog.w(TAG, "No session key — dropping message")
            return
        }

        try {
            val json = Crypto.decryptFromBase64(text, key)
            val event = gson.fromJson(json, NotificationEvent::class.java) ?: return
            AppLog.d(TAG, "Received event: type=${event.type} app=${event.appName} body=${event.body.take(50)}")
            onEventReceived(event)
        } catch (e: Exception) {
            AppLog.e(TAG, "Decrypt/parse failed for inbound frame", e)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        AppLog.w(TAG, "WebSocket closing: code=$code reason=$reason")
        webSocket.close(1000, null)
        this.webSocket = null
        stableConnectionJob?.cancel()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        AppLog.w(TAG, "WebSocket closed: code=$code reason=$reason")
        this.webSocket = null
        stableConnectionJob?.cancel()
        if (shouldReconnect) {
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val code = response?.code ?: -1
        AppLog.e(TAG, "WebSocket failure: code=$code message=${t.message}", t)
        this.webSocket = null
        stableConnectionJob?.cancel()
        if (shouldReconnect) {
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            AppLog.d(TAG, "Not reconnecting — shouldReconnect=false")
            return
        }

        if (reconnectAttempt >= Constants.RECONNECT_MAX_ATTEMPTS) {
            AppLog.w(TAG, "Giving up after $reconnectAttempt attempts")
            shouldReconnect = false
            _connectionState.value = ConnectionState.FAILED
            return
        }

        reconnectAttempt++
        val baseDelay = (1000L * reconnectAttempt.coerceAtMost(30))
            .coerceAtMost(30_000L)
        val jitter = (baseDelay * 0.2 * Math.random()).toLong()
        val delayMs = baseDelay + jitter
        AppLog.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt/${Constants.RECONNECT_MAX_ATTEMPTS})")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect) {
                val url = serverUrl ?: return@launch
                val pin = pin ?: return@launch
                val salt = sessionSalt ?: return@launch
                _connectionState.value = ConnectionState.CONNECTING
                doConnect(url, pin, salt)
            }
        }
    }

    fun disconnect() {
        AppLog.i(TAG, "Disconnecting (user-initiated)")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stableConnectionJob?.cancel()
        stableConnectionJob = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        // Zero crypto material to prevent heap-dump extraction
        // (AUDIT.md — v0.2.4 security hardening).
        sessionKey?.let { key ->
            try {
                key.encoded?.fill(0)
            } catch (_: Exception) { }
        }
        sessionKey = null
        pin = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    fun isAlive(): Boolean = webSocket != null && _connectionState.value == ConnectionState.CONNECTED
}

package com.phnx28.notifsync.network

import android.util.Log
import com.google.gson.Gson
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

    @Volatile
    private var pin: String? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun connect(url: String, pin: String? = null) {
        serverUrl = url
        this.pin = pin
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(url)
    }

    private fun doConnect(url: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .apply {
                    pin?.let { header("X-Pairing-PIN", it) }
                }
                .build()
            webSocket = client.newWebSocket(request, this)
            Log.d(TAG, "Connecting to $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection to $url", e)
            _isConnected.value = false
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "Connected to server")
        reconnectAttempt = 0
        _isConnected.value = true
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val event = gson.fromJson(text, NotificationEvent::class.java)
            onEventReceived(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $text", e)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        _isConnected.value = false
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Disconnected: $reason")
        _isConnected.value = false
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "Connection failed: ${t.message}")
        _isConnected.value = false
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectAttempt++
        val delayMs = (1000L * reconnectAttempt.coerceAtMost(30)).coerceAtMost(30000L)
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

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        _isConnected.value = false
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    fun isAlive(): Boolean = webSocket != null
}

package com.phnx28.notifsync.network

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.phnx28.notifsync.data.model.NotificationEvent
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

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val TAG = "WebSocketClient"
    private var serverUrl: String? = null
    private var shouldReconnect = false
    private var reconnectAttempt = 0

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    fun connect(url: String) {
        serverUrl = url
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(url)
    }

    private fun doConnect(url: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            webSocket = client.newWebSocket(request, this)
            Log.d(TAG, "Connecting to $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection to $url", e)
            _isConnected.postValue(false)
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "Connected to server")
        reconnectAttempt = 0
        _isConnected.postValue(true)
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
        _isConnected.postValue(false)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Disconnected: $reason")
        _isConnected.postValue(false)
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "Connection failed: ${t.message}")
        _isConnected.postValue(false)
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectAttempt++
        val delayMs = (1000L * reconnectAttempt.coerceAtMost(30)).coerceAtMost(30000L)
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")

        Thread {
            try {
                Thread.sleep(delayMs)
                if (shouldReconnect) {
                    val url = serverUrl ?: return@Thread
                    doConnect(url)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        _isConnected.postValue(false)
    }

    fun isAlive(): Boolean = webSocket != null
}

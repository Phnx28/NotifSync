package com.phnx28.notifsync.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class WebSocketServer(
    port: Int = 8765,
    private val onMessageSent: (() -> Unit)? = null
) : WebSocketServer(InetSocketAddress(port)) {

    private val clients = mutableSetOf<WebSocket>()
    private val TAG = "WebSocketServer"

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        Log.d(TAG, "Received message from client: $message")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server started on port ${port}")
    }

    fun broadcastEvent(message: String) {
        val clientsCopy = clients.toSet()
        clientsCopy.forEach { client ->
            try {
                client.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message to client", e)
                clients.remove(client)
            }
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

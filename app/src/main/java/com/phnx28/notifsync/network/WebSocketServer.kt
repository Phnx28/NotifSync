package com.phnx28.notifsync.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import com.phnx28.notifsync.Constants

class WebSocketServer(
    port: Int = Constants.DEFAULT_PORT,
    private val pin: String?,
    private val onConnectionChanged: ((Int) -> Unit)? = null,
    private val onMessageSent: (() -> Unit)? = null
) : WebSocketServer(InetSocketAddress(port)) {

    private val clients: MutableSet<WebSocket> = ConcurrentHashMap.newKeySet()
    private val TAG = "WebSocketServer"

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: Draft,
        request: ClientHandshake
    ): ServerHandshakeBuilder {
        val clientPin = request.getFieldValue("X-Pairing-PIN")
        if (pin != null && clientPin != pin) {
            Log.w(TAG, "Rejecting connection from ${conn.remoteSocketAddress} due to invalid PIN: $clientPin")
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized: Invalid Pairing PIN")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        onConnectionChanged?.invoke(clients.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}")
        onConnectionChanged?.invoke(clients.size)
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
        var changed = false
        clientsCopy.forEach { client ->
            try {
                client.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message to client", e)
                if (clients.remove(client)) {
                    changed = true
                }
            }
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

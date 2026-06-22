package com.phnx28.notifsync.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.NotifSyncApp
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.network.Crypto
import com.phnx28.notifsync.network.EventBus
import com.phnx28.notifsync.network.NsdHelper
import com.phnx28.notifsync.network.WebSocketServer
import com.phnx28.notifsync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class SenderForegroundService : Service() {

    private val TAG = "SenderFGService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocketServer: WebSocketServer? = null
    private var nsdHelper: NsdHelper? = null
    private var eventCollectorJob: Job? = null

    private val gson = Gson()
    private val NOTIFICATION_ID = 1001

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createSenderChannel(this)
        startForeground(NOTIFICATION_ID, NotificationHelper.buildSenderNotification(this).build())
        startWebSocketServer()
        startEventCollector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        eventCollectorJob?.cancel()
        releaseWakeLocks()
        webSocketServer?.stopServer()
        nsdHelper?.tearDown()
        _serverInfoFlow.value = null
        _clientEventFlow.tryEmit(ClientEvent.ServiceStopped)
        serviceScope.cancel()
    }

    private fun startWebSocketServer() {
        val pin = (100_000..999_999).random().toString()
        val salt = Crypto.newSessionSalt()
        val key = Crypto.deriveKey(pin, salt)

        _serverInfoFlow.value = ServerInfo(
            pin = pin,
            saltHex = Crypto.toHex(salt),
            ipAddress = getLocalIpAddress(),
            port = Constants.DEFAULT_PORT,
            connectedClients = 0,
            isRunning = true
        )

        webSocketServer = WebSocketServer(
            port = Constants.DEFAULT_PORT,
            pin = pin,
            sessionSalt = salt,
            onConnectionChanged = { count ->
                // Update the server info flow with the new client count.
                _serverInfoFlow.value?.let { current ->
                    _serverInfoFlow.value = current.copy(connectedClients = count)
                }
                updateForegroundNotification(count)
                if (count > 0) acquireWakeLocks() else releaseWakeLocks()
            },
            onClientConnected = { address ->
                _clientEventFlow.tryEmit(ClientEvent.ClientConnected(address))
            },
            onClientDisconnected = { address ->
                _clientEventFlow.tryEmit(ClientEvent.ClientDisconnected(address))
            }
        ).apply { startServer() }

        nsdHelper = NsdHelper(this).apply {
            registerService(
                Constants.DEFAULT_PORT,
                mapOf(
                    "salt" to Crypto.toHex(salt),
                    "ver" to "1"
                )
            )
        }

        Log.d(TAG, "WebSocket server started on port ${Constants.DEFAULT_PORT}")
        updateForegroundNotification(0)
    }

    private fun startEventCollector() {
        eventCollectorJob = serviceScope.launch {
            EventBus.events.collect { json ->
                try {
                    val key = _serverInfoFlow.value?.let { info ->
                        Crypto.deriveKey(info.pin, Crypto.fromHex(info.saltHex))
                    } ?: return@collect
                    val payload = Crypto.encryptToBase64(json, key)
                    webSocketServer?.broadcastEvent(payload)
                    saveEventToLocal(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast event", e)
                }
            }
        }
    }

    private fun updateForegroundNotification(count: Int) {
        val pin = _serverInfoFlow.value?.pin
        val pinText = pin?.let { " | PIN: $it" } ?: ""
        val notification = NotificationHelper.buildSenderNotification(this)
            .setContentText("Running on port ${Constants.DEFAULT_PORT} | Connected: $count$pinText")
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NotifSync:SenderWakeLock"
            ).apply { acquire(Constants.WAKELOCK_TIMEOUT_MS) }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "NotifSync:SenderWifiLock"
            ).apply { acquire() }
        }
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private suspend fun saveEventToLocal(json: String) {
        try {
            val event = gson.fromJson(json, NotificationEvent::class.java) ?: return
            val app = application as NotifSyncApp
            app.repository.insertEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving event locally", e)
        }
    }

    /** Events emitted to the UI for snackbar feedback. */
    sealed class ClientEvent {
        data class ClientConnected(val address: String) : ClientEvent()
        data class ClientDisconnected(val address: String) : ClientEvent()
        object ServiceStopped : ClientEvent()
    }

    companion object {
        private val _serverInfoFlow = MutableStateFlow<ServerInfo?>(null)
        val serverInfoFlow: StateFlow<ServerInfo?> = _serverInfoFlow.asStateFlow()

        private val _clientEventFlow = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 16)
        val clientEventFlow: SharedFlow<ClientEvent> = _clientEventFlow.asSharedFlow()

        fun isRunning(): Boolean = _serverInfoFlow.value?.isRunning == true

        fun getLocalIpAddress(): String? {
            return try {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
                    .flatMap { Collections.list(it.inetAddresses) }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            } catch (ex: Exception) {
                Log.e("SenderFGService", "Error getting IP", ex)
                null
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, SenderForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SenderForegroundService::class.java))
        }
    }
}

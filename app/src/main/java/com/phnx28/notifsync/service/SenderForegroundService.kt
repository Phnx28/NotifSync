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
import com.phnx28.notifsync.ServiceLocator
import com.phnx28.notifsync.data.ConnectionRepository
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.network.Crypto
import com.phnx28.notifsync.network.NsdHelper
import com.phnx28.notifsync.network.WebSocketServer
import com.phnx28.notifsync.util.AppLog
import com.phnx28.notifsync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private val connRepo get() = ServiceLocator.connectionRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "Sender service creating")
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
        AppLog.i(TAG, "Sender service destroying")
        eventCollectorJob?.cancel()
        releaseWakeLocks()
        webSocketServer?.stopServer()
        nsdHelper?.tearDown()
        connRepo.setSenderInfo(null)
        connRepo.emitSenderEvent(ConnectionRepository.SenderEvent.ServiceStopped)
        serviceScope.cancel()
    }

    private fun startWebSocketServer() {
        val pin = (100_000..999_999).random().toString()
        val salt = Crypto.newSessionSalt()
        val key = Crypto.deriveKey(pin, salt)
        val ip = getLocalIpAddress()

        AppLog.i(TAG, "Starting WebSocket server: port=${Constants.DEFAULT_PORT} ip=$ip pin=$pin salt=${Crypto.toHex(salt).take(8)}...")

        connRepo.setSenderInfo(
            ServerInfo(
                pin = pin,
                saltHex = Crypto.toHex(salt),
                ipAddress = ip,
                port = Constants.DEFAULT_PORT,
                connectedClients = 0,
                isRunning = true
            )
        )

        webSocketServer = WebSocketServer(
            port = Constants.DEFAULT_PORT,
            pin = pin,
            sessionSalt = salt,
            onConnectionChanged = { count ->
                connRepo.updateSenderClientCount(count)
                updateForegroundNotification(count)
                if (count > 0) acquireWakeLocks() else releaseWakeLocks()
            },
            onClientConnected = { address ->
                connRepo.emitSenderEvent(ConnectionRepository.SenderEvent.ClientConnected(address))
            },
            onClientDisconnected = { address ->
                connRepo.emitSenderEvent(ConnectionRepository.SenderEvent.ClientDisconnected(address))
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
        AppLog.i(TAG, "mDNS service registered: _notifsync._tcp port=${Constants.DEFAULT_PORT}")

        updateForegroundNotification(0)
    }

    private fun startEventCollector() {
        eventCollectorJob = serviceScope.launch {
            ServiceLocator.connectionRepository.events.collect { json ->
                try {
                    val info = ServiceLocator.connectionRepository.senderInfo.value ?: return@collect
                    val key = Crypto.deriveKey(info.pin, Crypto.fromHex(info.saltHex))
                    val payload = Crypto.encryptToBase64(json, key)
                    val clientCount = webSocketServer?.getClientCount() ?: 0
                    if (clientCount > 0) {
                        webSocketServer?.broadcastEvent(payload)
                        AppLog.d(TAG, "Broadcast event to $clientCount client(s)")
                    }
                    saveEventToLocal(json)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to broadcast event", e)
                }
            }
        }
    }

    private fun updateForegroundNotification(count: Int) {
        val pin = ServiceLocator.connectionRepository.senderInfo.value?.pin
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
            ServiceLocator.notificationRepository.insertEvent(event)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error saving event locally", e)
        }
    }

    companion object {
        fun isRunning(): Boolean = ServiceLocator.connectionRepository.senderInfo.value?.isRunning == true

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
                AppLog.e("SenderFGService", "Error getting IP", ex)
                null
            }
        }

        fun start(context: Context) {
            AppLog.i("SenderFGService", "Starting sender service")
            val intent = Intent(context, SenderForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            AppLog.i("SenderFGService", "Stopping sender service")
            context.stopService(Intent(context, SenderForegroundService::class.java))
        }
    }
}

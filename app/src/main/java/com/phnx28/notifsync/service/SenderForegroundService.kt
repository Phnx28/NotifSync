package com.phnx28.notifsync.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    // v0.2.1 — `onTaskRemoved` + AlarmManager self-restart removed.
    // It defeats the user's swipe-away gesture and is a Play-policy violation
    // (AUDIT.md H-04). START_STICKY is enough for system-initiated restarts.

    override fun onDestroy() {
        super.onDestroy()
        eventCollectorJob?.cancel()
        releaseWakeLocks()
        webSocketServer?.stopServer()
        nsdHelper?.tearDown()
        activePin = null
        activeSessionSalt = null
        sessionKey = null
        connectionCountFlow.value = 0
        serviceScope.cancel()
    }

    private fun startWebSocketServer() {
        // 6-digit PIN + per-session salt for crypto (AUDIT.md C-01 / C-02).
        val pin = (100_000..999_999).random().toString()
        val salt = Crypto.newSessionSalt()
        val key = Crypto.deriveKey(pin, salt)

        activePin = pin
        activeSessionSalt = salt
        sessionKey = key
        connectionCountFlow.value = 0

        webSocketServer = WebSocketServer(
            port = Constants.DEFAULT_PORT,
            pin = pin,
            sessionSalt = salt,
            onConnectionChanged = { count ->
                connectionCountFlow.value = count
                updateForegroundNotification(count)
                // Hold wakelocks only when there's at least one client
                // (AUDIT.md H-06).
                if (count > 0) acquireWakeLocks() else releaseWakeLocks()
            }
        ).apply { startServer() }

        // Publish the salt in the mDNS TXT record so receivers can derive
        // the same AES key (AUDIT.md C-04).
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

    /**
     * Collect events from the in-process EventBus and ship them to all
     * connected WebSocket clients as encrypted Base64 frames.
     */
    private fun startEventCollector() {
        eventCollectorJob = serviceScope.launch {
            EventBus.events.collect { json ->
                try {
                    val key = sessionKey ?: return@collect
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
        val pinText = activePin?.let { " | PIN: $it" } ?: ""
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
            ).apply { acquire(Constants.WAKELOCK_TIMEOUT_MS) } // AUDIT.md H-05
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

    companion object {
        @Volatile
        var activePin: String? = null
            private set

        @Volatile
        var activeSessionSalt: ByteArray? = null
            private set

        @Volatile
        private var sessionKey: javax.crypto.spec.SecretKeySpec? = null

        val connectionCountFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

        /**
         * Return the device's primary LAN IPv4 address, preferring `wlan0` /
         * `eth0` and filtering out VPN / cellular interfaces (AUDIT.md M-12).
         */
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

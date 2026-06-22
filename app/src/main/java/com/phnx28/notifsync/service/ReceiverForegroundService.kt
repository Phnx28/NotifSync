package com.phnx28.notifsync.service

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
import com.phnx28.notifsync.MainActivity
import com.phnx28.notifsync.NotifSyncApp
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.network.WebSocketClient
import com.phnx28.notifsync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReceiverForegroundService : Service() {

    private val TAG = "ReceiverFGService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocketClient: WebSocketClient? = null
    private val gson = Gson()
    private val NOTIFICATION_ID = 1002

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    private var connectionObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createReceiverChannel(this)
        startForeground(NOTIFICATION_ID, NotificationHelper.buildReceiverNotification(this).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_IP_ADDRESS) ?: getPersistedIp()
                val port = intent.getIntExtra(EXTRA_PORT, Constants.DEFAULT_PORT)
                val pin = intent.getStringExtra(EXTRA_PIN) ?: getPersistedPin()
                val saltHex = intent.getStringExtra(EXTRA_SALT) ?: getPersistedSalt()
                if (ip != null && pin != null && saltHex != null) {
                    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    persistConnection(ip, port, pin, saltHex)
                    connectToServer(ip, port, pin, salt)
                } else {
                    Log.w(TAG, "Insufficient connection info — stopping")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                clearPersistedConnection()
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // v0.2.1 — `onTaskRemoved` + AlarmManager self-restart removed (AUDIT.md H-04).

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLocks()
        connectionObserverJob?.cancel()
        webSocketClient?.shutdown()
        webSocketClient = null
        serviceScope.cancel()
    }

    private fun connectToServer(ip: String, port: Int, pin: String, salt: ByteArray) {
        connectionObserverJob?.cancel()
        webSocketClient?.shutdown()
        connectedSenderIp = ip

        webSocketClient = WebSocketClient(
            onEventReceived = { event ->
                serviceScope.launch { handleReceivedEvent(event) }
            }
        )

        connectionObserverJob = serviceScope.launch(Dispatchers.Main) {
            webSocketClient?.connectionState?.collectLatest { state ->
                _connectionStateFlow.value = state
                updateNotification(state == ConnectionState.CONNECTED)
                // Hold wakelocks only when actively connected (AUDIT.md H-06).
                if (state == ConnectionState.CONNECTED) acquireWakeLocks()
                else releaseWakeLocks()
            }
        }

        val url = "ws://$ip:$port"
        webSocketClient?.connect(url, pin, salt)
        Log.d(TAG, "Connecting to $url")
    }

    private fun disconnect() {
        releaseWakeLocks()
        connectionObserverJob?.cancel()
        connectionObserverJob = null
        webSocketClient?.shutdown()
        webSocketClient = null
        connectedSenderIp = null
        _connectionStateFlow.value = ConnectionState.DISCONNECTED
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NotifSync:ReceiverWakeLock"
            ).apply { acquire(Constants.WAKELOCK_TIMEOUT_MS) } // AUDIT.md H-05
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "NotifSync:ReceiverWifiLock"
            ).apply { acquire() }
        }
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // --- Encrypted SharedPreferences for persisted connection (AUDIT.md M-02) ---

    private fun encryptedPrefs() = EncryptedSharedPreferences.create(
        this,
        PREFS_NAME,
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun persistConnection(ip: String, port: Int, pin: String, saltHex: String) {
        encryptedPrefs().edit()
            .putString(KEY_LAST_IP, ip)
            .putInt(KEY_LAST_PORT, port)
            .putString(KEY_LAST_PIN, pin)
            .putString(KEY_LAST_SALT, saltHex)
            .apply()
    }

    private fun getPersistedIp(): String? =
        encryptedPrefs().getString(KEY_LAST_IP, null)

    private fun getPersistedPin(): String? =
        encryptedPrefs().getString(KEY_LAST_PIN, null)

    private fun getPersistedSalt(): String? =
        encryptedPrefs().getString(KEY_LAST_SALT, null)

    private fun clearPersistedConnection() {
        encryptedPrefs().edit()
            .remove(KEY_LAST_IP)
            .remove(KEY_LAST_PORT)
            .remove(KEY_LAST_PIN)
            .remove(KEY_LAST_SALT)
            .apply()
    }

    /** Expose the last-connected sender IP for the UI (AUDIT.md U-01). */
    fun getPersistedIpForUi(): String? = getPersistedIp()

    // -------------------------------------------------

    private suspend fun handleReceivedEvent(event: NotificationEvent) {
        try {
            val app = application as NotifSyncApp
            app.repository.insertEvent(event)

            // Stable notification ID derived from event content (AUDIT.md L-09).
            val notifId = (event.timestamp.toInt() xor event.appName.hashCode() xor event.title.hashCode())

            NotificationHelper.postMirroredNotification(
                context = this,
                appName = event.appName,
                sender = event.sender,
                title = event.title,
                body = event.body,
                notificationId = notifId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling received event", e)
        }
    }

    private fun updateNotification(connected: Boolean) {
        val notification = NotificationHelper.buildReceiverNotification(this)
            .setContentTitle(
                if (connected) getString(com.phnx28.notifsync.R.string.receiver_fg_notification_title)
                else "NotifSync Receiver — Disconnected"
            )
            .setContentText(
                if (connected) getString(com.phnx28.notifsync.R.string.receiver_fg_notification_text)
                else "Reconnecting…"
            )
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_CONNECT = "com.phnx28.notifsync.CONNECT"
        const val ACTION_DISCONNECT = "com.phnx28.notifsync.DISCONNECT"
        const val EXTRA_IP_ADDRESS = "ip_address"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
        const val EXTRA_SALT = "salt"

        private const val PREFS_NAME = "receiver_connection"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_PIN = "last_pin"
        private const val KEY_LAST_SALT = "last_salt"

        /** UI-observable connection state. */
        private val _connectionStateFlow = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.IDLE)
        val connectionStateFlow: kotlinx.coroutines.flow.StateFlow<ConnectionState> = _connectionStateFlow

        /** The IP of the sender we're connected (or trying to connect) to. */
        @Volatile
        var connectedSenderIp: String? = null
            private set

        fun connect(context: Context, ip: String, port: Int = Constants.DEFAULT_PORT, pin: String, saltHex: String) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_IP_ADDRESS, ip)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PIN, pin)
                putExtra(EXTRA_SALT, saltHex)
            }
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startForegroundService(intent)
        }
    }
}

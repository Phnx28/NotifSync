package com.phnx28.notifsync.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.ServiceLocator
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.network.WebSocketClient
import com.phnx28.notifsync.util.AppLog
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

    private val connRepo get() = ServiceLocator.connectionRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "Receiver service creating")
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
                AppLog.i(TAG, "onStartCommand CONNECT: ip=$ip port=$port pin=${pin?.take(2)}** salt=${saltHex?.take(8)}...")
                if (ip != null && pin != null && saltHex != null) {
                    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    persistConnection(ip, port, pin, saltHex)
                    connectToServer(ip, port, pin, salt)
                } else {
                    AppLog.w(TAG, "Insufficient connection info — stopping. ip=$ip pin=${pin != null} salt=${saltHex != null}")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                AppLog.i(TAG, "onStartCommand DISCONNECT")
                clearPersistedConnection()
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i(TAG, "Receiver service destroying")
        releaseWakeLocks()
        connectionObserverJob?.cancel()
        webSocketClient?.shutdown()
        webSocketClient = null
        serviceScope.cancel()
    }

    private fun connectToServer(ip: String, port: Int, pin: String, salt: ByteArray) {
        AppLog.i(TAG, "Connecting to $ip:$port")
        connectionObserverJob?.cancel()
        webSocketClient?.shutdown()
        connRepo.setConnectedSenderIp(ip)

        webSocketClient = WebSocketClient(
            onEventReceived = { event ->
                serviceScope.launch { handleReceivedEvent(event) }
            }
        )

        connectionObserverJob = serviceScope.launch(Dispatchers.Main) {
            webSocketClient?.connectionState?.collectLatest { state ->
                AppLog.d(TAG, "Connection state: $state")
                connRepo.setReceiverState(state)
                updateNotification(state == ConnectionState.CONNECTED)
                if (state == ConnectionState.CONNECTED) acquireWakeLocks()
                else releaseWakeLocks()
            }
        }

        val url = "ws://$ip:$port"
        webSocketClient?.connect(url, pin, salt)
    }

    private fun disconnect() {
        releaseWakeLocks()
        connectionObserverJob?.cancel()
        connectionObserverJob = null
        webSocketClient?.shutdown()
        webSocketClient = null
        connRepo.setConnectedSenderIp(null)
        connRepo.setReceiverState(ConnectionState.DISCONNECTED)
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NotifSync:ReceiverWakeLock"
            ).apply { acquire(Constants.WAKELOCK_TIMEOUT_MS) }
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

    private fun encryptedPrefs() = androidx.security.crypto.EncryptedSharedPreferences.create(
        this,
        PREFS_NAME,
        androidx.security.crypto.MasterKey.Builder(this)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build(),
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun persistConnection(ip: String, port: Int, pin: String, saltHex: String) {
        encryptedPrefs().edit()
            .putString(KEY_LAST_IP, ip)
            .putInt(KEY_LAST_PORT, port)
            .putString(KEY_LAST_PIN, pin)
            .putString(KEY_LAST_SALT, saltHex)
            .apply()
    }

    private fun getPersistedIp(): String? = encryptedPrefs().getString(KEY_LAST_IP, null)
    private fun getPersistedPin(): String? = encryptedPrefs().getString(KEY_LAST_PIN, null)
    private fun getPersistedSalt(): String? = encryptedPrefs().getString(KEY_LAST_SALT, null)

    private fun clearPersistedConnection() {
        encryptedPrefs().edit()
            .remove(KEY_LAST_IP).remove(KEY_LAST_PORT)
            .remove(KEY_LAST_PIN).remove(KEY_LAST_SALT)
            .apply()
    }

    private suspend fun handleReceivedEvent(event: NotificationEvent) {
        try {
            ServiceLocator.notificationRepository.insertEvent(event)
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
            AppLog.e(TAG, "Error handling received event", e)
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

        fun stop(context: Context) = disconnect(context)
    }
}

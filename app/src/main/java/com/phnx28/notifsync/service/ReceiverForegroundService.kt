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
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.MainActivity
import com.phnx28.notifsync.NotifSyncApp
import com.phnx28.notifsync.R
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
    private var notificationCounter = 0
    private var wakeLock: PowerManager.WakeLock? = null
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
                // Prefer explicit IP from intent, fall back to persisted IP for restarts
                val ip = intent.getStringExtra(EXTRA_IP_ADDRESS) ?: getPersistedIp()
                val port = intent.getIntExtra(EXTRA_PORT, Constants.DEFAULT_PORT)
                val pin = intent.getStringExtra(EXTRA_PIN) ?: getPersistedPin()
                if (ip != null) {
                    persistConnection(ip, port, pin)
                    connectToServer(ip, port, pin)
                } else {
                    Log.w(TAG, "No IP available for connection, stopping")
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart with ACTION_CONNECT — the IP will be read from SharedPreferences
        val restartIntent = Intent(this, ReceiverForegroundService::class.java).apply {
            action = ACTION_CONNECT
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 2000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLocks()
        connectionObserverJob?.cancel()
        webSocketClient?.shutdown()
        webSocketClient = null
        serviceScope.cancel()
    }

    private fun connectToServer(ip: String, port: Int, pin: String?) {
        // Clean up previous connection if any
        connectionObserverJob?.cancel()
        webSocketClient?.shutdown()

        acquireWakeLocks()

        webSocketClient = WebSocketClient(
            onEventReceived = { event ->
                serviceScope.launch {
                    handleReceivedEvent(event)
                }
            }
        )

        // Collect StateFlow in a cancellable coroutine — no observer leak
        connectionObserverJob = serviceScope.launch(Dispatchers.Main) {
            webSocketClient?.isConnected?.collectLatest { connected ->
                updateNotification(connected)
            }
        }

        val url = "ws://$ip:$port"
        webSocketClient?.connect(url, pin)
        Log.d(TAG, "Connecting to $url")
    }

    private fun disconnect() {
        releaseWakeLocks()
        connectionObserverJob?.cancel()
        connectionObserverJob = null
        webSocketClient?.shutdown()
        webSocketClient = null
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NotifSync:ReceiverWakeLock"
            ).apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
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

    // --- Connection persistence for service restarts ---

    private fun persistConnection(ip: String, port: Int, pin: String?) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_LAST_IP, ip)
            .putInt(KEY_LAST_PORT, port)
            .putString(KEY_LAST_PIN, pin)
            .apply()
    }

    private fun getPersistedIp(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_IP, null)
    }

    private fun getPersistedPort(): Int {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_LAST_PORT, Constants.DEFAULT_PORT)
    }

    private fun getPersistedPin(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_PIN, null)
    }

    private fun clearPersistedConnection() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(KEY_LAST_IP)
            .remove(KEY_LAST_PORT)
            .remove(KEY_LAST_PIN)
            .apply()
    }

    // -------------------------------------------------

    private suspend fun handleReceivedEvent(event: NotificationEvent) {
        try {
            val app = application as NotifSyncApp
            app.repository.insertEvent(event)

            NotificationHelper.postMirroredNotification(
                context = this,
                appName = event.appName,
                sender = event.sender,
                title = event.title,
                body = event.body,
                notificationId = ++notificationCounter
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling received event", e)
        }
    }

    private fun updateNotification(connected: Boolean) {
        val notification = NotificationHelper.buildReceiverNotification(this)
            .setContentTitle(
                if (connected) getString(R.string.receiver_fg_notification_title)
                else "NotifSync Receiver — Disconnected"
            )
            .setContentText(
                if (connected) getString(R.string.receiver_fg_notification_text)
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

        private const val PREFS_NAME = "receiver_connection"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_PIN = "last_pin"

        fun connect(context: Context, ip: String, port: Int = Constants.DEFAULT_PORT, pin: String) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_IP_ADDRESS, ip)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PIN, pin)
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

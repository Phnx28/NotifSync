package com.phnx28.notifsync.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.NotifSyncApp
import com.phnx28.notifsync.R
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.network.NsdHelper
import com.phnx28.notifsync.network.WebSocketServer
import com.phnx28.notifsync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SenderForegroundService : Service() {

    private val TAG = "SenderFGService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocketServer: WebSocketServer? = null
    private var nsdHelper: NsdHelper? = null
    private val gson = Gson()
    private val NOTIFICATION_ID = 1001
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(NotificationCaptureService.EXTRA_EVENT_JSON) ?: return
            serviceScope.launch {
                webSocketServer?.broadcastEvent(json)
                saveEventToLocal(json)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createSenderChannel(this)

        val filter = IntentFilter(NotificationCaptureService.ACTION_BROADCAST_EVENT)
        registerReceiver(eventReceiver, filter, RECEIVER_NOT_EXPORTED)

        startForeground(NOTIFICATION_ID, NotificationHelper.buildSenderNotification(this).build())
        startWebSocketServer()
        acquireWakeLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(this, SenderForegroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 2000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLocks()
        unregisterReceiver(eventReceiver)
        webSocketServer?.stopServer()
        nsdHelper?.tearDown()
        activePin = null
        connectionCountFlow.value = 0
        serviceScope.cancel()
    }

    private fun startWebSocketServer() {
        val pin = (1000..9999).random().toString()
        activePin = pin
        connectionCountFlow.value = 0

        webSocketServer = WebSocketServer(
            port = Constants.DEFAULT_PORT,
            pin = pin,
            onConnectionChanged = { count ->
                connectionCountFlow.value = count
                updateForegroundNotification(count)
            }
        ).apply {
            startServer()
        }

        nsdHelper = NsdHelper(this).apply {
            registerService(Constants.DEFAULT_PORT)
        }

        Log.d(TAG, "WebSocket server started on port ${Constants.DEFAULT_PORT} with PIN $pin")
        updateForegroundNotification(0)
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
            ).apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
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
            val event = gson.fromJson(json, NotificationEvent::class.java)
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

        val connectionCountFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

        fun getLocalIpAddress(): String? {
            try {
                val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    if (intf.isUp && !intf.isLoopback) {
                        val addrs = java.util.Collections.list(intf.inetAddresses)
                        for (addr in addrs) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                return addr.hostAddress
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("SenderFGService", "Error getting IP", ex)
            }
            return null
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

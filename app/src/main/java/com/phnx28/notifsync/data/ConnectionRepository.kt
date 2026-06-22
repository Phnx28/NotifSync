package com.phnx28.notifsync.data

import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.service.ConnectionState
import com.phnx28.notifsync.service.ServerInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for connection state and event delivery.
 *
 * Replaces:
 *  - SenderForegroundService.serverInfoFlow / clientEventFlow (companion object statics)
 *  - ReceiverForegroundService.connectionStateFlow (companion object static)
 *  - EventBus (in-process SharedFlow for notification events)
 *
 * Services write to this repository; UI reads from it. This makes the
 * services testable (inject a fake repository) and eliminates the "stale
 * StateFlow value after service destruction" bug.
 */
class ConnectionRepository {

    // ─── Sender state ────────────────────────────────────────────────────

    private val _senderInfo = MutableStateFlow<ServerInfo?>(null)
    val senderInfo: StateFlow<ServerInfo?> = _senderInfo.asStateFlow()

    fun setSenderInfo(info: ServerInfo?) {
        _senderInfo.value = info
    }

    fun updateSenderClientCount(count: Int) {
        _senderInfo.value?.let { current ->
            _senderInfo.value = current.copy(connectedClients = count)
        }
    }

    // ─── Sender client events (for snackbars) ────────────────────────────

    sealed class SenderEvent {
        data class ClientConnected(val address: String) : SenderEvent()
        data class ClientDisconnected(val address: String) : SenderEvent()
        object ServiceStopped : SenderEvent()
    }

    private val _senderEvents = MutableSharedFlow<SenderEvent>(extraBufferCapacity = 16)
    val senderEvents: SharedFlow<SenderEvent> = _senderEvents.asSharedFlow()

    fun emitSenderEvent(event: SenderEvent) {
        _senderEvents.tryEmit(event)
    }

    // ─── Receiver state ──────────────────────────────────────────────────

    private val _receiverState = MutableStateFlow(ConnectionState.IDLE)
    val receiverState: StateFlow<ConnectionState> = _receiverState.asStateFlow()

    fun setReceiverState(state: ConnectionState) {
        _receiverState.value = state
    }

    private val _connectedSenderIp = MutableStateFlow<String?>(null)
    val connectedSenderIp: StateFlow<String?> = _connectedSenderIp.asStateFlow()

    fun setConnectedSenderIp(ip: String?) {
        _connectedSenderIp.value = ip
    }

    // ─── Event bus (notification/SMS events, in-process) ─────────────────

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emitEvent(json: String): Boolean = _events.tryEmit(json)
}

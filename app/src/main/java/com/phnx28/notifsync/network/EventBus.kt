package com.phnx28.notifsync.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local event bus for in-process notification/SMS → sender service delivery.
 *
 * Replaces the previous `sendBroadcast(Intent)` path (see AUDIT.md H-09):
 *  - No IPC round-trip through ActivityManager.
 *  - No `RECEIVER_NOT_EXPORTED` manifest flag to get wrong.
 *  - Backpressure-friendly via SharedFlow's buffering.
 *  - Trivially testable.
 *
 * The bus carries the JSON-serialized [com.phnx28.notifsync.data.model.NotificationEvent]
 * string — same format that goes on the wire — so the sender service can apply
 * identical validation regardless of source.
 */
object EventBus {

    private const val BUFFER_CAPACITY = 256

    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY
    )

    /** Read-only view for consumers (the sender foreground service). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Non-suspending emit for use from BroadcastReceiver / NotificationListenerService. */
    fun tryEmit(json: String): Boolean = _events.tryEmit(json)
}

package com.phnx28.notifsync.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Process-local ring buffer log. Captures all NotifSync log output so the
 * user can see what's happening without connecting adb.
 *
 * The buffer holds the last [CAPACITY] entries. The UI observes
 * [entriesFlow] and renders them in a bottom sheet.
 */
object AppLog {

    private const val CAPACITY = 500
    private const val TAG_PREFIX = "NotifSync"

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        enum class Level { V, D, I, W, E }
    }

    private val buffer = ConcurrentLinkedDeque<Entry>()
    private val _entriesFlow = MutableStateFlow<List<Entry>>(emptyList())
    val entriesFlow: StateFlow<List<Entry>> = _entriesFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) = log(Entry.Level.D, tag, message)
    fun i(tag: String, message: String) = log(Entry.Level.I, tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Entry.Level.W, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Entry.Level.E, tag, message, t)

    fun log(level: Entry.Level, tag: String, message: String, t: Throwable? = null) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message, t)
        buffer.addLast(entry)
        while (buffer.size > CAPACITY) buffer.pollFirst()
        _entriesFlow.value = buffer.toList()

        // Also emit to logcat so adb still works
        val logcatTag = if (tag.startsWith(TAG_PREFIX)) tag else "$TAG_PREFIX/$tag"
        when (level) {
            Entry.Level.V -> Log.v(logcatTag, message, t)
            Entry.Level.D -> Log.d(logcatTag, message, t)
            Entry.Level.I -> Log.i(logcatTag, message, t)
            Entry.Level.W -> Log.w(logcatTag, message, t)
            Entry.Level.E -> Log.e(logcatTag, message, t)
        }
    }

    fun clear() {
        buffer.clear()
        _entriesFlow.value = emptyList()
    }

    /** Format an entry for display in the log window. */
    fun format(entry: Entry): String {
        val time = timeFormat.format(Date(entry.timestamp))
        val levelStr = entry.level.name
        val throwableStr = entry.throwable?.let { " ${Log.getStackTraceString(it)}" } ?: ""
        return "$time $levelStr/${entry.tag}: ${entry.message}$throwableStr"
    }
}

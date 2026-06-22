package com.phnx28.notifsync

/**
 * App-wide constants. Single source of truth for magic numbers
 * (see AUDIT.md M-04 — previously scattered across the codebase).
 */
object Constants {

    /** Default WebSocket port for sender ↔ receiver communication. */
    const val DEFAULT_PORT = 8765

    /** Max length of a notification/SMS body, in characters. */
    const val MAX_BODY_LENGTH = 5_000

    /** Max length of a notification title, in characters. */
    const val MAX_TITLE_LENGTH = 500

    /** Max length of a sender/app-name field, in characters. */
    const val MAX_SENDER_LENGTH = 100

    /** Hard cap on a single WebSocket text frame, in chars. Guards against OOM DoS. */
    const val MAX_MESSAGE_SIZE = 64 * 1024

    /** Dedup window for back-to-back identical notifications, in ms. */
    const val DEDUP_WINDOW_MS = 2_000L

    /** Hard cap on the dedup map size. */
    const val DEDUP_MAP_MAX_SIZE = 200

    /** Archive retention period, in days. */
    const val ARCHIVE_RETENTION_DAYS = 30

    /** Max reconnect attempts before the receiver gives up. */
    const val RECONNECT_MAX_ATTEMPTS = 20

    /** WakeLock / WifiLock ceiling. Service re-acquires if still running. */
    const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000 // 6 hours

    /** Failed-handshake rate limit. */
    const val AUTH_MAX_FAILURES = 5

    /** Lockout duration after [AUTH_MAX_FAILURES] failed handshakes, in ms. */
    const val AUTH_LOCKOUT_MS = 5L * 60 * 1000 // 5 minutes
}

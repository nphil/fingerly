package com.fingerly.app.log

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Opt-in remote debug log via ntfy.sh, for reading app behavior during testing
 * without a USB debugger. Rate-limit friendly by construction:
 *
 *  - lines are buffered in memory and flushed as ONE batched POST every
 *    [FLUSH_INTERVAL_MS] (or earlier if [MAX_LINES_PER_FLUSH] lines pile up) —
 *    worst case ~2 requests/min, far under ntfy.sh visitor limits;
 *  - flushes are skipped entirely when the buffer is empty;
 *  - the body is capped at [MAX_BODY_BYTES]; overflow lines wait for the next
 *    flush, and the in-memory buffer drops oldest lines past [MAX_BUFFERED].
 *
 * Default OFF; toggled from the Settings screen. Never call from the render loop
 * or MIDI parse path (SPEC §1) — log from lifecycle/connection/summary points only.
 * The topic is public (repo is public): no personal data in log lines, ever.
 */
object RemoteLog {

    const val TOPIC = "fingerly-x7q4wj9k"

    private const val URL_STRING = "https://ntfy.sh/$TOPIC"
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val MAX_LINES_PER_FLUSH = 60
    private const val MAX_BODY_BYTES = 3800
    private const val MAX_BUFFERED = 400
    private const val PREFS_KEY = "remote_logging"

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val lock = Object()
    private val buffer = ArrayDeque<String>()
    private var droppedLines = 0

    @Volatile
    private var enabled = false

    @Volatile
    private var running = false

    fun init(context: Context) {
        // ON by default while the fundamentals module is under test: the learner
        // should not have to remember to switch on the only channel the build has
        // for seeing what happened. Switchable from the testing brief.
        enabled = context.getSharedPreferences("fingerly", 0).getBoolean(PREFS_KEY, true)
        if (enabled) ensureFlusher()
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("fingerly", 0).edit().putBoolean(PREFS_KEY, on).apply()
        enabled = on
        if (on) {
            ensureFlusher()
            log("log", "remote logging enabled")
        }
        synchronized(lock) { lock.notifyAll() } // flush leftovers promptly when disabling
    }

    /** Cheap no-op when disabled. Do not call from hot paths. */
    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${timeFormat.format(Instant.now())} [$tag] $message"
        synchronized(lock) {
            if (buffer.size >= MAX_BUFFERED) {
                buffer.removeFirst()
                droppedLines++
            }
            buffer.addLast(line)
            if (buffer.size >= MAX_LINES_PER_FLUSH) lock.notifyAll()
        }
    }

    @Synchronized
    private fun ensureFlusher() {
        if (running) return
        running = true
        Thread({
            while (true) {
                synchronized(lock) {
                    if (buffer.size < MAX_LINES_PER_FLUSH) {
                        try {
                            lock.wait(FLUSH_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                flush()
            }
        }, "fingerly-remote-log").apply { isDaemon = true }.start()
    }

    private fun flush() {
        val body = StringBuilder()
        synchronized(lock) {
            if (droppedLines > 0) {
                body.append("(… $droppedLines older lines dropped)\n")
                droppedLines = 0
            }
            while (buffer.isNotEmpty() && body.length + buffer.first().length + 1 <= MAX_BODY_BYTES) {
                body.append(buffer.removeFirst()).append('\n')
            }
        }
        if (body.isEmpty()) return
        runCatching {
            val conn = URL(URL_STRING).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Title", "Fingerly log")
                conn.setRequestProperty("X-Priority", "min") // no noisy notifications
                conn.setRequestProperty("X-Tags", "page_facing_up")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() } // drain; result intentionally ignored
            } finally {
                conn.disconnect()
            }
        } // network failures drop the batch silently — logging must never break the app
    }
}

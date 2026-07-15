package de.schliemannosaurusrex.mukkeklopper.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * Zentrales App-Logging, zuschaltbar über Settings > Debug log (PLAN „Debug-Log,
 * Sync-Fix, Queue, Cast" Punkt 1). [i]/[w]/[e] werden **immer** nach Logcat und in
 * einen In-Memory-Ringpuffer geschrieben — unabhängig vom Toggle. [d] (Debug/Verbose,
 * feature-übergreifend über Sync/Player/Equalizer/... verteilt) wird nur geloggt und
 * gepuffert, wenn der User "Enable debug logging" aktiviert hat, damit der Normalbetrieb
 * leise bleibt.
 */
object AppLog {
    private const val MAX_ENTRIES = 1000

    private val debugEnabled = AtomicBoolean(false)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    val timeFormat: SimpleDateFormat get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled.set(enabled)
    }

    fun isDebugEnabled(): Boolean = debugEnabled.get()

    /** Nur sichtbar/gepuffert, solange "Enable debug logging" aktiv ist. */
    fun d(tag: String, message: String) {
        if (!debugEnabled.get()) return
        Log.d(tag, message)
        append(LogLevel.DEBUG, tag, message)
    }

    /** Immer geloggt und gepuffert — normaler Betriebszustand. */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append(LogLevel.INFO, tag, message)
    }

    /** Immer geloggt und gepuffert — erwartbare, aber bemerkenswerte Zustände. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append(LogLevel.WARN, tag, message + throwable.suffix())
    }

    /** Immer geloggt und gepuffert — Fehler, die den User betreffen. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append(LogLevel.ERROR, tag, message + throwable.suffix())
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Für "Share"/"Copy" in DebugLogScreen — komplette Historie als Text. */
    fun exportText(): String {
        val fmt = timeFormat
        return synchronized(lock) {
            buffer.joinToString("\n") { entry ->
                "${fmt.format(entry.timestamp)} ${entry.level.name.padEnd(5)} ${entry.tag}: ${entry.message}"
            }
        }
    }

    private fun Throwable?.suffix(): String =
        if (this == null) "" else " — ${javaClass.simpleName}: ${message ?: "no message"}"

    private fun append(level: LogLevel, tag: String, message: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(LogEntry(System.currentTimeMillis(), level, tag, message))
            _entries.value = buffer.toList()
        }
    }
}

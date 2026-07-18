package de.schliemannosaurusrex.mukkeklopper.debug

import android.content.Context
import android.util.Log
import java.io.File
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
 * Puffer/Datei geschrieben — unabhängig vom Toggle. [d] (Debug/Verbose,
 * feature-übergreifend über Sync/Player/Equalizer/... verteilt) wird nur geloggt und
 * gepuffert, wenn der User "Enable debug logging" aktiviert hat, damit der Normalbetrieb
 * leise bleibt.
 *
 * Zusätzlich zum In-Memory-Ringpuffer wird jede Zeile nach `filesDir/debug_log.txt`
 * geschrieben: Android kann [de.schliemannosaurusrex.mukkeklopper.player.MukkePlayerService]
 * im Hintergrund jederzeit killen und neu erzeugen (beobachtet: Audio-Test in Android
 * Auto), wodurch der reine In-Memory-Puffer beim nächsten Prozessstart leer beginnt und
 * die Historie des vorherigen Laufs verloren geht. [init] lädt das Ende der Datei beim
 * App-Start zurück in den Puffer, damit "Debug log" auch über einen Neustart hinweg
 * Kontext zeigt; zusätzlich per `adb shell run-as <pkg> cat files/debug_log.txt`
 * auslesbar, falls die App-UI selbst nicht mehr erreichbar ist.
 */
object AppLog {
    private const val MAX_ENTRIES = 3000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val LOG_FILE_NAME = "debug_log.txt"
    private const val LOG_FILE_OLD_NAME = "debug_log.old.txt"
    private const val SELF_TAG = "AppLog"

    private val debugEnabled = AtomicBoolean(false)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private var logFile: File? = null

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    val timeFormat: SimpleDateFormat get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Einmal beim App-Start aufrufen (Application.onCreate), vor allen anderen Log-Aufrufen. */
    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            val file = File(context.applicationContext.filesDir, LOG_FILE_NAME)
            logFile = file
            runCatching {
                if (file.exists()) {
                    file.readLines().takeLast(MAX_ENTRIES).forEach { line ->
                        parseLine(line)?.let { buffer.addLast(it) }
                    }
                    _entries.value = buffer.toList()
                }
            }.onFailure { Log.w(SELF_TAG, "failed to preload persisted log", it) }
        }
    }

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
            val file = logFile
            runCatching { file?.delete() }
            runCatching { file?.parentFile?.let { File(it, LOG_FILE_OLD_NAME).delete() } }
        }
    }

    /** Für "Share"/"Copy" in DebugLogScreen — komplette gepufferte Historie als Text. */
    fun exportText(): String {
        val fmt = timeFormat
        return synchronized(lock) {
            buffer.joinToString("\n") { entry -> displayLine(fmt, entry) }
        }
    }

    private fun displayLine(fmt: SimpleDateFormat, entry: LogEntry): String =
        "${fmt.format(entry.timestamp)} ${entry.level.name.padEnd(5)} ${entry.tag}: ${entry.message}"

    private fun Throwable?.suffix(): String =
        if (this == null) "" else " — ${javaClass.simpleName}: ${message ?: "no message"}"

    private fun append(level: LogLevel, tag: String, message: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
            buffer.addLast(entry)
            _entries.value = buffer.toList()
            persist(entry)
        }
    }

    /** Rohformat (Tab-getrennt) statt formatiertem Anzeigetext — [parseLine] macht das rückgängig. */
    private fun persist(entry: LogEntry) {
        val file = logFile ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) rotate(file)
            file.appendText(
                "${entry.timestamp}\t${entry.level.name}\t${entry.tag}\t${entry.message.replace('\n', ' ')}\n"
            )
        }.onFailure { Log.w(SELF_TAG, "failed to persist log entry", it) }
    }

    private fun rotate(file: File) {
        val old = File(file.parentFile, LOG_FILE_OLD_NAME)
        runCatching { old.delete() }
        runCatching { file.renameTo(old) }
    }

    private fun parseLine(line: String): LogEntry? {
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val level = runCatching { LogLevel.valueOf(parts[1]) }.getOrNull() ?: return null
        return LogEntry(timestamp, level, parts[2], parts[3])
    }
}

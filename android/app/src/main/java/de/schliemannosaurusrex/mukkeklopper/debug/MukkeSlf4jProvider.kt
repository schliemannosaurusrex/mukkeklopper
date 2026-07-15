package de.schliemannosaurusrex.mukkeklopper.debug

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * Minimale SLF4J-Bindung (registriert über
 * `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`), die sshj's interne
 * Handshake-/Auth-Diagnose in [AppLog] umleitet. Ohne diese Bindung fällt SLF4J 2.x
 * auf einen stillen NOP-Fallback zurück — genau die Details (welche Auth-Methoden der
 * Server anbietet, warum ein Handshake scheitert), die für die Sync-Fehlerdiagnose
 * nötig sind, würden sonst verworfen (PLAN „Debug-Log, Sync-Fix, Queue, Cast" Punkt 1/2).
 */
class MukkeSlf4jServiceProvider : SLF4JServiceProvider {
    private val loggerFactory = MukkeLoggerFactory()
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory
    override fun getMarkerFactory(): IMarkerFactory = markerFactory
    override fun getMDCAdapter(): MDCAdapter = mdcAdapter
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}

private class MukkeLoggerFactory : ILoggerFactory {
    private val loggers = HashMap<String, Logger>()

    @Synchronized
    override fun getLogger(name: String): Logger =
        loggers.getOrPut(name) { MukkeSlf4jLogger(name) }
}

/** Route jeden sshj-Log-Aufruf durch [AppLog] — DEBUG/TRACE nur bei aktivem Toggle. */
private class MukkeSlf4jLogger(loggerName: String) : AbstractLogger() {
    init {
        name = loggerName
    }

    override fun isTraceEnabled(): Boolean = AppLog.isDebugEnabled()
    override fun isTraceEnabled(marker: Marker?): Boolean = isTraceEnabled()
    override fun isDebugEnabled(): Boolean = AppLog.isDebugEnabled()
    override fun isDebugEnabled(marker: Marker?): Boolean = isDebugEnabled()
    override fun isInfoEnabled(): Boolean = AppLog.isDebugEnabled()
    override fun isInfoEnabled(marker: Marker?): Boolean = isInfoEnabled()
    override fun isWarnEnabled(): Boolean = true
    override fun isWarnEnabled(marker: Marker?): Boolean = true
    override fun isErrorEnabled(): Boolean = true
    override fun isErrorEnabled(marker: Marker?): Boolean = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<Any?>?,
        throwable: Throwable?,
    ) {
        val formatted = runCatching {
            MessageFormatter.arrayFormat(messagePattern.orEmpty(), arguments).message
        }.getOrDefault(messagePattern.orEmpty())
        val tag = "sshj.$name"
        when (level) {
            Level.ERROR -> AppLog.e(tag, formatted, throwable)
            Level.WARN -> AppLog.w(tag, formatted, throwable)
            else -> AppLog.d(tag, formatted + (throwable?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        }
    }
}

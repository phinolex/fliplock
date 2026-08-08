package com.fliplock.cover.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogCategory {
    LIGHT,
    PROXIMITY,
    SCREEN,
    ACCESSIBILITY,
    ACTION,
    ENGINE,
    CALIBRATION,
    SYSTEM,
}

data class LogEntry(
    val wallClockMs: Long,
    val category: LogCategory,
    val message: String,
) {
    fun format(): String = "${time()} | ${category.name} | $message"

    fun time(): String = TIME_FORMAT.format(Instant.ofEpochMilli(wallClockMs))

    companion object {
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * Journal de debogage interne.
 *
 * - conserve en MEMOIRE uniquement (tampon circulaire), jamais envoye nulle part ;
 * - ne contient aucune donnee personnelle : uniquement des valeurs de capteurs,
 *   des etats booleens et des durees.
 */
class FlipLockLogger(private val capacity: Int = 600) {

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(category: LogCategory, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), category, message)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Rendu texte des [max] dernieres lignes, pour le rapport de diagnostic. */
    fun renderTail(max: Int = 80): String =
        _entries.value.takeLast(max).joinToString("\n") { it.format() }
}

package com.example.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel {
  INFO,
  SUCCESS,
  WARNING,
  ERROR,
}

data class LogEntry(
  val id: Long,
  val timestamp: String,
  val tag: String,
  val message: String,
  val level: LogLevel = LogLevel.INFO,
)

class AppLogStore {
  private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
  val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

  private var nextId = 1L

  fun append(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
    val timeText = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    val entry =
      LogEntry(
        id = nextId++,
        timestamp = timeText,
        tag = tag,
        message = message,
        level = level,
      )
    _logs.update { current -> (current + entry).takeLast(MAX_LOG_ENTRIES) }
  }

  fun clear() {
    _logs.value = emptyList()
  }

  fun exportPlainText(): String {
    return _logs.value.joinToString("\n") { entry ->
      "[${entry.timestamp}] [${entry.tag}] [${entry.level.name}] ${entry.message}"
    }
  }

  private companion object {
    const val MAX_LOG_ENTRIES = 400
  }
}

package com.rustedwax.app.detect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only event log — every detection and scrobble decision, in order.
 *
 * Everything the probe observes lands here so the run can be exported off the
 * device and diffed against what the extension's connectors produce for the
 * same content. In-memory ring buffer for the UI, plus a file for export.
 *
 * Deliberately unstructured — this is a measurement tool, not a product
 * surface. It gets deleted when Phase 3 lands.
 */
object EventLog {

	private const val TAG = "RustedWax"
	private const val MAX_LINES = 2000
	const val FILE_NAME = "rustedwax-log.txt"

	private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

	private val _lines = MutableStateFlow<List<String>>(emptyList())
	val lines: StateFlow<List<String>> = _lines.asStateFlow()

	private var file: File? = null

	fun init(context: Context) {
		file = File(context.filesDir, FILE_NAME)
		append("app", "--- session start ${Date()} ---")
	}

	@Synchronized
	fun append(tag: String, message: String) {
		val line = "${stamp.format(Date())}  [$tag] $message"
		Log.d(TAG, line)
		_lines.value = (_lines.value + line).takeLast(MAX_LINES)
		runCatching { file?.appendText(line + "\n") }
	}

	/** Multi-line block, indented so metadata dumps stay readable. */
	fun appendBlock(tag: String, title: String, body: List<String>) {
		append(tag, title)
		body.forEach { append(tag, "    $it") }
	}

	@Synchronized
	fun clear() {
		_lines.value = emptyList()
		runCatching { file?.writeText("") }
	}

	fun logFile(): File? = file
}

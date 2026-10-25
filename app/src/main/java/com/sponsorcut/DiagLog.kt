// app/src/main/java/com/sponsorcut/DiagLog.kt

package com.sponsorcut

import android.util.Log

/**
 * In-memory diagnostic log accumulator.
 * Collects entries from any thread during a processing run.
 * Cleared at the start of each new run.
 */
object DiagLog {

    @Volatile
    var suppressGlobalLogs: Boolean = false
    private const val TAG = "DiagLog"
    private val buf = StringBuilder()

    @Volatile
    private var runSummary: String = ""

    @Synchronized
    fun clear() {
        buf.clear()
        runSummary = ""
    }

    @Synchronized
    fun setRunSummary(summary: String) {
        runSummary = summary.trim()
    }

    @Synchronized
    fun append(tag: String, msg: String) {
        buf.append("[$tag] $msg\n")
        Log.d(TAG, "[$tag] $msg")
    }

    @Synchronized
    fun get(): String {
        val details = buf.toString()
        return if (runSummary.isBlank()) {
            details
        } else {
            buildString {
                appendLine("=== RUN SUMMARY ===")
                appendLine(runSummary)
                appendLine()
                appendLine("=== DIAGNOSTICS ===")
                append(details)
            }
        }
    }
}


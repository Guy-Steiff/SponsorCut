package com.sponsorcut

import android.util.Log

/**
 * In-memory diagnostic log accumulator.
 * Collects entries from any thread during a processing run.
 * Cleared at the start of each new run.
 */
object DiagLog {
    private const val TAG = "DiagLog"
    private val buf = StringBuilder()

    @Synchronized
    fun clear() { buf.clear() }

    @Synchronized
    fun append(tag: String, msg: String) {
        buf.append("[$tag] $msg\n")
        Log.d(TAG, "[$tag] $msg")
    }

    @Synchronized
    fun get(): String = buf.toString()
}


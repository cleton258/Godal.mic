package com.godalmic.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log em memória do app (aparece em "Diagnóstico e log" e pode ser enviado ao PC). */
object AppLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile var remote: ((String) -> Unit)? = null

    fun add(msg: String, toPc: Boolean = false) {
        synchronized(lines) {
            lines.add(fmt.format(Date()) + " " + msg)
            if (lines.size > 300) lines.removeAt(0)
        }
        if (toPc) try { remote?.invoke(msg) } catch (_: Exception) { }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
    fun clear() = synchronized(lines) { lines.clear() }
}

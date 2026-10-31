package com.openkeys.keyboard

import android.content.Context
import org.json.JSONArray

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density).toInt()

/** Show invisible characters in a readable way. */
fun displayText(s: String): String = when (s) {
    "\u200c" -> "ZWNJ"
    "\u200d" -> "ZWJ"
    " " -> "␣"
    else -> s
}

fun matchCase(orig: String, w: String): String = when {
    orig.length > 1 && orig.any { it.isUpperCase() } && orig.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
    orig.isNotEmpty() && orig[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
    else -> w
}

fun jsonStrings(a: JSONArray?): List<String> {
    if (a == null) return emptyList()
    return (0 until a.length()).map { a.getString(it) }
}

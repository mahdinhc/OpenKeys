package com.openkeys.keyboard

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer debug log (Developer -> Debug log). Plain text, so it can be read even when the keyboard itself is broken.
 *
 *  - Only records while Developer options AND "Debug log" are both switched on (config keys devMode + devDebugLog).
 *  - Records lifecycle breadcrumbs, swallowed errors and uncaught-exception stack traces (the crash handler writes
 *    synchronously before the process dies).
 *  - NEVER records what is typed - that is the separate, encrypted, password-protected key log (TypeLog).
 *
 * Files: files/devlog/debug.log (current) and debug.1.log (previous, rotated at ~200 KB).
 */
object DevLog {
    private const val MAX_BYTES = 200_000L
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var installed = false
    private var rawCache: String? = null
    private var onCache = false

    private fun dir(c: Context) = File(c.applicationContext.filesDir, "devlog").also { it.mkdirs() }

    /** Cheap enough to call on every event: re-parses the config only when the stored string changed. */
    fun enabled(c: Context): Boolean {
        return try {
            val raw = c.applicationContext.getSharedPreferences("openkeys", Context.MODE_PRIVATE).getString("config", null) ?: return false
            synchronized(lock) {
                if (raw != rawCache) {
                    rawCache = raw
                    onCache = try { JSONObject(raw).let { it.optBoolean("devMode", false) && it.optBoolean("devDebugLog", false) } } catch (e: Exception) { false }
                }
                onCache
            }
        } catch (e: Exception) { false }
    }

    fun d(c: Context, tag: String, msg: String) { if (enabled(c)) write(c, 'D', Log.DEBUG, tag, msg, null) }
    fun i(c: Context, tag: String, msg: String) { if (enabled(c)) write(c, 'I', Log.INFO, tag, msg, null) }
    fun e(c: Context, tag: String, msg: String, t: Throwable? = null) { if (enabled(c)) write(c, 'E', Log.ERROR, tag, msg, t) }

    /** Writes a "new session" header (app version, Android version, device) so a log can be read on its own. */
    fun session(c: Context, what: String) {
        if (!enabled(c)) return
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (e: Exception) { "?" }
        write(c, 'I', Log.INFO, "Session", "==== $what | app $ver | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL} ====", null)
    }

    /** Chains in front of the existing default handler so a crash stack trace is saved before the process dies. */
    fun installCrashHandler(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try { if (enabled(app)) write(app, 'F', Log.ERROR, "CRASH", "Uncaught exception on thread \"${th.name}\"", ex) } catch (e: Throwable) { }
            prev?.uncaughtException(th, ex)
        }
    }

    private fun write(c: Context, level: Char, prio: Int, tag: String, msg: String, t: Throwable?) {
        try { Log.println(prio, "OpenKeys/$tag", msg + (if (t != null) "\n" + Log.getStackTraceString(t) else "")) } catch (e: Throwable) { }
        synchronized(lock) {
            try {
                val d = dir(c)
                val f = File(d, "debug.log")
                if (f.length() > MAX_BYTES) { val old = File(d, "debug.1.log"); old.delete(); f.renameTo(old) }
                val sb = StringBuilder()
                sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
                if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
                f.appendText(sb.toString())
            } catch (e: Throwable) { }
        }
    }

    /** Newest [maxChars] characters of the log (previous file + current file). */
    fun read(c: Context, maxChars: Int): String = synchronized(lock) {
        val d = dir(c)
        val a = File(d, "debug.1.log"); val b = File(d, "debug.log")
        val s = (if (a.exists()) a.readText() else "") + (if (b.exists()) b.readText() else "")
        if (s.length > maxChars) "... (older entries trimmed)\n" + s.takeLast(maxChars) else s
    }

    fun clear(c: Context) { synchronized(lock) { dir(c).deleteRecursively() } }
}

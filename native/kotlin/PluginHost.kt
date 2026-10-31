package com.openkeys.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import app.cash.quickjs.QuickJs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** In-memory ring buffer shown in the app (Plugins -> Show log). Keyboard service and app share one process. */
object PluginLog {
    private val lines = ArrayList<String>()

    @Synchronized fun add(plugin: String, level: String, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val tag = if (level == "log") "" else level.uppercase() + ": "
        lines.add("$t [$plugin] $tag$msg")
        while (lines.size > 300) lines.removeAt(0)
        android.util.Log.d("OpenKeysPlugin", "[$plugin] $msg")
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")
    @Synchronized fun clear() { lines.clear() }
}

/** key ("pluginId.actionId") -> icon, used to label {js=pluginId.actionId} layout keys. */
object PluginIcons {
    @Volatile var map: Map<String, String> = emptyMap()
    fun label(key: String): String = map[key] ?: key
}

/** Plugin files live in filesDir/plugins/<id>.js ; flags/settings in prefs. */
class PluginStore(private val ctx: Context) {
    val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("openkeys_plugins", Context.MODE_PRIVATE)
    /** Plugin storage + load metadata. A separate file so writes here never trigger a plugin reload. */
    val data: SharedPreferences = ctx.applicationContext.getSharedPreferences("openkeys_plugin_data", Context.MODE_PRIVATE)
    private val dir: File get() = File(ctx.filesDir, "plugins").also { it.mkdirs() }

    fun ids(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".js") }?.map { it.name.removeSuffix(".js") }?.sorted() ?: emptyList()

    fun code(id: String): String? = try {
        val f = File(dir, "$id.js"); if (f.exists()) f.readText() else null
    } catch (e: Exception) { null }

    fun save(id: String, code: String) {
        require(Regex("[a-z0-9_-]{1,40}").matches(id)) { "id must be 1-40 chars of a-z, 0-9, _ or -" }
        File(dir, "$id.js").writeText(code)
        touch()
    }

    fun delete(id: String) {
        File(dir, "$id.js").delete()
        sp.edit().remove("enabled:$id").remove("settings:$id").apply()
        val ed = data.edit().remove("meta:$id")
        for (k in data.all.keys) if (k.startsWith("s:$id:")) ed.remove(k)
        ed.apply()
        touch()
    }

    fun enabled(id: String): Boolean = sp.getBoolean("enabled:$id", true)

    fun setEnabled(id: String, on: Boolean) {
        sp.edit().putBoolean("enabled:$id", on).putLong("rev", System.nanoTime()).apply()
    }

    fun settings(id: String): String = sp.getString("settings:$id", "{}") ?: "{}"

    fun setSettings(id: String, json: String) {
        JSONObject(json) // validate
        sp.edit().putString("settings:$id", json).putLong("rev", System.nanoTime()).apply()
    }

    fun saveMeta(id: String, json: String) { data.edit().putString("meta:$id", json).apply() }
    fun meta(id: String): String? = data.getString("meta:$id", null)

    /** Any change to "rev" makes the running keyboard reload its plugins. */
    fun touch() { sp.edit().putLong("rev", System.nanoTime()).apply() }

    /** Sample plugins shipped in assets/plugins (copied from the repo's plugins/ folder at build time). */
    fun bundled(): JSONArray {
        val a = JSONArray()
        val names = try { ctx.assets.list("plugins") } catch (e: Exception) { null }
        if (names == null) return a
        for (n in names.sorted()) {
            if (!n.endsWith(".js")) continue
            try {
                val code = ctx.assets.open("plugins/$n").bufferedReader().use { it.readText() }
                a.put(JSONObject().put("id", n.removeSuffix(".js")).put("code", code))
            } catch (e: Exception) { }
        }
        return a
    }

    fun exportAll(): JSONArray {
        val a = JSONArray()
        for (id in ids()) {
            a.put(JSONObject().put("id", id).put("code", code(id) ?: "").put("enabled", enabled(id))
                .put("settings", try { JSONObject(settings(id)) } catch (e: Exception) { JSONObject() }))
        }
        return a
    }
}

/** What the plugin runtime needs from the keyboard. Every method is called on the main thread. */
interface PluginEnv {
    fun commit(text: String, back: Int)
    fun deleteAround(before: Int, after: Int)
    fun sendKey(name: String)
    fun editAction(name: String)
    fun moveCursor(n: Int)
    fun toast(text: String)
    fun haptic()
    fun switchLayout(id: String)
    fun openPanel(plugin: String, spec: JSONObject)
    fun closePanel()
    fun pluginSuggestions(seq: Int, list: List<String>)
    /** [key, icon] pairs for the toolbar. */
    fun pluginsLoaded(tools: List<Pair<String, String>>)
}

/**
 * Runs all enabled plugins in ONE QuickJS context on ONE dedicated thread.
 *  - sync hooks (onCommit) are awaited from the UI thread with a short timeout, so a slow plugin can't freeze typing
 *  - everything else is fire-and-forget
 *  - reload() throws the whole engine away and builds a fresh one, which also recovers from an infinite loop
 * The JS <-> Kotlin boundary is two tiny interfaces that only pass Strings (JSON).
 */
class PluginHost(private val ctx: Context, private val env: PluginEnv) {
    interface Bridge { fun dispatch(hook: String, json: String): String }
    interface Host { fun call(fn: String, json: String): String }

    private class Rt(val ex: ScheduledThreadPoolExecutor) {
        @Volatile var engine: QuickJs? = null
        @Volatile var bridge: Bridge? = null
        val timers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    }

    private val main = Handler(Looper.getMainLooper())
    private val store = PluginStore(ctx)
    private val io = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "openkeys-net").also { it.isDaemon = true } })
    @Volatile private var rt: Rt? = null
    @Volatile private var hooks: Set<String> = emptySet()
    private var lastWarn = 0L

    fun hasHook(h: String): Boolean = hooks.contains(h)

    // ------------------------------------------------------------ lifecycle
    fun reload() {
        shutdown()
        val srcs = ArrayList<Triple<String, String, String>>()
        for (id in store.ids()) {
            if (!store.enabled(id)) continue
            val code = store.code(id) ?: continue
            srcs.add(Triple(id, code, store.settings(id)))
        }
        PluginIcons.map = emptyMap()
        main.post { env.pluginsLoaded(emptyList()) }
        if (srcs.isEmpty()) return
        val ex = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "openkeys-js").also { it.isDaemon = true } })
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        ex.setRemoveOnCancelPolicy(true)
        val r = Rt(ex)
        rt = r
        ex.execute { load(r, srcs) }
    }

    fun shutdown() {
        val r = rt ?: return
        rt = null
        hooks = emptySet()
        try {
            r.ex.execute { try { r.engine?.close() } catch (e: Exception) { } }
            r.ex.shutdown()
        } catch (e: Exception) { }
    }

    private fun load(r: Rt, srcs: List<Triple<String, String, String>>) {
        try {
            val e = QuickJs.create()
            r.engine = e
            e.set("__host", Host::class.java, HostImpl(r))
            val prelude = ctx.assets.open("plugin_prelude.js").bufferedReader().use { it.readText() }
            e.evaluate(prelude, "prelude.js")
            r.bridge = e.get("__bridge", Bridge::class.java)
            for (s in srcs) {
                val id = s.first
                try {
                    // Plugin source goes on the first line of the wrapper so error line numbers match the file.
                    e.evaluate("__defs[" + JSONObject.quote(id) + "]=function(plugin,keys,console,fetch,setTimeout,setInterval,clearTimeout,clearInterval){" +
                        s.second + "\n};", "$id.js")
                    e.evaluate("__run(" + JSONObject.quote(id) + "," + JSONObject.quote(s.third) + ")", "run.js")
                } catch (ex: Exception) {
                    val m = ex.message ?: ex.toString()
                    PluginLog.add(id, "error", m)
                    store.saveMeta(id, JSONObject().put("id", id).put("name", id).put("error", m).toString())
                }
            }
            publish(r)
        } catch (ex: Throwable) {
            PluginLog.add("host", "error", "plugin runtime failed to start: " + (ex.message ?: ex.toString()))
        }
    }

    private fun publish(r: Rt) {
        val json = r.bridge?.dispatch("__meta", "") ?: ""
        if (json.isEmpty()) return
        val arr = JSONObject(json).optJSONArray("plugins") ?: JSONArray()
        val tools = ArrayList<Pair<String, String>>()
        val icons = HashMap<String, String>()
        val hs = HashSet<String>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            store.saveMeta(p.getString("id"), p.toString())
            if (p.optString("error", "").isNotEmpty()) continue
            val acts = p.optJSONArray("actions") ?: JSONArray()
            for (j in 0 until acts.length()) {
                val a = acts.getJSONObject(j)
                val key = a.getString("key")
                val icon = a.optString("icon", "\u2022")
                icons[key] = icon
                if (a.optBoolean("toolbar", true)) tools.add(key to icon)
            }
            hs.addAll(jsonStrings(p.optJSONArray("hooks")))
        }
        PluginIcons.map = icons
        hooks = hs
        if (rt === r) main.post { env.pluginsLoaded(tools) }
    }

    // ------------------------------------------------------------ calls from the keyboard
    /** Blocks the caller (UI thread) for at most timeoutMs. Returns null on timeout/error/no result. */
    fun runSync(hook: String, args: JSONObject, timeoutMs: Long = 30): JSONObject? {
        val r = rt ?: return null
        val json = args.toString()
        return try {
            val f = r.ex.submit(Callable<String> { r.bridge?.dispatch(hook, json) ?: "" })
            val s = f.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (s.isNullOrEmpty()) null else JSONObject(s)
        } catch (e: TimeoutException) {
            val now = System.currentTimeMillis()
            if (now - lastWarn > 10000) { lastWarn = now; PluginLog.add("host", "warn", "$hook took longer than ${timeoutMs}ms and was skipped") }
            null
        } catch (e: Exception) { null }
    }

    fun runAsync(hook: String, args: JSONObject) {
        val r = rt ?: return
        post(r, hook, args.toString())
    }

    private fun post(r: Rt, hook: String, json: String) {
        try {
            r.ex.execute {
                try { r.bridge?.dispatch(hook, json) } catch (e: Exception) { PluginLog.add("host", "error", "$hook: ${e.message}") }
            }
        } catch (e: Exception) { /* executor already shut down */ }
    }

    // ------------------------------------------------------------ calls from JS
    private inner class HostImpl(private val r: Rt) : Host {
        override fun call(fn: String, json: String): String {
            return try {
                handle(fn, if (json.isEmpty()) JSONObject() else JSONObject(json))
            } catch (e: Exception) {
                PluginLog.add("host", "error", "$fn: ${e.message}")
                ""
            }
        }

        private fun handle(fn: String, o: JSONObject): String {
            when (fn) {
                "commit" -> { val t = o.getString("text"); val b = o.optInt("back", 0); main.post { env.commit(t, b) } }
                "delete" -> { val b = o.optInt("before", 0); val a = o.optInt("after", 0); main.post { env.deleteAround(b, a) } }
                "key" -> { val n = o.getString("name"); main.post { env.sendKey(n) } }
                "edit" -> { val n = o.getString("name"); main.post { env.editAction(n) } }
                "move" -> { val n = o.optInt("n", 0); main.post { env.moveCursor(n) } }
                "toast" -> { val t = o.optString("text"); main.post { env.toast(t) } }
                "haptic" -> main.post { env.haptic() }
                "layout" -> { val id = o.getString("id"); main.post { env.switchLayout(id) } }
                "panel" -> {
                    if (o.optBoolean("close")) main.post { env.closePanel() }
                    else { val p = o.getString("plugin"); val spec = o.getJSONObject("spec"); main.post { env.openPanel(p, spec) } }
                }
                "suggest" -> {
                    val seq = o.getInt("seq")
                    val list = jsonStrings(o.optJSONArray("list"))
                    main.post { env.pluginSuggestions(seq, list) }
                }
                "clipGet" -> return clipGet()
                "clipSet" -> { val t = o.getString("text"); main.post { clipSet(t) } }
                "storeGet" -> return store.data.getString("s:" + o.getString("plugin") + ":" + o.getString("key"), "") ?: ""
                "storeSet" -> store.data.edit().putString("s:" + o.getString("plugin") + ":" + o.getString("key"), o.getString("value")).apply()
                "storeDel" -> store.data.edit().remove("s:" + o.getString("plugin") + ":" + o.getString("key")).apply()
                "log" -> PluginLog.add(o.optString("plugin", "?"), o.optString("level", "log"), o.optString("msg"))
                "fetch" -> doFetch(r, o)
                "timer" -> setTimer(r, o)
                "clearTimer" -> { val t = r.timers.remove(o.getInt("id")); if (t != null) t.cancel(false) }
            }
            return ""
        }
    }

    private fun clipGet(): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c == null || c.itemCount == 0) "" else (c.getItemAt(0).coerceToText(ctx)?.toString() ?: "")
    } catch (e: Exception) { "" }

    private fun clipSet(t: String) {
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("openkeys", t))
        } catch (e: Exception) { }
    }

    private fun setTimer(r: Rt, o: JSONObject) {
        val id = o.getInt("id")
        val ms = maxOf(0L, o.optLong("ms", 0L))
        val rep = o.optBoolean("repeat", false)
        val task = Runnable {
            try { r.bridge?.dispatch("__timer", id.toString()) } catch (e: Exception) { }
            if (!rep) r.timers.remove(id)
        }
        try {
            val f: ScheduledFuture<*> =
                if (rep) r.ex.scheduleWithFixedDelay(task, maxOf(ms, 10L), maxOf(ms, 10L), TimeUnit.MILLISECONDS)
                else r.ex.schedule(task, ms, TimeUnit.MILLISECONDS)
            r.timers[id] = f
        } catch (e: Exception) { }
    }

    private fun doFetch(r: Rt, o: JSONObject) {
        val id = o.getInt("id")
        io.execute {
            val res = JSONObject().put("id", id)
            try {
                val c = URL(o.getString("url")).openConnection() as HttpURLConnection
                c.requestMethod = o.optString("method", "GET").uppercase()
                val to = o.optInt("timeout", 10000)
                c.connectTimeout = to
                c.readTimeout = to
                c.instanceFollowRedirects = true
                val h = o.optJSONObject("headers")
                if (h != null) {
                    val ks = h.keys()
                    while (ks.hasNext()) { val k = ks.next(); c.setRequestProperty(k, h.optString(k)) }
                }
                if (c.getRequestProperty("User-Agent") == null) c.setRequestProperty("User-Agent", "OpenKeys")
                val body = if (o.isNull("body")) null else o.optString("body")
                if (body != null && c.requestMethod != "GET") {
                    c.doOutput = true
                    c.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                var text = ""
                if (stream != null) {
                    val bytes = stream.use { s -> s.readBytes() }
                    text = String(bytes, 0, minOf(bytes.size, 2000000), Charsets.UTF_8)
                }
                res.put("ok", code in 200..299).put("status", code).put("text", text)
            } catch (e: Exception) {
                res.put("ok", false).put("status", 0).put("error", e.message ?: e.toString())
            }
            post(r, "__fetch", res.toString())
        }
    }
}

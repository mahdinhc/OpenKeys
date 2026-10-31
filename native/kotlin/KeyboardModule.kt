package com.openkeys.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import android.app.Activity
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class KeyboardModule(private val rc: ReactApplicationContext) : ReactContextBaseJavaModule(rc) {
    override fun getName() = "OpenKeys"

    private var fontPromise: Promise? = null
    private val actListener: ActivityEventListener = object : BaseActivityEventListener() {
        override fun onActivityResult(a: Activity, req: Int, res: Int, data: Intent?) {
            if (req != 7701) return
            val pr = fontPromise ?: return; fontPromise = null
            val uri: Uri? = data?.data
            if (res != Activity.RESULT_OK || uri == null) { pr.resolve(""); return }
            try {
                val dir = File(rc.filesDir, "fonts").also { it.mkdirs() }
                val out = File(dir, "custom.ttf")
                rc.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                android.graphics.Typeface.createFromFile(out) // validate
                var name = "custom font"
                rc.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex("_display_name"); if (i >= 0) name = c.getString(i) } }
                Prefs(rc).touch(); pr.resolve(name)
            } catch (e: Exception) { File(rc.filesDir, "fonts/custom.ttf").delete(); pr.reject("E_FONT", "Not a valid font file") }
        }
    }
    init { rc.addActivityEventListener(actListener) }

    private val synth = Synth()
    private var player: MelodyPlayer? = null

    @ReactMethod fun playMelody(text: String, speed: Double, p: Promise) {
        player?.stop()
        val steps = MelodyParser.parse(text)
        if (steps.isEmpty()) { p.reject("E_MELODY", "No playable notes found"); return }
        synth.params = SynthParams.from(Prefs(rc))
        val pl = MelodyPlayer(synth); player = pl
        pl.play(steps, speed.coerceIn(0.25, 4.0)) { p.resolve(true) }
    }

    @ReactMethod fun stopMelody(p: Promise) { player?.stop(); p.resolve(true) }

    @ReactMethod fun pickFont(p: Promise) {
        val a = currentActivity ?: return p.reject("E_FONT", "No activity")
        fontPromise = p
        a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7701)
    }

    @ReactMethod fun clearFont(p: Promise) { File(rc.filesDir, "fonts/custom.ttf").delete(); Prefs(rc).touch(); p.resolve(true) }

    // ---- developer typing log (password protected, encrypted at rest)
    @ReactMethod fun logHasPassword(p: Promise) = p.resolve(TypeLog.hasPassword(rc))
    @ReactMethod fun logSetPassword(old: String, new: String, p: Promise) = try { TypeLog.setPassword(rc, old, new); p.resolve(true) } catch (e: Exception) { p.reject("E_LOG", e.message, e) }
    @ReactMethod fun logVerify(pw: String, p: Promise) = p.resolve(TypeLog.verify(rc, pw))
    @ReactMethod fun logIndex(pw: String, p: Promise) = try { p.resolve(TypeLog.index(rc, pw)) } catch (e: Exception) { p.reject("E_LOG", e.message, e) }
    @ReactMethod fun logRead(pw: String, date: String, hour: String, p: Promise) = try { p.resolve(TypeLog.read(rc, pw, date, hour)) } catch (e: Exception) { p.reject("E_LOG", e.message, e) }
    @ReactMethod fun logClear(pw: String, p: Promise) = try { TypeLog.clear(rc, pw); p.resolve(true) } catch (e: Exception) { p.reject("E_LOG", e.message, e) }
    @ReactMethod fun logExport(pw: String, p: Promise) = try { p.resolve(TypeLog.export(rc, pw)) } catch (e: Exception) { p.reject("E_LOG", e.message, e) }
    @ReactMethod fun logImport(json: String, pw: String, p: Promise) = try { p.resolve(TypeLog.import(rc, json, pw)) } catch (e: Exception) { p.reject("E_LOG", e.message ?: "Import failed", e) }

    @ReactMethod fun getStatus(p: Promise) = try {
        val imm = rc.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val id = ComponentName(rc, OpenKeysIME::class.java).flattenToShortString()
        val m = Arguments.createMap()
        m.putBoolean("enabled", imm.enabledInputMethodList.any { it.id == id })
        m.putBoolean("selected", Settings.Secure.getString(rc.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id)
        p.resolve(m)
    } catch (e: Exception) { p.reject("E_STATUS", e) }

    @ReactMethod fun openImeSettings() {
        rc.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @ReactMethod fun showPicker() {
        (rc.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    @ReactMethod fun getConfig(p: Promise) = p.resolve(Prefs(rc).raw())

    @ReactMethod fun setConfig(json: String, p: Promise) = try { Prefs(rc).save(json); p.resolve(true) } catch (e: Exception) { p.reject("E_CONFIG", e) }

    @ReactMethod fun getLayouts(p: Promise) = try { p.resolve(LayoutRepo(rc).info().toString()) } catch (e: Exception) { p.reject("E_LAYOUTS", e) }

    @ReactMethod fun saveLayout(json: String, p: Promise) = try {
        LayoutRepo(rc).save(JSONObject(json)); Prefs(rc).touch(); p.resolve(true)
    } catch (e: Exception) { p.reject("E_LAYOUT", e.message ?: "Invalid layout", e) }

    @ReactMethod fun deleteLayout(id: String, p: Promise) = try {
        LayoutRepo(rc).delete(id); Prefs(rc).touch(); p.resolve(true)
    } catch (e: Exception) { p.reject("E_LAYOUT", e) }

    @ReactMethod fun exportBackup(p: Promise) = try {
        p.resolve(JSONObject().put("app", "openkeys").put("config", JSONObject(Prefs(rc).raw()))
            .put("layouts", LayoutRepo(rc).customAll()).toString(2))
    } catch (e: Exception) { p.reject("E_EXPORT", e) }

    @ReactMethod fun importBackup(json: String, p: Promise) = try {
        val o = JSONObject(json)
        val repo = LayoutRepo(rc)
        val a = o.optJSONArray("layouts") ?: JSONArray()
        for (i in 0 until a.length()) repo.save(a.getJSONObject(i))
        o.optJSONObject("config")?.let { Prefs(rc).save(it.toString()) }
        Prefs(rc).touch(); p.resolve(true)
    } catch (e: Exception) { p.reject("E_IMPORT", e.message ?: "Invalid backup", e) }

    @ReactMethod fun clearUserDictionary(p: Promise) = try {
        SuggestEngine(rc).clearUser(); p.resolve(true)
    } catch (e: Exception) { p.reject("E_DICT", e) }

    @ReactMethod fun addListener(name: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}

class KeyboardPackage : ReactPackage {
    override fun createNativeModules(rc: ReactApplicationContext) = listOf<com.facebook.react.bridge.NativeModule>(KeyboardModule(rc))
    override fun createViewManagers(rc: ReactApplicationContext) = emptyList<ViewManager<*, *>>()
}

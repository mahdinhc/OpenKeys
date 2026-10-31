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
import org.json.JSONArray
import org.json.JSONObject

class KeyboardModule(private val rc: ReactApplicationContext) : ReactContextBaseJavaModule(rc) {
    override fun getName() = "OpenKeys"

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

package com.openkeys.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Developer typing log. Layout: files/typelog/<yyyy-MM-dd>/<HH>.log, one AES-256-GCM encrypted entry per line.
 * Entries are encrypted with a random data key that is wrapped by the Android Keystore (never leaves the device
 * in plain form). Reading / exporting / clearing needs the user's password (PBKDF2 hash). Exports are encrypted
 * with a key derived from the password, so a backup file is useless without it.
 */
object TypeLog {
    private const val KS = "AndroidKeyStore"; private const val ALIAS = "openkeys_log_wrap"
    private val rnd = SecureRandom()
    private val main = Handler(Looper.getMainLooper())
    private val buf = StringBuilder(); private var bufApp = ""; private var bufT = 0L
    private var flushJob: Runnable? = null
    @Volatile private var dek: SecretKey? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("openkeys_log", Context.MODE_PRIVATE)
    private fun root(c: Context) = File(c.applicationContext.filesDir, "typelog")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    // ------------------------------------------------------------ keys
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun dataKey(c: Context): SecretKey {
        dek?.let { return it }
        synchronized(this) {
            dek?.let { return it }
            val p = sp(c); val stored = p.getString("dek", null)
            val k: SecretKey
            if (stored == null) {
                val raw = ByteArray(32).also { rnd.nextBytes(it) }
                val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, wrapKey())
                p.edit().putString("dek", b64(ci.iv) + ":" + b64(ci.doFinal(raw))).apply()
                k = SecretKeySpec(raw, "AES")
            } else {
                val (iv, ct) = stored.split(":").map { unb64(it) }
                val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
                k = SecretKeySpec(ci.doFinal(ct), "AES")
            }
            dek = k; return k
        }
    }

    private fun pbkdf(pw: String, salt: ByteArray, bits: Int = 256): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120_000, bits)).encoded

    private fun enc(k: SecretKey, data: ByteArray): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return b64(iv + ci.doFinal(data))
    }

    private fun dec(k: SecretKey, s: String): ByteArray {
        val all = unb64(s)
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, 12))
        return ci.doFinal(all, 12, all.size - 12)
    }

    // ------------------------------------------------------------ password
    fun hasPassword(c: Context) = sp(c).contains("pw_hash")

    fun verify(c: Context, pw: String): Boolean {
        val p = sp(c); val h = p.getString("pw_hash", null) ?: return false; val s = p.getString("pw_salt", null) ?: return false
        return MessageDigest.isEqual(unb64(h), pbkdf(pw, unb64(s)))
    }

    /** Set or change the password. When one exists, [old] must match. */
    fun setPassword(c: Context, old: String, new: String) {
        require(new.length >= 4) { "Password must be at least 4 characters" }
        if (hasPassword(c)) require(verify(c, old)) { "Wrong current password" }
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        sp(c).edit().putString("pw_salt", b64(salt)).putString("pw_hash", b64(pbkdf(new, salt))).apply()
    }

    // ------------------------------------------------------------ writing
    fun record(c: Context, text: String, app: String) {
        if (text.isEmpty()) return
        synchronized(buf) {
            if (buf.isNotEmpty() && app != bufApp) flushNow(c)
            if (buf.isEmpty()) { bufT = System.currentTimeMillis(); bufApp = app }
            buf.append(text)
            flushJob?.let { main.removeCallbacks(it) }
            if (buf.length > 300) flushNow(c) else {
                val j = Runnable { flushNow(c) }; flushJob = j; main.postDelayed(j, 4000)
            }
        }
    }

    fun flushNow(c: Context) {
        val t: String; val app: String; val ts: Long
        synchronized(buf) { if (buf.isEmpty()) return; t = buf.toString(); app = bufApp; ts = bufT; buf.setLength(0) }
        Thread { try { write(c, ts, app, t) } catch (e: Exception) { } }.start()
    }

    private fun write(c: Context, ts: Long, app: String, text: String) {
        val d = Date(ts)
        val dir = File(root(c), SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)).also { it.mkdirs() }
        val f = File(dir, SimpleDateFormat("HH", Locale.US).format(d) + ".log")
        val line = enc(dataKey(c), JSONObject().put("t", ts).put("app", app).put("s", text).toString().toByteArray())
        synchronized(this) { f.appendText(line + "\n") }
    }

    // ------------------------------------------------------------ reading
    private fun readFile(c: Context, f: File): List<JSONObject> {
        val k = dataKey(c)
        return f.readLines().mapNotNull { try { JSONObject(String(dec(k, it))) } catch (e: Exception) { null } }
    }

    private fun need(c: Context, pw: String) { require(verify(c, pw)) { "Wrong password" } }

    /** { "2026-10-31": { "14": 12, "15": 3 } } entry counts per date/hour. */
    fun index(c: Context, pw: String): String {
        need(c, pw); flushNow(c); Thread.sleep(50)
        val o = JSONObject()
        root(c).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.forEach { d ->
            val h = JSONObject()
            d.listFiles()?.sortedBy { it.name }?.forEach { f -> h.put(f.nameWithoutExtension, f.readLines().size) }
            o.put(d.name, h)
        }
        return o.toString()
    }

    fun read(c: Context, pw: String, date: String, hour: String): String {
        need(c, pw)
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && Regex("\\d{2}").matches(hour))
        val f = File(File(root(c), date), "$hour.log")
        return JSONArray(if (f.exists()) readFile(c, f) else emptyList<JSONObject>()).toString()
    }

    fun clear(c: Context, pw: String) { need(c, pw); root(c).deleteRecursively() }

    // ------------------------------------------------------------ export / import
    fun export(c: Context, pw: String): String {
        need(c, pw); flushNow(c); Thread.sleep(50)
        val days = JSONObject()
        root(c).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val hours = JSONObject()
            d.listFiles()?.sortedBy { it.name }?.forEach { f -> hours.put(f.nameWithoutExtension, JSONArray(readFile(c, f))) }
            days.put(d.name, hours)
        }
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val k = SecretKeySpec(pbkdf(pw, salt), "AES")
        val plain = JSONObject().put("days", days).toString().toByteArray()
        return JSONObject().put("app", "openkeys-typelog").put("v", 1).put("salt", b64(salt)).put("data", enc(k, plain)).toString(2)
    }

    fun import(c: Context, json: String, pw: String): Int {
        val o = JSONObject(json); require(o.optString("app") == "openkeys-typelog") { "Not an OpenKeys typing-log export" }
        val k = SecretKeySpec(pbkdf(pw, unb64(o.getString("salt"))), "AES")
        val days = try { JSONObject(String(dec(k, o.getString("data")))).getJSONObject("days") } catch (e: Exception) { throw IllegalArgumentException("Wrong password or corrupt file") }
        val dk = dataKey(c); var added = 0
        days.keys().forEach { date ->
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date))
            val hours = days.getJSONObject(date)
            hours.keys().forEach { hr ->
                require(Regex("\\d{2}").matches(hr))
                val dir = File(root(c), date).also { it.mkdirs() }; val f = File(dir, "$hr.log")
                val have = if (f.exists()) readFile(c, f).map { it.optLong("t") to it.optString("s") }.toHashSet() else HashSet()
                val arr = hours.getJSONArray(hr)
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    if (have.add(e.optLong("t") to e.optString("s"))) { f.appendText(enc(dk, e.toString().toByteArray()) + "\n"); added++ }
                }
            }
        }
        return added
    }
}

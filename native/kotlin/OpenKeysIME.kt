package com.openkeys.keyboard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import java.io.File
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.json.JSONArray
import org.json.JSONObject

class OpenKeysIME : InputMethodService(), KeyboardView.Listener, SuggestionBar.Listener {

    companion object { const val ACTION_VOICE = "com.openkeys.keyboard.VOICE_RESULT" }

    private lateinit var prefs: Prefs
    private lateinit var repo: LayoutRepo
    private lateinit var suggest: SuggestEngine
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var kb: KeyboardView
    private lateinit var bar: SuggestionBar
    private lateinit var strip: EmojiResultStrip
    private lateinit var emoji: EmojiPanel
    private lateinit var clip: ClipboardPanel
    private lateinit var piano: PianoPanel
    private lateinit var pp: PluginPanel
    private lateinit var cv: CanvasPanel
    private var cvPct = 100
    private lateinit var plugins: PluginHost
    private var viewReady = false

    // plugins
    private var pluginTools: List<Pair<String, String>> = emptyList()
    private var pluginSug: List<String> = emptyList()
    private var sugSeq = 0
    private var lastSugKey = ""

    private var theme = Theme.DARK
    private var enabled: List<LayoutDef> = emptyList()
    private var idx = 0
    private var forced: LayoutDef? = null
    private var layout: LayoutDef? = null
    private var translit: Transliterator? = null
    private var dict: WordDict? = null

    private val composing = StringBuilder()
    private var noSuggest = false      // no suggestion bar / composing text in this field
    private var noFix = false          // suggestions may show, but never auto-replace, expand or learn (email, URL)
    private var lastSpace = 0L
    private var lastCorrection: Pair<String, String>? = null
    private var lastWord = ""
    private var lastSwipe: String? = null

    // emoji search mode: typing goes into [query] instead of the editor
    private var searching = false
    private val query = StringBuilder()
    private var searchPrev: LayoutDef? = null

    private val main = Handler(Looper.getMainLooper())

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post { reloadAll() } }
    private val pluginPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "rev") main.post { safePlugins { plugins.reload() } }
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { handleClipChanged() }
    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val t = i?.getStringExtra("text") ?: return
            pendingVoice = t; pendingVoiceAt = SystemClock.uptimeMillis()
            main.post { flushVoice() }
            main.postDelayed({ flushVoice() }, 400)     // the editor may not have its focus back yet
        }
    }

    // Voice text can arrive while the editor connection is stale (the voice screen took focus): keep it until it can be typed.
    private var pendingVoice: String? = null
    private var pendingVoiceAt = 0L

    private fun flushVoice() {
        val t = pendingVoice ?: return
        if (SystemClock.uptimeMillis() - pendingVoiceAt > 30000) { pendingVoice = null; return }
        val ic = currentInputConnection ?: return
        commitComposing(false)
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        val lead = if (!before.isNullOrEmpty() && !before[0].isWhitespace() && !"([{\"'".contains(before[0])) " " else ""
        if (ic.commitText("$lead$t ", 1)) { pendingVoice = null; lastWord = t.substringAfterLast(' '); lg("[voice:$t]"); updateSuggestions() }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        DevLog.installCrashHandler(this)
        DevLog.session(this, "keyboard service created")
        DevLog.d(this, "IME", "onCreate")
        prefs = Prefs(this); repo = LayoutRepo(this); suggest = SuggestEngine(this)
        suggest.urbanOn = { prefs.bool("urbanWords", false) }     // Settings -> Typing -> Urban Dictionary words
        suggest.animeOn = { prefs.bool("animeWords", false) }   // Settings -> Typing -> Anime & manga words
        Icons.load(this)
        EmojiData.load(this)
        Thread { try { suggest.dict("en"); suggest.dict("bn") } catch (e: Exception) { } }.start()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        plugins = PluginHost(this, pluginEnv)
        PluginStore(this).sp.registerOnSharedPreferenceChangeListener(pluginPrefListener)
        safePlugins { plugins.reload() }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        val f = IntentFilter(ACTION_VOICE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(voiceReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(voiceReceiver, f)
    }

    override fun onDestroy() {
        DevLog.d(this, "IME", "onDestroy")
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        safePlugins { PluginStore(this).sp.unregisterOnSharedPreferenceChangeListener(pluginPrefListener); plugins.shutdown() }
        try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        try { unregisterReceiver(voiceReceiver) } catch (e: Exception) { }
        suggest.flush(); TypeLog.flushNow(this)
        super.onDestroy()
    }

    private fun handleClipChanged() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip ?: return
            if (c.itemCount == 0) return
            val item = c.getItemAt(0)
            val uri = item.uri
            if (uri != null) {
                if (uri.authority == "$packageName.clips") return   // our own paste fallback
                val type = contentResolver.getType(uri)
                if (type != null && type.startsWith("image/")) { ClipStore.addImage(this, uri); return }
            }
            val t = item.coerceToText(this)?.toString()
            if (!t.isNullOrBlank()) ClipStore.addText(this, t)
        } catch (e: Exception) { }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        DevLog.d(this, "IME", "onCreateInputView: building keyboard view")
        return try {
            buildInputView().also { DevLog.d(this, "IME", "onCreateInputView: done") }
        } catch (e: Exception) {
            // Never take the whole IME process down because the view failed to build: log it and show the error
            // right in the keyboard area so it is visible even when the debug log is off.
            viewReady = false
            DevLog.e(this, "IME", "onCreateInputView FAILED", e)
            errorView(e)
        }
    }

    private fun errorView(e: Throwable): View = android.widget.TextView(this).apply {
        setBackgroundColor(0xFF2B2E32.toInt()); setTextColor(0xFFF28B82.toInt()); textSize = 12f
        setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f)); minHeight = dpi(160f)
        text = "OpenKeys failed to start\n${e.javaClass.simpleName}: ${e.message}\n" +
            e.stackTrace.take(6).joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } +
            "\n\nTurn on Developer > Debug log in the OpenKeys app for the full trace."
    }

    private fun buildInputView(): View {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        bar = SuggestionBar(this).also { it.listener = this }
        strip = EmojiResultStrip(this) { e ->
            lg(e); commitComposing(false); currentInputConnection?.commitText(e, 1); ClipStore.addEmoji(this, e)
        }
        strip.onHold = { e ->
            if (prefs.bool("stickerOnHold", true)) EmojiSticker.make(this, e) { f ->
                if (f != null) { ClipStore.addEmoji(this, e); pasteImage(Clip("k:" + f.name, "image", f.name, false, System.currentTimeMillis())) }
            }
        }
        strip.visibility = View.GONE
        content = FrameLayout(this).apply { clipChildren = false }
        kb = KeyboardView(this).also { it.listener = this }
        emoji = EmojiPanel(this, object : EmojiPanel.Callback {
            override fun onEmoji(e: String) { lg(e); commitComposing(false); currentInputConnection?.commitText(e, 1) }
            override fun onBack() = showKeyboard()
            override fun onBackspace() = onDelete()
            override fun onSearch() = startSearch()
            override fun onSticker(f: File) { commitComposing(false); pasteImage(Clip("k:" + f.name, "image", f.name, false, System.currentTimeMillis())) }
        })
        clip = ClipboardPanel(this, object : ClipboardPanel.Callback {
            override fun onPasteText(s: String) { lg("[paste:$s]"); commitComposing(false); currentInputConnection?.commitText(s, 1) }
            override fun onPasteImage(c: Clip) = pasteImage(c)
            override fun onBack() = showKeyboard()
        })
        piano = PianoPanel(this, object : PianoPanel.Callback {
            override fun onMelody(text: String) { commitComposing(false); currentInputConnection?.commitText(text, 1); lg(text) }
            override fun onBack() = showKeyboard()
            override fun onSpace() = this@OpenKeysIME.onSpace()
            override fun onBackspace() = onDelete()
            override fun onEnter() = this@OpenKeysIME.onEnter()
        })
        pp = PluginPanel(this, object : PluginPanel.Callback {
            override fun onItem(plugin: String, index: Int, text: String?) {
                if (text != null) { lg("[panel:$text]"); commitComposing(false); currentInputConnection?.commitText(text, 1) }
                else plugins.runAsync("panel", JSONObject().put("plugin", plugin).put("index", index).put("ctx", pluginCtx(true)))
            }
            override fun onBack() = showKeyboard()
        })
        cv = CanvasPanel(this, object : CanvasPanel.Callback {
            override fun onEvent(o: JSONObject) = plugins.runAsync("canvas", o)
            override fun onBack() = showKeyboard()
            override fun onInsert(file: String) = pasteImage(Clip(file, "image", file, false, System.currentTimeMillis()))
        })
        cv.visibility = View.GONE
        content.addView(kb, FrameLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT))
        emoji.visibility = View.GONE; clip.visibility = View.GONE; pp.visibility = View.GONE
        content.addView(emoji, FrameLayout.LayoutParams(mp, mp))
        content.addView(clip, FrameLayout.LayoutParams(mp, mp))
        piano.visibility = View.GONE
        content.addView(piano, FrameLayout.LayoutParams(mp, mp))
        content.addView(pp, FrameLayout.LayoutParams(mp, mp))
        content.addView(cv, FrameLayout.LayoutParams(mp, mp))
        root.addView(bar, LinearLayout.LayoutParams(mp, dpi(48f)))
        root.addView(strip, LinearLayout.LayoutParams(mp, dpi(52f)))
        root.addView(content, LinearLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = if (Build.VERSION.SDK_INT >= 35) insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom else 0
            v.setPadding(0, 0, 0, nav + dpi(prefs.int("bottomPad", 0).toFloat()))
            insets
        }
        viewReady = true
        reloadAll()
        return root
    }

    private fun topTools(): List<String> {
        val a = prefs.cfg.optJSONArray("topTools") ?: return listOf("emoji", "clip", "piano", "settings")
        return jsonStrings(a).filter { it != "abc" }
    }

    private fun buildTypeface(): Typeface {
        val bold = prefs.bool("fontBold", false)
        val italic = prefs.bool("fontItalic", false)
        val base: Typeface = when (prefs.str("fontFamily", "default")) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            "medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            "black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            "custom" -> File(filesDir, "fonts/custom.ttf").let { f ->
                if (f.exists()) try { Typeface.createFromFile(f) } catch (e: Exception) { Typeface.DEFAULT } else Typeface.DEFAULT
            }
            else -> Typeface.DEFAULT   // "default" and the removed casual/cursive ids
        }
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return if (style == 0) base else Typeface.create(base, style)
    }

    private val bgDraw = BgDrawable()

    // ---- developer typing log
    private var logOn = false
    /** Settings -> Developer -> Key log -> "Fields to record": "everything" also records password fields (marked with a lock). */
    private var logSecure = false
    private fun lg(t: String) {
        if (!logOn || t.isEmpty() || (isPasswordField && !logSecure)) return
        TypeLog.record(this, t, (currentInputEditorInfo?.packageName ?: "") + (if (isPasswordField) " \uD83D\uDD12" else ""))
    }
    private var isPasswordField = false

    private fun reloadAll() {
        if (!viewReady) return
        DevLog.d(this, "IME", "reloadAll")
        prefs.reload(); repo.invalidate()
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        theme = Theme.from(prefs, night)
        val bgs = BgStyle(prefs)
        val img = bgs.active
        bgDraw.set(bgs, theme.bg)
        if (img) root.background = bgDraw else root.setBackgroundColor(theme.bg)
        // with an image: keys area + top bar are see-through, panels keep `panels`% of the theme color
        val seeThrough = if (img) theme.withBg(0) else theme
        val panelTheme = if (img) theme.withBg((theme.bg and 0xFFFFFF) or ((bgs.panels * 255 / 100) shl 24)) else theme
        root.setPadding(0, 0, 0, dpi(prefs.int("bottomPad", 0).toFloat()))
        bar.setTools(topTools(), prefs.int("topIconSize", 50).coerceIn(30, 80) / 100f)
        bar.setPluginTools(pluginTools)
        bar.applyTheme(seeThrough); piano.applyTheme(panelTheme); emoji.applyTheme(panelTheme); clip.applyTheme(panelTheme); strip.applyTheme(seeThrough); pp.applyTheme(panelTheme); cv.applyTheme(panelTheme)
        kb.apply {
            theme = this@OpenKeysIME.theme
            heightScale = prefs.int("heightScale", 100); fontScale = prefs.int("fontScale", 100)
            radiusDp = prefs.int("keyRadius", 12); gapDp = prefs.int("keyGap", 4)
            showHints = prefs.bool("showHints", true); popupPreview = prefs.bool("popupPreview", true)
            swipeOn = prefs.bool("swipeTyping", true); hapticOn = prefs.bool("haptic", true)
            hapticMs = prefs.int("hapticMs", 12); soundOn = prefs.bool("sound", false)
            longPressMs = prefs.int("longPressMs", 350); numberRow = prefs.bool("numberRow", true); numAlts = prefs.numberAlts()
            oneHanded = prefs.str("oneHanded", "off")
            typeface = buildTypeface()
            keyAlpha = prefs.int("keyAlpha", 100).coerceIn(0, 100); keyBorder = prefs.int("keyBorder", 0).coerceIn(0, 6)
            keyShadow = prefs.bool("keyShadow", true)
        }
        logOn = prefs.bool("devMode", false) && prefs.bool("devLog", false) && TypeLog.hasPassword(this)
        logSecure = prefs.str("keyLogScope", "skipPasswords") == "everything"
        enabled = prefs.enabledLayouts().mapNotNull { repo.get(it) }.ifEmpty { listOfNotNull(repo.get("en_qwerty")) }
        if (idx >= enabled.size) idx = 0
        val l = forced?.let { repo.get(it.id) } ?: enabled.getOrNull(idx)
        if (l != null) setLayout(l)
        DevLog.d(this, "IME", "reloadAll done: layouts=${enabled.joinToString(",") { it.id }} active=${l?.id}")
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (pendingVoice != null) { main.post { flushVoice() }; main.postDelayed({ flushVoice() }, 300) }
        DevLog.d(this, "IME", "onStartInputView pkg=${info.packageName} inputType=0x${Integer.toHexString(info.inputType)} imeOptions=0x${Integer.toHexString(info.imeOptions)} restarting=$restarting viewReady=$viewReady")
        if (!viewReady) return   // the view failed to build (see errorView) - nothing to drive
        composing.setLength(0); lastCorrection = null; lastSwipe = null; lastWord = ""
        pluginSug = emptyList(); lastSugKey = ""
        searching = false; query.setLength(0); strip.visibility = View.GONE; bar.setSearch(false, "")
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val secret = v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val addressy = v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_URI
        val appSaysNo = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val searchField = (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH
        // "Where to show suggestions": strict = obey every app hint (old behaviour); moderate = also in search bars and in
        // fields that merely opted out; everywhere = every text field. Passwords and number/phone fields never get them.
        noSuggest = when (prefs.str("suggestMode", "moderate")) {
            "strict" -> !isText || appSaysNo || secret || addressy
            "everywhere" -> !isText || secret
            else -> !isText || secret || (addressy && !searchField)
        }
        noFix = noSuggest || addressy || appSaysNo     // shown, but never auto-replaced / learned there
        isPasswordField = v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        forced = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) repo.get("numeric") else null
        kb.enterIcon = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "forward"
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_DONE -> "done"
            else -> "return"
        }
        (forced ?: enabled.getOrNull(idx))?.let { setLayout(it) }
        showKeyboard(); updateAutoCaps(); updateSuggestions()
        if (plugins.hasHook("onStartInput")) plugins.runAsync("onStartInput", JSONObject().put("ctx", pluginCtx(false)))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        DevLog.d(this, "IME", "onFinishInputView finishing=$finishingInput")
        commitComposing(false); suggest.flush()
        if (viewReady && plugins.hasHook("onFinishInput")) plugins.runAsync("onFinishInput", JSONObject().put("ctx", pluginCtx(false)))
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        if (!viewReady) return
        if (plugins.hasHook("onSelection")) plugins.runAsync("onSelection", JSONObject().put("start", nss).put("end", nse).put("ctx", pluginCtx(true)))
        if (composing.isNotEmpty() && (cs == -1 || nss != ce)) {
            composing.setLength(0); currentInputConnection?.finishComposingText(); updateSuggestions()
        } else if (composing.isEmpty()) updateAutoCaps()
    }

    // ------------------------------------------------------------ layout / panels
    private fun setLayout(def: LayoutDef) {
        commitComposing(false)
        layout = def; translit = def.translit; dict = suggest.dict(def.dict)
        kb.langNames = enabled.map { it.name }; kb.langIndex = idx
        kb.applyLayout(def)
        updateSuggestions(); updateAutoCaps()
    }

    private fun showKeyboard() {
        if (cv.visibility == View.VISIBLE) cv.closed()
        piano.silence(); cv.visibility = View.GONE; kb.visibility = View.VISIBLE; emoji.visibility = View.GONE; clip.visibility = View.GONE; piano.visibility = View.GONE; pp.visibility = View.GONE
        bar.setMode("abc")
    }

    private var kbH = 0
    private fun showPanel(p: View) {
        if (p !== cv && cv.visibility == View.VISIBLE) cv.closed()
        cv.visibility = View.GONE
        if (kb.visibility == View.VISIBLE && kb.height > 0) kbH = kb.height
        if (kbH == 0) kbH = dpi(260f)
        val pct = when (p) { emoji -> prefs.int("emojiHeight", 100); piano -> prefs.int("pianoHeight", 100); cv -> cvPct; else -> 100 }.coerceIn(50, 260)
        p.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (kbH * pct / 100f).toInt())
        kb.visibility = View.GONE; emoji.visibility = View.GONE; clip.visibility = View.GONE; piano.visibility = View.GONE; pp.visibility = View.GONE
        p.visibility = View.VISIBLE
        val pn = when (p) { emoji -> "emoji"; clip -> "clip"; pp, cv -> "plugin"; else -> "piano" }
        DevLog.d(this, "IME", "showPanel $pn")
        bar.setMode(pn)
    }

    override fun onSelectLayout(index: Int) {
        if (forced != null || enabled.isEmpty()) return
        idx = index.coerceIn(0, enabled.size - 1)
        setLayout(enabled[idx])
    }

    // ------------------------------------------------------------ emoji search
    private fun startSearch() {
        commitComposing(false)
        searching = true; query.setLength(0)
        searchPrev = layout
        enabled.firstOrNull { it.locale == "en" }?.let { if (it !== layout) setLayout(it) }
        showKeyboard()
        strip.visibility = View.VISIBLE
        refreshSearch()
    }

    private fun exitSearch(backToPanel: Boolean) {
        searching = false; query.setLength(0)
        strip.visibility = View.GONE; bar.setSearch(false, "")
        searchPrev?.let { if (it !== layout) setLayout(it) }
        if (backToPanel) { emoji.open(); showPanel(emoji) }
    }

    private fun refreshSearch() {
        bar.setSearch(true, query.toString())
        strip.setResults(if (query.isEmpty()) ClipStore.recentEmoji(this) else EmojiData.search(query.toString()))
    }

    // ------------------------------------------------------------ images from the clipboard panel
    private fun pasteImage(c: Clip) {
        val f = ClipStore.imageFile(this, c.value)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.clips", f)
        val mime = ClipStore.mimeOf(f.name)
        val ei = currentInputEditorInfo
        val ic = currentInputConnection
        if (ei != null && ic != null && EditorInfoCompat.getContentMimeTypes(ei).any { ClipDescription.compareMimeTypes(mime, it) }) {
            val info = InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mime)), null)
            val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
            if (InputConnectionCompat.commitContent(ic, ei, info, flags, null)) return
        }
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newUri(contentResolver, "image", uri))
            Toast.makeText(this, "This field can't take images directly. Image copied - use the app's paste.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------ composing helpers
    private fun isWordCp(cp: Int): Boolean {
        if (Character.isLetter(cp) || cp == 0x27 || cp == 0x200C || cp == 0x200D) return true
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
    }

    private fun isWordInput(s: String): Boolean {
        translit?.let { tr -> return s.all { tr.accepts(it) } }
        if (noSuggest) return false
        var i = 0
        while (i < s.length) { val cp = s.codePointAt(i); if (!isWordCp(cp)) return false; i += Character.charCount(cp) }
        return s.isNotEmpty()
    }

    private fun display(): String = translit?.convert(composing.toString()) ?: composing.toString()

    private fun learn(w: String) {
        if (prefs.bool("learnWords", true) && !noFix) dict?.learn(w, lastWord)
        lastWord = w
    }

    private fun commitComposing(auto: Boolean) {
        if (composing.isEmpty()) return
        var w = display()
        lastCorrection = null
        if (auto && !noFix) {
            val sc = prefs.shortcuts()[w.lowercase()]
            if (sc != null) w = sc
            else if (translit != null && prefs.bool("autocorrect", true) && prefs.bool("suggestions", true)) dict?.autoCorrect(composing.toString())?.let { w = it }
            else if (translit == null && prefs.bool("autocorrect", true) && prefs.bool("suggestions", true)) {
                dict?.correct(w, lastWord)?.let { c -> val fixed = matchCase(w, c); lastCorrection = w to fixed; w = fixed }
            }
            if (plugins.hasHook("onCommit")) {
                val r = plugins.runSync("onCommit", JSONObject().put("word", w).put("ctx", pluginCtx(false)))
                if (r != null && r.has("text")) w = r.optString("text", w)
            }
        }
        currentInputConnection?.commitText(w, 1)
        composing.setLength(0)
        learn(w)
    }

    private fun fixOnAuto() = prefs.bool("autocorrect", true)

    private fun isEmojiStr(s: String) = s.isNotEmpty() && !s[0].isLetterOrDigit() && s.codePointAt(0) > 0x2000

    private fun updateSuggestions(fromPlugin: Boolean = false) {
        if (!viewReady) return
        if (searching || noSuggest || !prefs.bool("suggestions", true)) { bar.setSuggestions(emptyList()); return }
        if (!fromPlugin) {
            val k = display() + "\u0000" + lastWord
            if (k != lastSugKey) {
                lastSugKey = k; pluginSug = emptyList()
                if (plugins.hasHook("suggest")) {
                    sugSeq++
                    plugins.runAsync("suggest", JSONObject().put("seq", sugSeq).put("ctx", pluginCtx(false)))
                }
            }
        }
        val d = dict
        val emojiOn = prefs.bool("emojiSuggest", true) && translit == null
        if (composing.isNotEmpty()) {
            val typed = display()
            val comp = d?.complete(typed, 4, lastWord) ?: emptyList()
            val fixOn = prefs.bool("autocorrect", true) && translit == null
            val fix = if (fixOn) d?.correct(typed, lastWord)?.let { matchCase(typed, it) } else null
            val emo = if (emojiOn) EmojiData.forWord(typed, 2) else emptyList()
            val ac = if (translit != null && fixOnAuto()) d?.autoCorrect(composing.toString()) else null
            val out = ArrayList<String>(); out.add(typed)
            if (ac != null && ac != typed) out.add(ac)
            else if (fix != null) out.add(fix)
            else if (emo.isNotEmpty() && (d?.known(typed.lowercase()) == true)) { emo.forEach { if (out.size < 3) out.add(it) } }
            comp.filter { it !in out }.forEach { if (out.size < 3) out.add(it) }
            if (out.size < 3 && fix == null && translit == null) d?.fixes(typed, 2, lastWord)?.forEach { if (it !in out && out.size < 3) out.add(it) }
            if (out.size < 3 && emo.isNotEmpty()) emo.forEach { if (it !in out && out.size < 3) out.add(it) }
            bar.setSuggestions(mergeSug(out))
        } else if (lastWord.isNotEmpty()) {
            val nx = d?.next(lastWord, 3) ?: emptyList()
            val emo = if (emojiOn) EmojiData.forWord(lastWord, 2) else emptyList()
            val out = arrayOf(nx.getOrNull(0) ?: "", emo.getOrNull(0) ?: nx.getOrNull(1) ?: "",
                emo.getOrNull(1) ?: nx.getOrNull(if (emo.isEmpty()) 2 else 1) ?: "")
            bar.setSuggestions(mergeSug(if (out.all { it.isEmpty() }) emptyList() else out.toList()))
        } else bar.setSuggestions(mergeSug(emptyList()))
    }

    /** Plugin suggestions go right after the typed word (or first when nothing is being typed). */
    private fun mergeSug(base: List<String>): List<String> {
        if (pluginSug.isEmpty()) return base
        val out = ArrayList<String>()
        if (composing.isNotEmpty() && base.isNotEmpty()) out.add(base[0])
        for (x in pluginSug) if (x.isNotEmpty() && x !in out && out.size < 3) out.add(x)
        for (x in base) if (x.isNotEmpty() && x !in out && out.size < 3) out.add(x)
        return out
    }

    private fun updateAutoCaps() {
        val l = layout ?: return
        if (!viewReady || !l.autoCap || !prefs.bool("autoCap", true) || kb.layerName != "base") return
        val ei = currentInputEditorInfo ?: return
        if ((ei.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return
        if (kb.shiftState == 2 || (kb.shiftState == 1 && !kb.autoShifted)) return
        val mode = currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0
        kb.setShift(if (mode != 0) 1 else 0, true)
    }

    // ------------------------------------------------------------ KeyboardView.Listener
    override fun onText(text: String, letterLayer: Boolean) {
        if (searching) {
            if (text.length == 1 && (text[0].isLetterOrDigit())) { query.append(text.lowercase()); refreshSearch() }
            return
        }
        val ic = currentInputConnection ?: return
        lastSwipe = null; lg(text)
        if (plugins.hasHook("onText")) plugins.runAsync("onText", JSONObject().put("text", text).put("ctx", pluginCtx(false)))
        if (letterLayer && isWordInput(text)) {
            composing.append(text)
            ic.setComposingText(display(), 1)
            lastCorrection = null
            updateSuggestions()
            return
        }
        commitComposing(true)
        lastCorrection = null; lastSpace = 0
        ic.commitText(text, 1)
        lastWord = ""
        updateSuggestions()
    }

    override fun onSpecial(type: String) {
        when (type) {
            "space" -> if (searching) { if (query.isNotEmpty()) { query.append(' '); refreshSearch() } } else onSpace()
            "enter" -> if (searching) exitSearch(true) else onEnter()
            "lang" -> if (forced == null && enabled.size > 1) onSelectLayout((idx + 1) % enabled.size)
            "picker" -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            "letters" -> { forced = null; enabled.getOrNull(idx)?.let { setLayout(it) } }
            "emoji" -> if (!searching) { emoji.open(); showPanel(emoji) }
            "clip" -> { clip.refresh(); showPanel(clip) }
            "piano" -> if (!searching) { piano.open(prefs); showPanel(piano) }
            "mic" -> startVoice()
            else -> if (type.startsWith("act:")) doAct(type.substring(4)) else if (type.startsWith("js:")) runAction(type.substring(3))
        }
    }

    private fun doAct(name: String) {
        if (searching) return
        commitComposing(false)
        val ic = currentInputConnection ?: return
        when (name) {
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "selectall" -> ic.performContextMenuAction(android.R.id.selectAll)
        }
    }

    override fun onKey(keyCode: Int, meta: Int) {
        if (searching) return
        commitComposing(false)
        val ic = currentInputConnection ?: return
        lg("[key:" + KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_") + (if (meta != 0) "+" + meta else "") + "]")
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta))
        lastSwipe = null; lastCorrection = null
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        lg(" ")
        lastSwipe = null
        val had = composing.isNotEmpty()
        commitComposing(true)
        val now = SystemClock.uptimeMillis()
        if (!had && !noFix && prefs.bool("doubleSpacePeriod", true) && now - lastSpace < 500) {
            val b = ic.getTextBeforeCursor(2, 0)?.toString()
            if (b != null && b.length == 2 && b[1] == ' ' && !b[0].isWhitespace() && !".!?,;:।".contains(b[0])) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText((layout?.sentenceEnd ?: ".") + " ", 1)
                lastSpace = 0; lastCorrection = null
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpace = now
        updateSuggestions()
    }

    private fun onEnter() {
        lg("\n")
        commitComposing(true); lastCorrection = null; lastSwipe = null
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo ?: return
        val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        val multi = (ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multi && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        lastWord = ""; updateSuggestions()
    }

    override fun onDelete() {
        if (!searching) lg("\u232B")
        if (searching) {
            if (query.isNotEmpty()) { query.setLength(query.length - 1); refreshSearch() }
            return
        }
        val ic = currentInputConnection ?: return
        lastSwipe = null
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(display(), 1)
            updateSuggestions(); return
        }
        lastCorrection?.let { (orig, fixed) ->
            lastCorrection = null
            if (ic.getTextBeforeCursor(fixed.length + 1, 0)?.toString() == "$fixed ") {
                ic.deleteSurroundingText(fixed.length + 1, 0)
                composing.append(orig); ic.setComposingText(orig, 1); updateSuggestions(); return
            }
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        lastSpace = 0
    }

    override fun onCursorMove(steps: Int) {
        if (searching) return
        commitComposing(false)
        repeat(Math.abs(steps)) { sendDownUpKeyEvents(if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
    }

    override fun onSwipe(input: SwipeInput, shifted: Boolean) {
        if (searching) return
        val ic = currentInputConnection ?: return
        commitComposing(true)
        val c = dict?.swipe(input, 4, lastWord) ?: emptyList()
        if (c.isEmpty()) return
        lg("[swipe:" + c[0] + "]")
        val words = c.map { if (shifted) it.replaceFirstChar { ch -> ch.uppercase() } else it }
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        if (!before.isNullOrEmpty() && !before[0].isWhitespace() && !"([{\"'".contains(before[0])) ic.commitText(" ", 1)
        ic.commitText(words[0] + " ", 1)
        lastSwipe = words[0] + " "; lastWord = words[0]
        if (prefs.bool("learnWords", true)) dict?.learn(words[0], null)
        bar.setSuggestions(if (words.size < 2) words else listOf(words[1], words[0], words.getOrElse(2) { "" }))
    }

    override fun onLayerChange(name: String) { if (name == "base") updateAutoCaps() }

    // ------------------------------------------------------------ SuggestionBar.Listener
    override fun onPick(s: String) {
        val ic = currentInputConnection ?: return
        lg("[" + s + "]")
        if (isEmojiStr(s)) {
            ClipStore.addEmoji(this, s)
            if (composing.isNotEmpty()) {
                val w = display(); composing.setLength(0); lastCorrection = null
                ic.commitText("$w $s ", 1); learn(w)
            } else {
                lastSwipe?.let { ic.deleteSurroundingText(it.length, 0); ic.commitText(it, 1) }
                lastSwipe = null
                ic.commitText("$s ", 1)
            }
            updateSuggestions(); return
        }
        if (composing.isNotEmpty()) {
            composing.setLength(0); lastCorrection = null
            ic.commitText("$s ", 1); learn(s)
        } else {
            lastSwipe?.let { ic.deleteSurroundingText(it.length, 0) }
            ic.commitText("$s ", 1); lastSwipe = null; lastWord = s
        }
        updateSuggestions()
    }

    override fun onTool(name: String) {
        when (name) {
            "abc" -> { if (searching) exitSearch(false); showKeyboard() }
            "emoji" -> onSpecial("emoji")
            "clip" -> onSpecial("clip")
            "piano" -> onSpecial("piano")
            "mic" -> startVoice()
            "searchBack" -> exitSearch(true)
            "hand" -> {
                val next = when (prefs.str("oneHanded", "off")) { "off" -> "right"; "right" -> "left"; else -> "off" }
                prefs.save(JSONObject(prefs.raw()).put("oneHanded", next).toString())
            }
            "settings" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> if (name.startsWith("js:")) runAction(name.substring(3))
        }
    }

    // ------------------------------------------------------------ plugins
    /** The plugin runtime must never take the keyboard down. */
    private fun safePlugins(f: () -> Unit) {
        try { f() } catch (e: Throwable) { DevLog.e(this, "Plugins", "plugin runtime error", e); PluginLog.add("host", "error", e.toString()) }
    }

    /** Toolbar button or {js=...} key. Takes a full snapshot (selection, text around the cursor) on the UI thread first. */
    private fun runAction(key: String) {
        if (searching) return
        commitComposing(false)
        plugins.runAsync("action", JSONObject().put("key", key).put("ctx", pluginCtx(true)))
    }

    /** Context handed to plugin hooks. Must be built on the main thread. */
    private fun pluginCtx(full: Boolean): JSONObject {
        val ic = currentInputConnection
        val ei = currentInputEditorInfo
        val o = JSONObject()
        o.put("word", display())
        o.put("prev", lastWord)
        o.put("before", ic?.getTextBeforeCursor(100, 0)?.toString() ?: "")
        o.put("package", ei?.packageName ?: "")
        o.put("inputType", ei?.inputType ?: 0)
        o.put("layout", layout?.id ?: "")
        o.put("locale", layout?.locale ?: "")
        o.put("noSuggest", noSuggest)
        o.put("noFix", noFix)
        o.put("secure", isPasswordField)
        if (full) {
            o.put("after", ic?.getTextAfterCursor(100, 0)?.toString() ?: "")
            o.put("selected", ic?.getSelectedText(0)?.toString() ?: "")
        }
        return o
    }

    private val pluginEnv = object : PluginEnv {
        override fun commit(text: String, back: Int) {
            lg("[plugin:$text]")
            commitComposing(false)
            currentInputConnection?.commitText(text, 1)
            if (back > 0) onCursorMove(-back)
        }
        override fun deleteAround(before: Int, after: Int) {
            commitComposing(false)
            currentInputConnection?.deleteSurroundingText(before, after)
        }
        override fun sendKey(name: String, mods: JSONObject?) {
            commitComposing(false)
            val code = when (name.lowercase()) {
                "enter" -> KeyEvent.KEYCODE_ENTER
                "del", "backspace" -> KeyEvent.KEYCODE_DEL
                "forwarddel" -> KeyEvent.KEYCODE_FORWARD_DEL
                "tab" -> KeyEvent.KEYCODE_TAB
                "esc" -> KeyEvent.KEYCODE_ESCAPE
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "up" -> KeyEvent.KEYCODE_DPAD_UP
                "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "home" -> KeyEvent.KEYCODE_MOVE_HOME
                "end" -> KeyEvent.KEYCODE_MOVE_END
                "space" -> KeyEvent.KEYCODE_SPACE
                "pageup" -> KeyEvent.KEYCODE_PAGE_UP
                "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
                "insert" -> KeyEvent.KEYCODE_INSERT
                else -> KeyEvent.keyCodeFromString("KEYCODE_" + name.uppercase()).let { if (it == KeyEvent.KEYCODE_UNKNOWN) return else it }   // a-z, 0-9, f1-f12 ...
            }
            var meta = 0
            if (mods != null) {
                if (mods.optBoolean("ctrl")) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                if (mods.optBoolean("alt")) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                if (mods.optBoolean("shift")) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                if (mods.optBoolean("meta")) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            }
            if (meta != 0) {
                val ic = currentInputConnection ?: return
                val t = android.os.SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
                return
            }
            sendDownUpKeyEvents(code)
        }
        override fun editAction(name: String) {
            commitComposing(false)
            val id = when (name) {
                "selectAll" -> android.R.id.selectAll
                "copy" -> android.R.id.copy
                "cut" -> android.R.id.cut
                "paste" -> android.R.id.paste
                else -> return
            }
            currentInputConnection?.performContextMenuAction(id)
        }
        override fun moveCursor(n: Int) { onCursorMove(n) }
        override fun toast(text: String) { Toast.makeText(this@OpenKeysIME, text, Toast.LENGTH_SHORT).show() }
        override fun vibrate(ms: Int) {
            try {
                @Suppress("DEPRECATION") val v = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                @Suppress("DEPRECATION") v?.vibrate(ms.toLong().coerceIn(1, 500))
            } catch (e: Exception) { }
        }
        override fun openUrl(url: String) {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { Toast.makeText(this@OpenKeysIME, "Can't open $url", Toast.LENGTH_SHORT).show() }
        }
        override fun themeJson(): String = theme.toJson().toString()
        override fun haptic() { if (viewReady) kb.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        override fun switchLayout(id: String) {
            val i = enabled.indexOfFirst { it.id == id }
            if (i >= 0) onSelectLayout(i)
            else Toast.makeText(this@OpenKeysIME, "Layout \"$id\" is not enabled", Toast.LENGTH_SHORT).show()
        }
        override fun openPanel(plugin: String, spec: JSONObject) {
            if (!viewReady) return
            pp.show(plugin, spec)
            this@OpenKeysIME.showPanel(pp)
        }
        override fun closePanel() { if (viewReady) showKeyboard() }
        override fun openCanvas(plugin: String, cid: Int, spec: JSONObject) {
            if (!viewReady) return
            cvPct = spec.optInt("height", 100)
            cv.show(plugin, cid, spec)
            this@OpenKeysIME.showPanel(cv)
        }
        override fun canvasOps(plugin: String, cid: Int, ops: JSONArray) {
            if (viewReady && cv.plugin == plugin && cv.cid == cid) cv.apply(ops)
        }
        override fun canvasClose(plugin: String, cid: Int) {
            if (viewReady && cv.visibility == View.VISIBLE && cv.plugin == plugin && cv.cid == cid) showKeyboard()
        }
        override fun pluginSuggestions(seq: Int, list: List<String>) {
            if (seq != -1 && seq != sugSeq) return   // -1 = keys.suggest(): always applies to the current word
            pluginSug = (pluginSug + list).distinct()
            updateSuggestions(true)
        }
        override fun pluginsLoaded(tools: List<Pair<String, String>>) {
            pluginTools = tools
            if (viewReady) bar.setPluginTools(tools)
        }
    }

    private fun startVoice() {
        val loc = layout?.locale ?: "en"
        val tag = when (loc) { "en" -> "en-US"; "bn" -> "bn-BD"; else -> loc }
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("lang", tag))
    }
}

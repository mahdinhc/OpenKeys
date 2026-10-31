package com.openkeys.keyboard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
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
    private var viewReady = false

    private var theme = Theme.DARK
    private var enabled: List<LayoutDef> = emptyList()
    private var idx = 0
    private var forced: LayoutDef? = null
    private var layout: LayoutDef? = null
    private var translit: Transliterator? = null
    private var dict: WordDict? = null

    private val composing = StringBuilder()
    private var noSuggest = false
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
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { handleClipChanged() }
    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val t = i?.getStringExtra("text") ?: return
            commitComposing(false); currentInputConnection?.commitText(t, 1)
        }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this); repo = LayoutRepo(this); suggest = SuggestEngine(this)
        Icons.load(this)
        EmojiData.load(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        val f = IntentFilter(ACTION_VOICE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(voiceReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(voiceReceiver, f)
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        try { unregisterReceiver(voiceReceiver) } catch (e: Exception) { }
        suggest.flush()
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
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        bar = SuggestionBar(this).also { it.listener = this }
        strip = EmojiResultStrip(this) { e ->
            commitComposing(false); currentInputConnection?.commitText(e, 1); ClipStore.addEmoji(this, e)
        }
        strip.visibility = View.GONE
        content = FrameLayout(this).apply { clipChildren = false }
        kb = KeyboardView(this).also { it.listener = this }
        emoji = EmojiPanel(this, object : EmojiPanel.Callback {
            override fun onEmoji(e: String) { commitComposing(false); currentInputConnection?.commitText(e, 1) }
            override fun onBack() = showKeyboard()
            override fun onBackspace() = onDelete()
            override fun onSearch() = startSearch()
        })
        clip = ClipboardPanel(this, object : ClipboardPanel.Callback {
            override fun onPasteText(s: String) { commitComposing(false); currentInputConnection?.commitText(s, 1) }
            override fun onPasteImage(c: Clip) = pasteImage(c)
            override fun onBack() = showKeyboard()
        })
        content.addView(kb, FrameLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT))
        emoji.visibility = View.GONE; clip.visibility = View.GONE
        content.addView(emoji, FrameLayout.LayoutParams(mp, mp))
        content.addView(clip, FrameLayout.LayoutParams(mp, mp))
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

    private fun reloadAll() {
        if (!viewReady) return
        prefs.reload(); repo.invalidate()
        theme = Theme.from(prefs)
        root.setBackgroundColor(theme.bg)
        root.setPadding(0, 0, 0, dpi(prefs.int("bottomPad", 0).toFloat()))
        bar.applyTheme(theme); emoji.applyTheme(theme); clip.applyTheme(theme); strip.applyTheme(theme)
        kb.apply {
            theme = this@OpenKeysIME.theme
            heightScale = prefs.int("heightScale", 100); fontScale = prefs.int("fontScale", 100)
            radiusDp = prefs.int("keyRadius", 12); gapDp = prefs.int("keyGap", 4)
            showHints = prefs.bool("showHints", true); popupPreview = prefs.bool("popupPreview", true)
            swipeOn = prefs.bool("swipeTyping", true); hapticOn = prefs.bool("haptic", true)
            hapticMs = prefs.int("hapticMs", 12); soundOn = prefs.bool("sound", false)
            longPressMs = prefs.int("longPressMs", 350); numberRow = prefs.bool("numberRow", true)
            oneHanded = prefs.str("oneHanded", "off")
        }
        enabled = prefs.enabledLayouts().mapNotNull { repo.get(it) }.ifEmpty { listOfNotNull(repo.get("en_qwerty")) }
        if (idx >= enabled.size) idx = 0
        val l = forced?.let { repo.get(it.id) } ?: enabled.getOrNull(idx)
        if (l != null) setLayout(l)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0); lastCorrection = null; lastSwipe = null; lastWord = ""
        searching = false; query.setLength(0); strip.visibility = View.GONE; bar.setSearch(false, "")
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        noSuggest = cls != InputType.TYPE_CLASS_TEXT || (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_URI
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
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        commitComposing(false); suggest.flush()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        if (!viewReady) return
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
        kb.visibility = View.VISIBLE; emoji.visibility = View.GONE; clip.visibility = View.GONE
    }

    private fun showPanel(p: View) {
        val h = kb.height
        p.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        kb.visibility = View.INVISIBLE; emoji.visibility = View.GONE; clip.visibility = View.GONE
        p.visibility = View.VISIBLE
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
        if (prefs.bool("learnWords", true) && !noSuggest) dict?.learn(w, lastWord)
        lastWord = w
    }

    private fun commitComposing(auto: Boolean) {
        if (composing.isEmpty()) return
        var w = display()
        lastCorrection = null
        if (auto && !noSuggest) {
            val sc = prefs.shortcuts()[w.lowercase()]
            if (sc != null) w = sc
            else if (translit == null && prefs.bool("autocorrect", true) && prefs.bool("suggestions", true)) {
                dict?.correct(w)?.let { c -> val fixed = matchCase(w, c); lastCorrection = w to fixed; w = fixed }
            }
        }
        currentInputConnection?.commitText(w, 1)
        composing.setLength(0)
        learn(w)
    }

    private fun updateSuggestions() {
        if (!viewReady) return
        if (searching || noSuggest || !prefs.bool("suggestions", true)) { bar.setSuggestions(emptyList()); return }
        val d = dict
        if (composing.isNotEmpty()) {
            val typed = display()
            val comp = d?.complete(typed, 4) ?: emptyList()
            val fix = if (prefs.bool("autocorrect", true) && translit == null) d?.correct(typed)?.let { matchCase(typed, it) } else null
            val best = fix ?: comp.firstOrNull()
            val out = ArrayList<String>(); out.add(typed)
            if (best != null) out.add(best)
            comp.filter { it != best && it != typed }.take(3 - out.size).forEach { out.add(it) }
            bar.setSuggestions(out)
        } else {
            bar.setSuggestions(if (lastWord.isNotEmpty()) d?.next(lastWord, 3) ?: emptyList() else emptyList())
        }
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
        lastSwipe = null
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
            "mic" -> startVoice()
        }
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        lastSwipe = null
        val had = composing.isNotEmpty()
        commitComposing(true)
        val now = SystemClock.uptimeMillis()
        if (!had && !noSuggest && prefs.bool("doubleSpacePeriod", true) && now - lastSpace < 500) {
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

    override fun onSwipe(path: String, shifted: Boolean) {
        if (searching) return
        val ic = currentInputConnection ?: return
        commitComposing(true)
        val c = dict?.swipe(path, 3) ?: emptyList()
        if (c.isEmpty()) return
        val words = c.map { if (shifted) it.replaceFirstChar { ch -> ch.uppercase() } else it }
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        if (!before.isNullOrEmpty() && !before[0].isWhitespace() && !"([{\"'".contains(before[0])) ic.commitText(" ", 1)
        ic.commitText(words[0] + " ", 1)
        lastSwipe = words[0] + " "; lastWord = words[0]
        bar.setSuggestions(if (words.size < 2) words else listOf(words[1], words[0], words.getOrElse(2) { "" }))
    }

    override fun onLayerChange(name: String) { if (name == "base") updateAutoCaps() }

    // ------------------------------------------------------------ SuggestionBar.Listener
    override fun onPick(s: String) {
        val ic = currentInputConnection ?: return
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
            "emoji" -> onSpecial("emoji")
            "clip" -> onSpecial("clip")
            "mic" -> startVoice()
            "searchBack" -> exitSearch(true)
            "hand" -> {
                val next = when (prefs.str("oneHanded", "off")) { "off" -> "right"; "right" -> "left"; else -> "off" }
                prefs.save(JSONObject(prefs.raw()).put("oneHanded", next).toString())
            }
            "settings" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun startVoice() {
        val loc = layout?.locale ?: "en"
        val tag = when (loc) { "en" -> "en-US"; "bn" -> "bn-BD"; else -> loc }
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("lang", tag))
    }
}

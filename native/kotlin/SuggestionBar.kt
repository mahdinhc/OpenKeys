package com.openkeys.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Gboard-style top bar: [apps/back] [suggestions | tools | emoji-search field] [mic].
 * The apps button toggles the tool row even while suggestions are showing.
 */
class SuggestionBar(ctx: Context) : LinearLayout(ctx) {
    interface Listener { fun onPick(s: String); fun onTool(name: String) }

    var listener: Listener? = null
    private var theme = Theme.DARK
    private var suggestions: List<String> = emptyList()
    private var forceTools = false
    private var searching = false

    private val left = IconView(ctx, "keyboard", Theme.DARK.hint, 0.5f)
    private val micBg = FrameLayout(ctx)
    private val mic = IconView(ctx, "mic", Theme.DARK.keyText, 0.55f)
    private val center = FrameLayout(ctx)
    private val sugRow = LinearLayout(ctx)
    private val toolRow = LinearLayout(ctx)
    private val searchRow = LinearLayout(ctx)
    private val searchIcon = IconView(ctx, "search", Theme.DARK.hint, 0.6f)
    private val query = TextView(ctx)
    private val slots = ArrayList<TextView>()
    private val tools = ArrayList<IconView>()

    // NOTE: these MUST be declared before the init block below. Kotlin runs property initialisers and init blocks in
    // source order, and init calls setTools()/render(), which use them. Declared after init they were still null ->
    // NullPointerException in the constructor -> the keyboard crashed as soon as it launched.
    private val toolNames = ArrayList<String>()
    private val toolScroll = HorizontalScrollView(ctx)
    private val pluginViews = ArrayList<TextView>()
    private var pluginTools: List<Pair<String, String>> = emptyList()
    private var builtinTools: List<String> = listOf("emoji", "clip", "piano", "settings")
    private var mode = "abc"
    private var iconScale = 0.5f
    private val toolIcons = mapOf("emoji" to "emoji", "clip" to "clipboard", "piano" to "piano", "hand" to "one_hand", "settings" to "settings")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = ViewGroup.LayoutParams.MATCH_PARENT
        left.setOnClickListener {
            if (searching) listener?.onTool("searchBack")
            else if (mode != "abc") listener?.onTool("abc")
            else { forceTools = !forceTools; render() }
        }
        addView(left, LayoutParams(ctx.dpi(48f), h))

        for (i in 0 until 3) {
            val t = TextView(ctx).apply {
                gravity = Gravity.CENTER; textSize = 17f; maxLines = 1
                ellipsize = TextUtils.TruncateAt.END; isClickable = true
                setPadding(ctx.dpi(6f), 0, ctx.dpi(6f), 0)
                setOnClickListener { val s = text.toString(); if (s.isNotEmpty()) listener?.onPick(s) }
            }
            slots.add(t); sugRow.addView(t, LayoutParams(0, h, 1f))
        }
        setTools(listOf("emoji", "clip", "piano", "settings"))
        searchRow.gravity = Gravity.CENTER_VERTICAL
        query.textSize = 17f; query.maxLines = 1
        searchRow.addView(searchIcon, LayoutParams(ctx.dpi(36f), h))
        searchRow.addView(query, LayoutParams(0, h, 1f).also { })
        query.gravity = Gravity.CENTER_VERTICAL
        toolScroll.isHorizontalScrollBarEnabled = false
        toolScroll.overScrollMode = View.OVER_SCROLL_NEVER
        toolScroll.isFillViewport = true
        toolScroll.addView(toolRow, FrameLayout.LayoutParams(h, h))
        center.addView(toolScroll, FrameLayout.LayoutParams(h, h))
        center.addView(sugRow, FrameLayout.LayoutParams(h, h))
        center.addView(searchRow, FrameLayout.LayoutParams(h, h))
        addView(center, LayoutParams(0, h, 1f))

        micBg.addView(mic, FrameLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f), Gravity.CENTER))
        micBg.setOnClickListener { listener?.onTool("mic") }
        addView(micBg, LayoutParams(ctx.dpi(56f), h))
        render()
    }

    fun setTools(names: List<String>, scale: Float = 0.5f) {
        builtinTools = names; iconScale = scale
        rebuildTools()
    }

    /** [key, icon] pairs contributed by plugins; shown after the built-in tools. */
    fun setPluginTools(list: List<Pair<String, String>>) {
        if (list == pluginTools) return
        pluginTools = list
        rebuildTools()
    }

    private fun rebuildTools() {
        val h = ViewGroup.LayoutParams.MATCH_PARENT
        val w = context.dpi(48f)
        toolRow.removeAllViews(); tools.clear(); toolNames.clear(); pluginViews.clear()
        for (n in builtinTools) {
            val ic = toolIcons[n] ?: continue
            val v = IconView(context, ic, theme.hint, iconScale).also { it.setOnClickListener { listener?.onTool(n) } }
            tools.add(v); toolNames.add(n); toolRow.addView(v, LayoutParams(w, h, 1f))
        }
        for ((key, glyph) in pluginTools) {
            val v = TextView(context).apply {
                gravity = Gravity.CENTER; text = glyph; textSize = 40f * iconScale; maxLines = 1; isClickable = true
                setOnClickListener { listener?.onTool("js:" + key) }
            }
            pluginViews.add(v); toolRow.addView(v, LayoutParams(w, h, 1f))
        }
        tintTools()
    }

    private fun tintTools() {
        tools.forEachIndexed { i, v -> v.tint = if (toolNames[i] == mode) theme.accent else theme.hint; v.invalidate() }
        pluginViews.forEach { it.setTextColor(theme.hint) }
    }

    /** Which mode is active ("abc", "emoji", "clip", "piano"); its tool icon is drawn in the accent colour. */
    fun setMode(m: String) { mode = m; tintTools(); render() }

    fun setSuggestions(l: List<String>) { suggestions = l; if (l.isEmpty()) forceTools = false; render() }

    fun setSearch(active: Boolean, q: String) {
        searching = active
        query.text = if (q.isEmpty()) "Search emoji" else q
        query.setTextColor(if (q.isEmpty()) theme.hint else theme.keyText)
        render()
    }

    private fun render() {
        left.set(if (searching) "back" else "keyboard", if (!searching && mode == "abc") theme.accent else theme.hint)
        micBg.visibility = if (searching) GONE else VISIBLE
        searchRow.visibility = if (searching) VISIBLE else GONE
        val showSug = !searching && suggestions.isNotEmpty() && !forceTools && mode == "abc"
        sugRow.visibility = if (showSug) VISIBLE else GONE
        toolScroll.visibility = if (!searching && !showSug) VISIBLE else GONE
        if (showSug) for (i in 0 until 3) {
            slots[i].text = suggestions.getOrNull(i) ?: ""
            slots[i].setTypeface(null, if (i == 1 && suggestions.size > 1) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        slots.forEach { it.setTextColor(t.keyText) }
        tintTools()
        searchIcon.set("search", t.hint)
        mic.set("mic", t.keyText)
        micBg.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.key) }
        val pad = context.dpi(4f)
        micBg.setPadding(pad, pad, pad, pad)
        render()
    }
}

/** Horizontal strip of emoji search results shown above the keyboard while searching. */
class EmojiResultStrip(ctx: Context, private val onPick: (String) -> Unit) : android.widget.HorizontalScrollView(ctx) {
    private val row = LinearLayout(ctx)
    private var color = Theme.DARK.keyText
    init { isHorizontalScrollBarEnabled = false; addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    fun setResults(items: List<String>) {
        row.removeAllViews()
        scrollTo(0, 0)
        if (items.isEmpty()) {
            row.addView(TextView(context).apply { text = "No emoji found"; setTextColor(color); gravity = Gravity.CENTER_VERTICAL; setPadding(context.dpi(16f), 0, 0, 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        for (e in items) row.addView(TextView(context).apply {
            text = e; textSize = 28f; gravity = Gravity.CENTER; setPadding(context.dpi(10f), 0, context.dpi(10f), 0)
            setOnClickListener { onPick(e) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun applyTheme(t: Theme) { color = t.keyText; setBackgroundColor(t.bg) }
}

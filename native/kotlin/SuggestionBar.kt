package com.openkeys.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shows 3 suggestion slots, or a toolbar (emoji, clipboard, voice, one-hand, settings) when idle. */
class SuggestionBar(ctx: Context) : FrameLayout(ctx) {
    interface Listener { fun onPick(s: String); fun onTool(name: String) }

    var listener: Listener? = null
    private val sugRow = LinearLayout(ctx)
    private val toolRow = LinearLayout(ctx)
    private val slots = ArrayList<TextView>()
    private val tools = ArrayList<TextView>()
    private var theme = Theme.DARK

    init {
        for (i in 0 until 3) {
            val t = TextView(ctx).apply {
                gravity = Gravity.CENTER; textSize = 17f; maxLines = 1
                ellipsize = TextUtils.TruncateAt.END; isClickable = true
                setPadding(ctx.dpi(6f), 0, ctx.dpi(6f), 0)
                setOnClickListener { val s = text.toString(); if (s.isNotEmpty()) listener?.onPick(s) }
            }
            slots.add(t); sugRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        listOf("emoji" to "😊", "clip" to "📋", "mic" to "🎤", "hand" to "↔", "settings" to "⚙").forEach { (n, g) ->
            val t = TextView(ctx).apply {
                gravity = Gravity.CENTER; textSize = 19f; text = g; isClickable = true
                setOnClickListener { listener?.onTool(n) }
            }
            tools.add(t); toolRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        addView(toolRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(sugRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sugRow.visibility = GONE
    }

    fun setSuggestions(l: List<String>) {
        if (l.isEmpty()) { sugRow.visibility = GONE; toolRow.visibility = VISIBLE; return }
        toolRow.visibility = GONE; sugRow.visibility = VISIBLE
        for (i in 0 until 3) {
            slots[i].text = l.getOrNull(i) ?: ""
            slots[i].setTypeface(null, if (i == 1 && l.size > 1) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        slots.forEach { it.setTextColor(t.keyText) }
        tools.forEach { it.setTextColor(t.hint) }
    }
}

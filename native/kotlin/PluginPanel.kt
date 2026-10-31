package com.openkeys.keyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Declarative panel opened by keys.panel({title, columns, items}) from a plugin. Replaces the keyboard like the emoji panel. */
class PluginPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback {
        /** text != null -> insert it; otherwise the plugin's onTap(index) should run. */
        fun onItem(plugin: String, index: Int, text: String?)
        fun onBack()
    }

    private var theme = Theme.DARK
    private var plugin = ""
    private var keepOpen = false
    private var labels: List<String> = emptyList()
    private var texts: List<String?> = emptyList()
    private val title = TextView(ctx)
    private val grid = GridView(ctx)
    private val bottom = LinearLayout(ctx)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = labels.size
        override fun getItem(p: Int) = labels[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val t = (cv as? TextView) ?: TextView(context).apply {
                textSize = 15f; gravity = Gravity.CENTER; maxLines = 2
                setPadding(context.dpi(8f), context.dpi(10f), context.dpi(8f), context.dpi(10f))
            }
            t.text = labels[p]
            t.setTextColor(theme.keyText)
            t.background = GradientDrawable().apply { setColor(theme.key); cornerRadius = context.dpf(8f) }
            return t
        }
    }

    init {
        orientation = VERTICAL
        title.apply { textSize = 13f; setPadding(context.dpi(14f), context.dpi(6f), context.dpi(14f), context.dpi(2f)) }
        grid.apply {
            adapter = this@PluginPanel.adapter
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = context.dpi(6f); verticalSpacing = context.dpi(6f)
            setPadding(context.dpi(8f), context.dpi(4f), context.dpi(8f), context.dpi(4f))
            clipToPadding = false
            setOnItemClickListener { _, _, p, _ ->
                cb.onItem(plugin, p, texts.getOrNull(p))
                if (!keepOpen) cb.onBack()
            }
        }
        addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(44f)))
    }

    fun show(plugin: String, spec: JSONObject) {
        this.plugin = plugin
        keepOpen = spec.optBoolean("keepOpen", false)
        title.text = spec.optString("title", "")
        grid.numColumns = maxOf(1, spec.optInt("columns", 3))
        val items = spec.optJSONArray("items")
        val l = ArrayList<String>()
        val t = ArrayList<String?>()
        if (items != null) for (i in 0 until items.length()) {
            val o = items.optJSONObject(i)
            if (o == null) { l.add(items.optString(i)); t.add(items.optString(i)); continue }
            l.add(o.optString("label"))
            t.add(if (o.has("text")) o.optString("text") else null)
        }
        labels = l; texts = t
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }

    fun applyTheme(th: Theme) {
        theme = th
        setBackgroundColor(th.bg)
        title.setTextColor(th.hint)
        bottom.removeAllViews()
        bottom.addView(TextView(context).apply {
            text = "ABC"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(th.keyText); isClickable = true
            setOnClickListener { cb.onBack() }
        }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(View(context), LayoutParams(0, 1, 4f))
        adapter.notifyDataSetChanged()
    }
}

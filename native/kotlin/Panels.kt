package com.openkeys.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private fun bottomBtn(ctx: Context, label: String, color: Int, w: Float, onClick: () -> Unit) =
    TextView(ctx).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(color); isClickable = true
        setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w) }

class EmojiPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback { fun onEmoji(e: String); fun onBack(); fun onBackspace() }

    private var theme = Theme.DARK
    private val cats = linkedMapOf(
        "🕘" to "",
        "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😍 🥰 😘 😋 😛 😜 🤪 🤔 😐 😑 😶 🙄 😏 😴 😪 😷 🤒 🥵 🥶 😎 🤓 😢 😭 😤 😡 🤯 😱",
        "👍" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 👋 👏 🙌 🤝 🙏 💪 ✍️ 💅 👀 👅 👄 🧠",
        "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 💫 🔥 💯 ✅ ❌ ⚡ 🎉 🎊 🎁",
        "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐝 🦋 🐢 🐍 🐙 🐬 🐳 🌸 🌹 🌻 🌲 🍀",
        "🍔" to "🍎 🍌 🍉 🍇 🍓 🍒 🥭 🍍 🥥 🥑 🍅 🥕 🌽 🍞 🧀 🍗 🍔 🍟 🍕 🌭 🌮 🍜 🍣 🍚 🍰 🍩 🍪 🍫 ☕ 🍵 🥤 🍺",
        "🚗" to "🚗 🚕 🚌 🚑 🚒 🚲 🏍️ ✈️ 🚀 🚢 🚂 🏠 🏢 🏝️ ⛰️ 🌍 🌙 ☀️ ☁️ 🌧️ ⚽ 🏏 🏀 🎮 🎵 📱 💻 📷 💡 💰 📚"
    )
    private var items: List<String> = emptyList()
    private val grid = GridView(ctx)
    private val tabs = LinearLayout(ctx)
    private val tabViews = ArrayList<TextView>()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val t = (cv as? TextView) ?: TextView(context).apply {
                textSize = 24f; gravity = Gravity.CENTER; setPadding(0, context.dpi(8f), 0, context.dpi(8f))
            }
            t.text = items[p]; return t
        }
    }
    private val bottom = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        cats.keys.forEachIndexed { i, k ->
            val t = TextView(ctx).apply { text = k; textSize = 18f; gravity = Gravity.CENTER; setOnClickListener { select(i) } }
            tabViews.add(t); tabs.addView(t, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        grid.numColumns = 8; grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH; grid.adapter = adapter
        grid.setOnItemClickListener { _, _, p, _ -> cb.onEmoji(items[p]) }
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(40f)))
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(44f)))
    }

    private fun select(i: Int) {
        val key = cats.keys.elementAt(i)
        items = if (i == 0) ClipStore.recentEmoji(context).ifEmpty { cats.values.elementAt(1).split(" ").take(16) }
                else cats[key]!!.split(" ").filter { it.isNotEmpty() }
        adapter.notifyDataSetChanged(); grid.setSelection(0)
        tabViews.forEachIndexed { j, t -> t.alpha = if (j == i) 1f else 0.5f }
    }

    fun open() { select(0) }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.bg)
        tabViews.forEach { it.setTextColor(t.keyText) }
        bottom.removeAllViews()
        bottom.addView(bottomBtn(context, "ABC", t.keyText, 1f) { cb.onBack() })
        bottom.addView(View(context), LayoutParams(0, 1, 4f))
        bottom.addView(bottomBtn(context, "⌫", t.keyText, 1f) { cb.onBackspace() })
    }
}

class ClipboardPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback { fun onPaste(s: String); fun onBack() }

    private var theme = Theme.DARK
    private val listBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val scroll = ScrollView(ctx)
    private val bottom = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        scroll.addView(listBox)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(44f)))
    }

    fun refresh() {
        listBox.removeAllViews()
        val items = ClipStore.items(context)
        if (items.isEmpty()) listBox.addView(TextView(context).apply {
            text = "Copied text appears here.\nLong-press an item to pin it."; setTextColor(theme.hint)
            gravity = Gravity.CENTER; setPadding(0, context.dpi(30f), 0, 0)
        })
        for ((clipText, pinned) in items) {
            val tv = TextView(context).apply {
                this.text = (if (pinned) "📌 " else "") + clipText
                maxLines = 3; setTextColor(theme.keyText); textSize = 15f
                setBackgroundColor(theme.key)
                setPadding(context.dpi(12f), context.dpi(10f), context.dpi(12f), context.dpi(10f))
                setOnClickListener { cb.onPaste(clipText) }
                setOnLongClickListener { ClipStore.toggle(context, clipText); refresh(); true }
            }
            val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(context.dpi(8f), context.dpi(4f), context.dpi(8f), 0)
            listBox.addView(tv, lp)
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.bg)
        bottom.removeAllViews()
        bottom.addView(bottomBtn(context, "ABC", t.keyText, 1f) { cb.onBack() })
        bottom.addView(View(context), LayoutParams(0, 1, 4f))
        bottom.addView(bottomBtn(context, "Clear", t.keyText, 1f) { ClipStore.clear(context); refresh() })
    }
}

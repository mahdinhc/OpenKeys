package com.openkeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

/** Clipboard filter tabs. */
enum class ClipFilter(val label: String) { ALL("All"), PINNED("Pinned"), TEXT("Text"), IMAGES("Images") }

/**
 * Clipboard panel, split into small modules:
 *  - [ClipboardPanel]: header, filter tabs, scrolling list, footer actions (state + wiring)
 *  - [ClipCards]: builds one card per clip (text / image), no state
 *  - [ClipStore]: persistence (limits, corrupt-entry tolerance)
 * Every rebuild is wrapped so a bad item can never take the keyboard down.
 */
class ClipboardPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback { fun onPasteText(s: String); fun onPasteImage(c: Clip); fun onBack() }

    private var theme = Theme.DARK
    private var filter = ClipFilter.ALL
    private var confirmClear = false

    private val title = TextView(ctx)
    private val count = TextView(ctx)
    private val backIcon = IconView(ctx, "back", theme.keyText, 0.5f)
    private val tabs = LinearLayout(ctx)
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val clear = TextView(ctx)
    private val cards = ClipCards(ctx)

    init {
        orientation = VERTICAL
        // header
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backIcon.setOnClickListener { cb.onBack() }
        head.addView(backIcon, LayoutParams(ctx.dpi(40f), ctx.dpi(40f)))
        title.text = "Clipboard"; title.textSize = 17f; title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        head.addView(title, LayoutParams(0, WRAP, 1f))
        count.textSize = 12f; count.setPadding(0, 0, ctx.dpi(12f), 0)
        head.addView(count, LayoutParams(WRAP, WRAP))
        addView(head, LayoutParams(MATCH, ctx.dpi(40f)))
        // filter tabs
        tabs.orientation = HORIZONTAL; tabs.setPadding(ctx.dpi(8f), 0, ctx.dpi(8f), ctx.dpi(4f))
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LayoutParams(MATCH, ctx.dpi(38f)))
        // list
        addView(ScrollView(ctx).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) }, LayoutParams(MATCH, 0, 1f))
        // footer
        clear.textSize = 14f; clear.gravity = Gravity.CENTER
        clear.setOnClickListener {
            if (!confirmClear) { confirmClear = true; styleFooter(); postDelayed({ confirmClear = false; styleFooter() }, 3000) }
            else { confirmClear = false; safe { ClipStore.clearUnpinned(context) }; refresh() }
        }
        addView(clear, LayoutParams(MATCH, ctx.dpi(40f)))
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (e: Throwable) { DevLog.d(context, "CLIP", "error: " + e) } }

    private fun styleFooter() {
        clear.text = if (confirmClear) "Tap again to delete all unpinned" else "Clear unpinned"
        clear.setTextColor(if (confirmClear) theme.accentText else theme.hint)
        clear.background = if (confirmClear) rounded(theme.accent, context.dpf(10f)) else null
    }

    private fun buildTabs(items: List<Clip>) {
        tabs.removeAllViews()
        for (f in ClipFilter.values()) {
            val n = when (f) {
                ClipFilter.ALL -> items.size; ClipFilter.PINNED -> items.count { it.pinned }
                ClipFilter.TEXT -> items.count { it.type == "text" }; ClipFilter.IMAGES -> items.count { it.type == "image" }
            }
            val on = f == filter
            val tv = TextView(context).apply {
                text = if (n > 0 && f != ClipFilter.ALL) "${f.label} $n" else f.label
                textSize = 13f; gravity = Gravity.CENTER
                setPadding(context.dpi(14f), 0, context.dpi(14f), 0)
                setTextColor(if (on) theme.accentText else theme.keyText)
                background = rounded(if (on) theme.accent else theme.key, context.dpf(16f))
                setOnClickListener { filter = f; refresh() }
            }
            tabs.addView(tv, LayoutParams(WRAP, MATCH).also { it.setMargins(0, 0, context.dpi(6f), 0) })
        }
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text.uppercase(); textSize = 11f; setTextColor(theme.hint); letterSpacing = 0.08f
        setPadding(context.dpi(14f), context.dpi(10f), 0, context.dpi(2f))
    }

    private fun empty(msg: String) = TextView(context).apply {
        text = msg; setTextColor(theme.hint); gravity = Gravity.CENTER; setPadding(context.dpi(20f), context.dpi(36f), context.dpi(20f), 0)
    }

    fun refresh() {
        try {
            list.removeAllViews(); styleFooter()
            val all = ClipStore.items(context)
            count.text = if (all.isEmpty()) "" else "${all.size} items"
            buildTabs(all)
            if (all.isEmpty()) { list.addView(empty("Copied text and images appear here.\nLong-press an item to pin it.")); return }
            val shown = when (filter) {
                ClipFilter.ALL -> all; ClipFilter.PINNED -> all.filter { it.pinned }
                ClipFilter.TEXT -> all.filter { it.type == "text" }; ClipFilter.IMAGES -> all.filter { it.type == "image" }
            }
            if (shown.isEmpty()) { list.addView(empty("Nothing here yet.")); return }
            if (filter == ClipFilter.ALL) {
                val pinned = shown.filter { it.pinned }; val rest = shown.filter { !it.pinned }
                if (pinned.isNotEmpty()) { list.addView(sectionLabel("Pinned")); addGroup(pinned) }
                if (rest.isNotEmpty()) { if (pinned.isNotEmpty()) list.addView(sectionLabel("Recent")); addGroup(rest) }
            } else addGroup(shown)
        } catch (e: Throwable) {
            DevLog.d(context, "CLIP", "refresh failed: $e")
            list.removeAllViews(); list.addView(empty("Clipboard could not be shown.\nTap Clear unpinned to reset it."))
        }
    }

    private fun addGroup(items: List<Clip>) {
        val m = context.dpi(6f)
        val texts = items.filter { it.type == "text" }
        val imgs = items.filter { it.type == "image" }
        for (c in texts) safe {
            list.addView(cards.text(c, theme, ::paste, ::pin, ::delete), LayoutParams(MATCH, WRAP).also { it.setMargins(m, m / 2, m, m / 2) })
        }
        imgs.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (k in 0 until 2) {
                val c = pair.getOrNull(k)
                if (c == null) { row.addView(View(context), LayoutParams(0, 1, 1f)); continue }
                safe { row.addView(cards.image(c, theme, ::paste, ::pin, ::delete), LayoutParams(0, context.dpi(120f), 1f).also { it.setMargins(m, m / 2, m, m / 2) }) }
            }
            list.addView(row, LayoutParams(MATCH, WRAP))
        }
    }

    private fun paste(c: Clip) = safe { if (c.type == "image") cb.onPasteImage(c) else cb.onPasteText(readFull(c)) }
    private fun pin(c: Clip) { safe { ClipStore.togglePin(context, c.id) }; refresh() }
    private fun delete(c: Clip) { safe { ClipStore.remove(context, c.id) }; refresh() }
    private fun readFull(c: Clip) = ClipStore.fullText(context, c)

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.bg)
        title.setTextColor(t.keyText); count.setTextColor(t.hint); backIcon.tint = t.keyText; backIcon.invalidate()
        cards.clearThumbs()
        styleFooter()
    }
}

/** Builds clip cards. Stateless apart from the thumbnail cache. */
class ClipCards(private val ctx: Context) {
    private val thumbs = object : LruCache<String, Bitmap>(24) {}
    fun clearThumbs() = thumbs.evictAll()

    private fun thumb(c: Clip): Bitmap? {
        thumbs.get(c.id)?.let { return it }
        return try {
            val f = ClipStore.imageFile(ctx, c.value)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) return null
            var s = 1
            while (o.outWidth / s > 600 || o.outHeight / s > 600) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.also { thumbs.put(c.id, it) }
        } catch (e: Throwable) { null }   // includes OutOfMemoryError
    }

    private fun ago(ts: Long): String {
        if (ts <= 0) return ""
        val d = (System.currentTimeMillis() - ts) / 1000
        return when {
            d < 60 -> "just now"; d < 3600 -> "${d / 60} min ago"; d < 86400 -> "${d / 3600} h ago"
            d < 7 * 86400 -> "${d / 86400} d ago"; else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
        }
    }

    private fun actions(c: Clip, t: Theme, onPin: (Clip) -> Unit, onDel: (Clip) -> Unit): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pin = IconView(ctx, "pin", if (c.pinned) t.accent else t.hint, 0.5f).apply { setOnClickListener { onPin(c) } }
        val del = IconView(ctx, "close", t.hint, 0.5f).apply { setOnClickListener { onDel(c) } }
        col.addView(pin, LinearLayout.LayoutParams(ctx.dpi(34f), ctx.dpi(34f)))
        col.addView(del, LinearLayout.LayoutParams(ctx.dpi(34f), ctx.dpi(34f)))
        return col
    }

    fun text(c: Clip, t: Theme, onPaste: (Clip) -> Unit, onPin: (Clip) -> Unit, onDel: (Clip) -> Unit): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = rounded(t.key, ctx.dpf(12f)) }
        root.setPadding(ctx.dpi(12f), ctx.dpi(8f), ctx.dpi(4f), ctx.dpi(8f))
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val meta = TextView(ctx).apply {
            text = (if (c.pinned) "Pinned · " else "") + ago(c.ts) + " · " + c.length + " chars" + (if (c.isLarge) " · large" else "")
            textSize = 11f; setTextColor(if (c.pinned) t.accent else t.hint)
        }
        top.addView(meta, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(actions(c, t, onPin, onDel))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        // only a short preview is ever laid out, so huge copies stay cheap
        val preview = if (c.value.length > 400) c.value.substring(0, 400) + "…" else c.value
        root.addView(TextView(ctx).apply {
            text = preview; maxLines = 4; ellipsize = TextUtils.TruncateAt.END; textSize = 15f; setTextColor(t.keyText)
            setPadding(0, 0, ctx.dpi(8f), 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        root.setOnClickListener { onPaste(c) }
        root.setOnLongClickListener { onPin(c); true }
        return root
    }

    fun image(c: Clip, t: Theme, onPaste: (Clip) -> Unit, onPin: (Clip) -> Unit, onDel: (Clip) -> Unit): View {
        val f = FrameLayout(ctx).apply { background = rounded(t.key, ctx.dpf(12f)) }
        val bmp = thumb(c)
        if (bmp == null) { try { ClipStore.remove(ctx, c.id) } catch (e: Throwable) { } }   // unreadable image: drop it so it never breaks the panel again
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bmp); setPadding(ctx.dpi(6f), ctx.dpi(6f), ctx.dpi(6f), ctx.dpi(6f)) }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        f.addView(actions(c, t, onPin, onDel), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        f.setOnClickListener { onPaste(c) }
        f.setOnLongClickListener { onPin(c); true }
        return f
    }
}

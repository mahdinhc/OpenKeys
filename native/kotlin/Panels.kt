package com.openkeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun round(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

/** Tap-and-hold-to-repeat helper for backspace buttons in the panels. */
private fun View.onRepeatTouch(action: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    var down = false
    val rep = object : Runnable { override fun run() { if (down) { action(); h.postDelayed(this, 50) } } }
    setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; action(); h.postDelayed(rep, 400) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; h.removeCallbacks(rep) }
        }
        true
    }
}

private val KAOMOJI = listOf(
    EmojiSection("Emoticons", listOf(":-)", ":-D", ";-)", ":-P", ":-(", ":'(", ":-O", ":-/", ":-|", "8-)", "B-)", ">:(", "<3", "</3", "XD", "^_^", "-_-", "T_T", "o_O", "O_o", ":3", ":*", "(:", "=)", ":)", ":D", ";)", ":(", ":P", "xD")),
    EmojiSection("Kaomoji", listOf("¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "┬─┬ ノ( ゜-゜ノ)", "(づ｡◕‿‿◕｡)づ", "ʕ•ᴥ•ʔ", "(ಥ﹏ಥ)", "(☞ﾟヮﾟ)☞", "(≧▽≦)", "(^_^)", "(¬_¬)", "(•_•)", "( ͡° ͜ʖ ͡°)", "ヽ(´▽`)/", "(*^▽^*)", "orz", "(・ω・)", "(｡•́︿•̀｡)", "٩(◕‿◕)۶", "(ノಠ益ಠ)ノ", "ಠ_ಠ"))
)

class EmojiPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback { fun onEmoji(e: String); fun onBack(); fun onBackspace(); fun onSearch(); fun onSticker(f: java.io.File) }

    private var theme = Theme.DARK
    private val grid = EmojiGridView(ctx)
    private val topRow = LinearLayout(ctx)
    private val tabs = LinearLayout(ctx)
    private val tabViews = ArrayList<IconView>()
    private val searchPill = TextView(ctx)
    private val bottom = LinearLayout(ctx)
    private val emojiBtn = FrameLayout(ctx)
    private val kaoBtn = TextView(ctx)
    private val kitchenBtn = FrameLayout(ctx)
    private val kitchenBar = LinearLayout(ctx)
    private val slotA = TextView(ctx)
    private val slotB = TextView(ctx)
    private val mixImg = ImageView(ctx)
    private val mixMsg = TextView(ctx)
    private val mixBox = FrameLayout(ctx)
    private val kHint = TextView(ctx)
    private var kA: String? = null
    private var kB: String? = null
    private var kSel = 0
    private var mixSeq = 0
    private var mixFile: java.io.File? = null
    private val back = FrameLayout(ctx)
    private val backIcon = IconView(ctx, "back", Theme.DARK.keyText, 0.5f)
    private var sectionIndexForTab = ArrayList<Int>()
    private var kaomoji = false
    private var kitchen = false

    init {
        orientation = VERTICAL
        // top row: back, then a horizontally scrolling [search pill][category tabs]
        topRow.orientation = HORIZONTAL; topRow.gravity = Gravity.CENTER_VERTICAL
        back.addView(backIcon, FrameLayout.LayoutParams(MATCH, MATCH))
        back.setOnClickListener { cb.onBack() }
        topRow.addView(back, LayoutParams(ctx.dpi(46f), ctx.dpi(40f)).also { it.setMargins(ctx.dpi(6f), 0, ctx.dpi(4f), 0) })
        searchPill.text = "Search"; searchPill.textSize = 16f; searchPill.gravity = Gravity.CENTER_VERTICAL
        searchPill.setPadding(ctx.dpi(44f), 0, ctx.dpi(16f), 0)
        searchPill.setOnClickListener { cb.onSearch() }
        val searchIc = IconView(ctx, "search", Theme.DARK.hint, 0.6f)
        val pillBox = FrameLayout(ctx)
        pillBox.addView(searchPill, FrameLayout.LayoutParams(MATCH, MATCH))
        pillBox.addView(searchIc, FrameLayout.LayoutParams(ctx.dpi(40f), MATCH))
        pillBox.tag = searchIc
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val inner = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inner.addView(pillBox, LayoutParams(ctx.dpi(150f), ctx.dpi(40f)).also { it.setMargins(0, 0, ctx.dpi(6f), 0) })
        inner.addView(tabs, LayoutParams(WRAP, MATCH))
        hs.addView(inner, ViewGroup.LayoutParams(WRAP, MATCH))
        topRow.addView(hs, LayoutParams(0, MATCH, 1f))
        tabs.orientation = HORIZONTAL; tabs.gravity = Gravity.CENTER_VERTICAL

        grid.onPick = { e -> if (kitchen) kitchenPick(e) else { cb.onEmoji(e); if (!kaomoji) ClipStore.addEmoji(context, e) } }
        grid.toneBase = { !kitchen && EmojiData.isToneBase(it) }
        grid.displayOf = { e -> if (!kitchen && EmojiData.isToneBase(e)) (ClipStore.tone(context, e) ?: e) else e }
        grid.onTone = { base, v -> ClipStore.setTone(context, base, v) }
        grid.onSection = { sec -> highlight(sec) }

        emojiBtn.addView(IconView(ctx, "emoji", Theme.DARK.keyText, 0.55f), FrameLayout.LayoutParams(MATCH, MATCH))
        emojiBtn.setOnClickListener { setKaomoji(false) }
        kaoBtn.text = ":-)"; kaoBtn.textSize = 17f; kaoBtn.gravity = Gravity.CENTER; kaoBtn.setOnClickListener { setKaomoji(true) }
        kitchenBtn.addView(IconView(ctx, "kitchen", Theme.DARK.keyText, 0.55f), FrameLayout.LayoutParams(MATCH, MATCH))
        kitchenBtn.setOnClickListener { setKitchen(true) }
        buildKitchenBar(ctx)
        val del = FrameLayout(ctx); del.addView(IconView(ctx, "backspace", Theme.DARK.keyText, 0.5f), FrameLayout.LayoutParams(MATCH, MATCH))
        del.onRepeatTouch { cb.onBackspace() }
        bottom.orientation = HORIZONTAL; bottom.gravity = Gravity.CENTER_VERTICAL
        val m = ctx.dpi(4f)
        bottom.addView(View(ctx), LayoutParams(0, 1, 0.4f))
        bottom.addView(emojiBtn, LayoutParams(0, MATCH, 1.4f).also { it.setMargins(m, m, m, m) })
        bottom.addView(kaoBtn, LayoutParams(0, MATCH, 1.4f).also { it.setMargins(m, m, m, m) })
        bottom.addView(kitchenBtn, LayoutParams(0, MATCH, 1.4f).also { it.setMargins(m, m, m, m) })
        bottom.addView(del, LayoutParams(0, MATCH, 1f))

        addView(topRow, LayoutParams(MATCH, ctx.dpi(48f)))
        addView(kitchenBar, LayoutParams(MATCH, ctx.dpi(72f)))
        addView(grid, LayoutParams(MATCH, 0, 1f))
        addView(bottom, LayoutParams(MATCH, ctx.dpi(48f)))
    }

    private fun setKaomoji(on: Boolean) {
        kaomoji = on; kitchen = false
        topRow.visibility = if (on) GONE else VISIBLE
        kitchenBar.visibility = GONE
        restyleBottom()
        if (on) grid.setData(KAOMOJI, 3, 0f, context.dpf(46f)) else open()
    }

    private fun restyleBottom() {
        val sel = round(theme.accent and 0x00FFFFFF or 0x40000000, context.dpf(20f))
        emojiBtn.background = if (!kaomoji && !kitchen) sel else null
        kaoBtn.background = if (kaomoji) sel else null
        kitchenBtn.background = if (kitchen) sel else null
    }

    // ---------------------------------------------------------------- Emoji Kitchen
    private fun buildKitchenBar(ctx: Context) {
        kitchenBar.orientation = HORIZONTAL; kitchenBar.gravity = Gravity.CENTER; kitchenBar.visibility = GONE
        for ((i, t) in listOf(slotA, slotB).withIndex()) {
            t.gravity = Gravity.CENTER; t.textSize = 26f
            t.setOnClickListener { kSel = i; if (i == 0) kA = null else kB = null; mixUpdate() }
        }
        fun sign(c: String) = TextView(ctx).apply { text = c; textSize = 22f; gravity = Gravity.CENTER; tag = "sign" }
        mixImg.scaleType = ImageView.ScaleType.FIT_CENTER
        mixMsg.gravity = Gravity.CENTER; mixMsg.textSize = 13f
        mixBox.addView(mixImg, FrameLayout.LayoutParams(MATCH, MATCH).also { it.setMargins(6, 6, 6, 6) })
        mixBox.addView(mixMsg, FrameLayout.LayoutParams(MATCH, MATCH))
        mixBox.setOnClickListener { mixFile?.let { f -> cb.onSticker(f) } }
        val sz = ctx.dpi(52f); val gap = ctx.dpi(5f)
        kitchenBar.addView(slotA, LayoutParams(sz, sz))
        kitchenBar.addView(sign("+"), LayoutParams(ctx.dpi(28f), sz))
        kitchenBar.addView(slotB, LayoutParams(sz, sz))
        kitchenBar.addView(sign("="), LayoutParams(ctx.dpi(28f), sz))
        kitchenBar.addView(mixBox, LayoutParams(sz + gap * 2, sz + gap * 2))
        kHint.textSize = 12f; kHint.gravity = Gravity.CENTER_VERTICAL; kHint.maxLines = 3
        kHint.setPadding(ctx.dpi(10f), 0, ctx.dpi(6f), 0)
        kitchenBar.addView(kHint, LayoutParams(0, MATCH, 1f))
    }

    private fun setKitchen(on: Boolean) {
        kitchen = on; kaomoji = false; gridFilterFor = null; if (on) { kA = null; kB = null; kSel = 0 }
        topRow.visibility = if (on) GONE else VISIBLE
        kitchenBar.visibility = if (on) VISIBLE else GONE
        restyleBottom()
        if (!on) { open(); return }
        EmojiKitchen.ensureIndex(context)                      // (re)download the pair list in the background when missing/old
        EmojiData.load(context) { kitchenGrid(null); mixUpdate() }
    }

    private var gridFilterFor: String? = null

    /** Kitchen picker grid; with [only] (tokens from the pair index) just the emoji that mix with the first pick. */
    private fun kitchenGrid(only: Set<String>?) {
        val pr = Prefs(context)
        val heads = pr.bool("emojiHeaders", true)
        val secs = ArrayList<EmojiSection>()
        for (g in EmojiData.GROUPS.indices) {
            if (g == 8) continue                           // flags have no mixes
            var l = EmojiData.items.filter { it.group == g }.map { it.s }
            if (only != null) l = l.filter { EmojiKitchen.partnerToken(it) in only }
            if (l.isNotEmpty()) secs.add(EmojiSection(if (heads) EmojiData.GROUPS[g] else "", l))
        }
        grid.setData(secs, pr.int("emojiCols", 8).coerceIn(4, 12), (0.62f * pr.int("emojiSize", 100) / 100f).coerceIn(0.3f, 0.95f))
    }

    /** Exactly one slot filled -> narrow the grid to its partners (needs the index); otherwise show everything. */
    private fun refreshKitchenGrid() {
        val one = if ((kA == null) != (kB == null)) (kA ?: kB) else null
        if (one == gridFilterFor) return
        gridFilterFor = one
        if (one == null) { kitchenGrid(null); return }
        Thread {
            val set = EmojiKitchen.partners(context, one)
            post {
                if (gridFilterFor != one || !kitchen) return@post
                if (set == null || set.isEmpty()) { kitchenGrid(null); return@post }
                kitchenGrid(set)
                if (Prefs(context).bool("kitchenPrefetch", false))
                    EmojiKitchen.prefetch(context, one, EmojiData.items.filter { it.group != 8 && EmojiKitchen.partnerToken(it.s) in set }.map { it.s })
            }
        }.start()
    }

    private fun kitchenPick(e: String) {
        if (kSel == 0) kA = e else kB = e
        kSel = if (kA == null) 0 else 1
        mixUpdate()
    }

    /** The slot that receives the next pick is tinted with the accent colour. */
    private fun styleSlots() {
        val on = round(theme.accent and 0x00FFFFFF or 0x40000000, context.dpf(14f))
        val off = round(theme.key, context.dpf(14f))
        slotA.background = if (kSel == 0) on else off
        slotB.background = if (kSel == 1) on else off
    }

    private fun mixUpdate() {
        val ef = EmojiFont.get(context); slotA.typeface = ef ?: android.graphics.Typeface.DEFAULT; slotB.typeface = slotA.typeface
        slotA.text = kA ?: ""; slotB.text = kB ?: ""
        styleSlots()
        refreshKitchenGrid()
        mixSeq++; val seq = mixSeq
        mixFile = null; mixImg.setImageDrawable(null)
        val a = kA; val b = kB
        if (a == null || b == null) { mixMsg.text = ""; kHint.text = "Tap two emoji to mix them"; return }
        mixMsg.text = "…"; kHint.text = "Mixing…"
        EmojiKitchen.mix(context, a, b) { r ->
            if (seq != mixSeq) return@mix                   // user already picked something else
            when (r) {
                is EmojiKitchen.Result.Ok -> {
                    val bmp = decode(r.file)
                    if (bmp == null) { mixMsg.text = "Error"; kHint.text = "Couldn't read the image"; return@mix }
                    mixFile = r.file; mixImg.setImageBitmap(bmp); mixMsg.text = ""
                    kHint.text = "Tap the mix to send it. Tap a slot to change that emoji"
                }
                EmojiKitchen.Result.NoMix -> { mixMsg.text = "No mix"; kHint.text = "These two don't mix. Try another" }
                EmojiKitchen.Result.Offline -> { mixMsg.text = "Offline"; kHint.text = "Can't reach the server and this mix isn't saved on the phone yet. Mixes you've opened before work offline" }
            }
        }
    }

    private fun decode(f: java.io.File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > 320 || o.outHeight / s > 320) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { null }

    /** (Re)builds the section list: recents + the nine Unicode groups. */
    fun open() {
        kaomoji = false; kitchen = false; topRow.visibility = VISIBLE; kitchenBar.visibility = GONE; restyleBottom()
        EmojiData.load(context) { build() }
    }

    private fun build() {
        val secs = ArrayList<EmojiSection>()
        val tabIcons = ArrayList<String>()
        sectionIndexForTab.clear()
        val recents = ClipStore.recentEmoji(context)
        val pr = Prefs(context)
        val hidden = jsonStrings(pr.cfg.optJSONArray("emojiHidden")).toSet()
        val heads = pr.bool("emojiHeaders", true)
        secs.add(EmojiSection(if (heads) "Recent Emoji" else "", if (pr.bool("emojiRecents", true)) recents.ifEmpty { EmojiData.items.take(0).map { it.s } } else emptyList()))
        tabIcons.add("history"); sectionIndexForTab.add(0)
        for (g in EmojiData.GROUPS.indices) {
            val list = EmojiData.items.filter { it.group == g }.map { it.s }
            if (list.isEmpty() || hidden.contains(g.toString())) continue
            sectionIndexForTab.add(secs.size); tabIcons.add(EmojiData.GROUP_ICONS[g])
            secs.add(EmojiSection(if (heads) EmojiData.GROUPS[g] else "", list))
        }
        // drop an empty recents section
        val finalSecs = if (secs[0].items.isEmpty()) { sectionIndexForTab.removeAt(0); tabIcons.removeAt(0); secs.subList(1, secs.size).also { l -> for (i in sectionIndexForTab.indices) sectionIndexForTab[i] = sectionIndexForTab[i] - 1 } } else secs
        grid.setData(finalSecs, pr.int("emojiCols", 8).coerceIn(4, 12), (0.62f * pr.int("emojiSize", 100) / 100f).coerceIn(0.3f, 0.95f))
        tabs.removeAllViews(); tabViews.clear()
        tabIcons.forEachIndexed { i, ic ->
            val v = IconView(context, ic, theme.hint, 0.55f)
            v.setOnClickListener { grid.scrollToSection(sectionIndexForTab[i]) }
            tabViews.add(v)
            tabs.addView(v, LayoutParams(context.dpi(46f), context.dpi(40f)).also { it.setMargins(context.dpi(2f), 0, context.dpi(2f), 0) })
        }
        highlight(0)
    }

    private fun highlight(sec: Int) {
        val ti = sectionIndexForTab.indexOfLast { it <= sec }.coerceAtLeast(0)
        tabViews.forEachIndexed { i, v ->
            v.background = if (i == ti) round(theme.accent and 0x00FFFFFF or 0x40000000, context.dpf(20f)) else null
            v.tint = if (i == ti) theme.accent else theme.hint; v.invalidate()
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.bg)
        grid.theme = t
        backIcon.set("back", t.keyText)
        back.background = round(t.key, context.dpf(20f))
        searchPill.background = round(t.key, context.dpf(20f)); searchPill.setTextColor(t.hint)
        ((searchPill.parent as? FrameLayout)?.tag as? IconView)?.set("search", t.hint)
        kaoBtn.setTextColor(t.keyText)
        (emojiBtn.getChildAt(0) as IconView).set("emoji", t.keyText)
        (kitchenBtn.getChildAt(0) as IconView).set("kitchen", t.keyText)
        slotA.setTextColor(t.keyText); slotB.setTextColor(t.keyText); mixMsg.setTextColor(t.hint); kHint.setTextColor(t.hint)
        mixBox.background = round(t.key, context.dpf(16f))
        for (i in 0 until kitchenBar.childCount) (kitchenBar.getChildAt(i) as? TextView)?.takeIf { it.tag == "sign" }?.setTextColor(t.hint)
        kitchenBar.setBackgroundColor(Color.TRANSPARENT)
        restyleBottom(); styleSlots()
        grid.invalidate()
    }
}

class ClipboardPanel(ctx: Context, private val cb: Callback) : LinearLayout(ctx) {
    interface Callback { fun onPasteText(s: String); fun onPasteImage(c: Clip); fun onBack() }

    private var theme = Theme.DARK
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val bottom = LinearLayout(ctx)
    private val clear = TextView(ctx)
    private val thumbs = object : LruCache<String, Bitmap>(24) {}

    init {
        orientation = VERTICAL
        val sv = ScrollView(ctx).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) }
        addView(sv, LayoutParams(MATCH, 0, 1f))
        clear.text = "Clear"; clear.textSize = 16f; clear.gravity = Gravity.CENTER
        clear.setOnClickListener { ClipStore.clearUnpinned(context); refresh() }
        bottom.orientation = HORIZONTAL
        bottom.addView(View(ctx), LayoutParams(0, 1, 5f))
        bottom.addView(clear, LayoutParams(0, MATCH, 1f))
        addView(bottom, LayoutParams(MATCH, ctx.dpi(48f)))
    }

    private fun thumb(c: Clip): Bitmap? {
        thumbs.get(c.id)?.let { return it }
        return try {
            val f = ClipStore.imageFile(context, c.value)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / s > 600 || o.outHeight / s > 600) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.also { thumbs.put(c.id, it) }
        } catch (e: Exception) { null }
    }

    private fun card(content: View, c: Clip): FrameLayout {
        val f = FrameLayout(context)
        f.background = round(theme.key, context.dpf(12f))
        f.addView(content, FrameLayout.LayoutParams(MATCH, if (c.type == "image") MATCH else WRAP))
        val del = IconView(context, "close", theme.hint, 0.5f)
        del.setOnClickListener { ClipStore.remove(context, c.id); refresh() }
        f.addView(del, FrameLayout.LayoutParams(context.dpi(34f), context.dpi(34f), Gravity.TOP or Gravity.END))
        if (c.pinned) f.addView(IconView(context, "pin", theme.accent, 0.5f), FrameLayout.LayoutParams(context.dpi(30f), context.dpi(30f), Gravity.BOTTOM or Gravity.END))
        f.setOnClickListener { if (c.type == "image") cb.onPasteImage(c) else cb.onPasteText(c.value) }
        f.setOnLongClickListener { ClipStore.togglePin(context, c.id); refresh(); true }
        return f
    }

    fun refresh() {
        list.removeAllViews()
        val items = ClipStore.items(context)
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Copied text and images appear here.\nLong-press an item to pin it."
                setTextColor(theme.hint); gravity = Gravity.CENTER; setPadding(0, context.dpi(40f), 0, 0)
            }, LayoutParams(MATCH, WRAP))
            return
        }
        val m = context.dpi(6f)
        val texts = items.filter { it.type == "text" }
        val imgs = items.filter { it.type == "image" }
        for (c in texts) {
            val tv = TextView(context).apply {
                text = c.value; maxLines = 3; setTextColor(theme.keyText); textSize = 15f
                setPadding(context.dpi(14f), context.dpi(12f), context.dpi(40f), context.dpi(12f))
            }
            list.addView(card(tv, c), LayoutParams(MATCH, WRAP).also { it.setMargins(m, m, m, 0) })
        }
        imgs.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (k in 0 until 2) {
                val c = pair.getOrNull(k)
                if (c == null) { row.addView(View(context), LayoutParams(0, 1, 1f)); continue }
                val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(thumb(c)); setPadding(m, m, m, m) }
                row.addView(card(iv, c).also { it.layoutParams = null }, LayoutParams(0, context.dpi(120f), 1f).also { it.setMargins(m, m, m, 0) })
            }
            list.addView(row, LayoutParams(MATCH, WRAP))
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.bg)
        clear.setTextColor(t.keyText)
    }
}

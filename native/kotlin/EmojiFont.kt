package com.openkeys.keyboard

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * Emoji font used to DRAW emoji inside the keyboard (emoji panel, Emoji Kitchen slots).
 *   system -> whatever the phone ships (newest emoji may be missing and are then hidden)
 *   noto   -> bundled Noto Color Emoji (bitmap, Unicode 18, works on every Android version)
 *   colr   -> bundled Noto COLRv1 (vector, smaller & sharper at any size, Android 14+; falls back to "noto")
 *   openemoji -> bundled OpenEmoji (Noto-based bitmap font + the custom Private Use Area emoji, any Android version);
 *                while it is active the emoji panel also gets an "OpenEmoji" section (see EmojiData)
 * Other apps still draw what you send with their own font.
 */
object EmojiFont {
    private val cache = HashMap<String, Typeface?>()

    fun mode(ctx: Context): String {
        val m = Prefs(ctx).str("emojiFont", "system")
        return when {
            m == "colr" && Build.VERSION.SDK_INT < 34 -> "noto"
            m == "noto" || m == "colr" || m == "openemoji" -> m
            else -> "system"
        }
    }

    fun isOpenEmoji(ctx: Context) = mode(ctx) == "openemoji"

    /** True for text made only of emoji/symbols (no letters or digits), so words in the suggestion bar keep the normal font. */
    fun isEmojiText(s: String): Boolean {
        if (s.isEmpty()) return false
        var emoji = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) return false
            if (cp >= 0x203C) emoji = true
        }
        return emoji
    }

    @Synchronized fun get(ctx: Context): Typeface? {
        val m = mode(ctx)
        if (m == "system") return null
        if (cache.containsKey(m)) return cache[m]
        val face = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, when (m) { "colr" -> "fonts/Noto-COLRv1.ttf"; "openemoji" -> "fonts/OpenEmoji.ttf"; else -> "fonts/NotoColorEmoji.ttf" })
        } catch (e: Throwable) { DevLog.e(ctx, "EmojiFont", "couldn't load $m", e); null }
        cache[m] = face
        return face
    }
}

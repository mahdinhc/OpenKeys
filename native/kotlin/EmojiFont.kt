package com.openkeys.keyboard

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * Emoji font used to DRAW emoji inside the keyboard (emoji panel, Emoji Kitchen slots).
 *   system -> whatever the phone ships (newest emoji may be missing and are then hidden)
 *   noto   -> bundled Noto Color Emoji (bitmap, Unicode 18, works on every Android version)
 *   colr   -> bundled Noto COLRv1 (vector, smaller & sharper at any size, Android 14+; falls back to "noto")
 * Other apps still draw what you send with their own font.
 */
object EmojiFont {
    private val cache = HashMap<String, Typeface?>()

    fun mode(ctx: Context): String {
        val m = Prefs(ctx).str("emojiFont", "system")
        return when {
            m == "colr" && Build.VERSION.SDK_INT < 34 -> "noto"
            m == "noto" || m == "colr" -> m
            else -> "system"
        }
    }

    @Synchronized fun get(ctx: Context): Typeface? {
        val m = mode(ctx)
        if (m == "system") return null
        if (cache.containsKey(m)) return cache[m]
        val face = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, if (m == "colr") "fonts/Noto-COLRv1.ttf" else "fonts/NotoColorEmoji.ttf")
        } catch (e: Throwable) { DevLog.e(ctx, "EmojiFont", "couldn't load $m", e); null }
        cache[m] = face
        return face
    }
}

package com.openkeys.keyboard

import android.graphics.Color

class Theme(
    val bg: Int, val key: Int, val keyText: Int, val special: Int,
    val accent: Int, val accentText: Int, val hint: Int, val pressed: Int, val popup: Int
) {
    companion object {
        private fun c(s: String) = Color.parseColor(s)
        val LIGHT = Theme(c("#E3E6EA"), c("#FFFFFF"), c("#202124"), c("#C9CED4"), c("#1A73E8"), c("#FFFFFF"), c("#5F6368"), c("#BDC1C6"), c("#FFFFFF"))
        val DARK = Theme(c("#2B2E32"), c("#494C50"), c("#E8EAED"), c("#383B3F"), c("#669DF6"), c("#FFFFFF"), c("#B0B5BA"), c("#62666B"), c("#5A5D61"))
        val AMOLED = Theme(c("#000000"), c("#1A1A1A"), c("#FFFFFF"), c("#0D0D0D"), c("#BB86FC"), c("#000000"), c("#8A8A8A"), c("#333333"), c("#2A2A2A"))

        fun from(p: Prefs): Theme {
            val name = p.str("theme", "dark")
            val base = when (name) { "light" -> LIGHT; "amoled" -> AMOLED; else -> DARK }
            if (name != "custom") return base
            val o = p.cfg.optJSONObject("colors") ?: return base
            fun g(k: String, d: Int) = try { Color.parseColor(o.optString(k)) } catch (e: Exception) { d }
            val key = g("key", base.key)
            return Theme(g("bg", base.bg), key, g("keyText", base.keyText), g("special", base.special),
                g("accent", base.accent), g("accentText", base.accentText), g("hint", base.hint),
                g("pressed", base.pressed), key)
        }
    }
}

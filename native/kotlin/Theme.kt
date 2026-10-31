package com.openkeys.keyboard

import android.graphics.Color

class Theme(
    val bg: Int, val key: Int, val keyText: Int, val special: Int,
    val accent: Int, val accentText: Int, val hint: Int, val pressed: Int, val popup: Int
) {
    /** Same colors with another background (used when a background image is drawn behind the keyboard). */
    fun withBg(c: Int) = Theme(c, key, keyText, special, accent, accentText, hint, pressed, popup)

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        fun h(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        put("bg", h(bg)); put("key", h(key)); put("keyText", h(keyText)); put("special", h(special))
        put("accent", h(accent)); put("accentText", h(accentText)); put("hint", h(hint))
    }

    companion object {
        private fun c(s: String) = Color.parseColor(s)
        val LIGHT = Theme(c("#E3E6EA"), c("#FFFFFF"), c("#202124"), c("#C9CED4"), c("#1A73E8"), c("#FFFFFF"), c("#5F6368"), c("#BDC1C6"), c("#FFFFFF"))
        val DARK = Theme(c("#2B2E32"), c("#494C50"), c("#E8EAED"), c("#383B3F"), c("#669DF6"), c("#FFFFFF"), c("#B0B5BA"), c("#62666B"), c("#5A5D61"))
        val AMOLED = Theme(c("#000000"), c("#1A1A1A"), c("#FFFFFF"), c("#0D0D0D"), c("#BB86FC"), c("#000000"), c("#8A8A8A"), c("#333333"), c("#2A2A2A"))

        /** bg, key, keyText, special, accent, hint  - keep in sync with PRESETS in src/config.ts */
        val PRESETS: Map<String, Array<String>> = mapOf(
        "midnight" to arrayOf("#0B1026", "#1B2447", "#E6EBFF", "#131A38", "#7AA2FF", "#8F9BC7"),
        "ocean" to arrayOf("#0A2A3A", "#14465C", "#E3F6FF", "#0E3649", "#2DD4BF", "#8FBBCB"),
        "forest" to arrayOf("#14231A", "#25402D", "#E6F4EA", "#1B3022", "#7BD88F", "#9BB8A3"),
        "sunset" to arrayOf("#2A1420", "#4A2536", "#FFE9E0", "#381B29", "#FF8A65", "#C99C9C"),
        "cherry" to arrayOf("#240A12", "#431A28", "#FFE6EC", "#31101B", "#FF4D6D", "#C58A98"),
        "mocha" to arrayOf("#2B211C", "#4A3B33", "#F3E5D8", "#382C25", "#D9A066", "#B5A294"),
        "nord" to arrayOf("#2E3440", "#434C5E", "#ECEFF4", "#3B4252", "#88C0D0", "#9AA5B8"),
        "dracula" to arrayOf("#282A36", "#44475A", "#F8F8F2", "#343746", "#BD93F9", "#8D93B5"),
        "solarized_dark" to arrayOf("#002B36", "#073642", "#EEE8D5", "#00212B", "#B58900", "#839496"),
        "gruvbox" to arrayOf("#282828", "#3C3836", "#EBDBB2", "#32302F", "#FABD2F", "#A89984"),
        "graphite" to arrayOf("#1C1C1E", "#2C2C2E", "#F2F2F7", "#232325", "#0A84FF", "#8E8E93"),
        "cyberpunk" to arrayOf("#0D0221", "#261447", "#F5F5F5", "#190A33", "#FF2A6D", "#05D9E8"),
        "hc_dark" to arrayOf("#000000", "#1F1F1F", "#FFFFFF", "#111111", "#FFD400", "#CCCCCC"),
        "rose" to arrayOf("#FCE4EC", "#FFFFFF", "#3E1F2B", "#F3C6D3", "#D81B60", "#8E5A6B"),
        "lavender" to arrayOf("#ECE6F7", "#FFFFFF", "#2B2340", "#D6CCEB", "#7E57C2", "#6F6590"),
        "mint" to arrayOf("#E0F2EC", "#FFFFFF", "#1B3B30", "#C3E3D8", "#00A67E", "#5B8A7A"),
        "sand" to arrayOf("#EFE6D8", "#FFFBF3", "#3B3224", "#DDD0BB", "#C77D2E", "#8A7A62"),
        "solarized_light" to arrayOf("#FDF6E3", "#FFFFFF", "#586E75", "#EEE8D5", "#268BD2", "#93A1A1"),
        "paper" to arrayOf("#FFFFFF", "#F0F0F0", "#000000", "#DDDDDD", "#0057D9", "#444444"),
        )

        private fun lum(x: Int): Double = (0.299 * Color.red(x) + 0.587 * Color.green(x) + 0.114 * Color.blue(x)) / 255.0

        private fun derive(bg: Int, key: Int, keyText: Int, special: Int, accent: Int, hint: Int, accentText: Int? = null): Theme {
            val at = accentText ?: if (lum(accent) > 0.6) c("#101114") else Color.WHITE
            val pressed = androidx.core.graphics.ColorUtils.blendARGB(key, keyText, 0.18f)
            return Theme(bg, key, keyText, special, accent, at, hint, pressed, key)
        }

        fun preset(name: String): Theme? {
            val p = PRESETS[name] ?: return null
            val v = p.map { c(it) }
            return derive(v[0], v[1], v[2], v[3], v[4], v[5])
        }

        fun from(p: Prefs, night: Boolean = true): Theme {
            val name = p.str("theme", "dark")
            when (name) {
                "light" -> return LIGHT
                "dark" -> return DARK
                "amoled" -> return AMOLED
                "system" -> return if (night) DARK else LIGHT
            }
            if (name != "custom") return preset(name) ?: DARK
            val base = DARK
            val o = p.cfg.optJSONObject("colors") ?: return base
            fun g(k: String, d: Int) = try { Color.parseColor(o.optString(k)) } catch (e: Exception) { d }
            val key = g("key", base.key)
            return Theme(g("bg", base.bg), key, g("keyText", base.keyText), g("special", base.special),
                g("accent", base.accent), g("accentText", base.accentText), g("hint", base.hint),
                g("pressed", base.pressed), key)
        }
    }
}

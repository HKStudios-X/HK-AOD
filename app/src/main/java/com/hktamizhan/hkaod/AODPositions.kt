package com.hktamizhan.hkaod

import android.content.SharedPreferences

/**
 * Layout description of one AOD element.
 * [x] and [y] are fractions (0..1) of the AOD area and position the element's
 * CENTER (scaling pivots about the centre, so it never drifts).
 * [align] only controls the text alignment inside the element
 * ("start" = left, "center", "end" = right).
 * [scale] is a free multiplier (0.4..2.5). [visible] toggles it.
 */
data class AODPosition(
    val x: Float,
    val y: Float,
    val scale: Float,
    val visible: Boolean,
    val align: String = "center"
)

object AODPositions {
    const val PREFS_NAME = "HKAOD_PREFS"

    val Elements = listOf("clock", "date", "notifications", "media", "charging")

    val Alignments = listOf("start", "center", "end")

    val Defaults = mapOf(
        // y is the CENTRE of the element as a fraction of the screen, so a
        // value of 0 would draw half of the element above the top edge.
        "clock"         to AODPosition(0.5f, 0.10f, 1f, true, "center"),
        "date"          to AODPosition(0.5f, 0.20f, 1f, true, "center"),
        "notifications" to AODPosition(0.5f, 0.35f, 1f, true, "center"),
        "media"         to AODPosition(0.5f, 0.76f, 1f, true, "center"),
        "charging"      to AODPosition(0.5f, 0.90f, 1f, true, "center"),
    )

    private val legacyKeys = mapOf(
        "clock"         to "pos_clock",
        "date"          to "pos_date",
        "notifications" to "pos_notifications",
        "media"         to "pos_media",
        "charging"      to "pos_charging",
    )

    fun read(prefs: SharedPreferences, element: String): AODPosition {
        if (prefs.contains("${element}_pos_x")) {
            return AODPosition(
                x       = prefs.getFloat("${element}_pos_x", 0.5f).coerceIn(0f, 1f),
                y       = prefs.getFloat("${element}_pos_y", 0.5f).coerceIn(0f, 1f),
                scale   = prefs.getFloat("${element}_scale", 1f).coerceIn(0.4f, 2.5f),
                visible = prefs.getBoolean("${element}_visible", true),
                align   = prefs.getString("${element}_align", "center") ?: "center"
            )
        }
        val legacy = prefs.getString(legacyKeys[element], null)
        val fallback = Defaults[element] ?: AODPosition(0.5f, 0.5f, 1f, true)
        val migrated = preset(legacy) ?: fallback
        return if (legacy == "hidden") migrated.copy(visible = false) else migrated
    }

    fun save(prefs: SharedPreferences, element: String, pos: AODPosition) {
        prefs.edit()
            .putFloat("${element}_pos_x", pos.x)
            .putFloat("${element}_pos_y", pos.y)
            .putFloat("${element}_scale", pos.scale)
            .putBoolean("${element}_visible", pos.visible)
            .putString("${element}_align", pos.align)
            .apply()
    }

    private fun preset(name: String?): AODPosition? = when (name) {
        "top_left"      -> AODPosition(0f, 0f, 1f, true)
        "top_center"    -> AODPosition(0.5f, 0f, 1f, true)
        "top_right"     -> AODPosition(1f, 0f, 1f, true)
        "center_left"   -> AODPosition(0f, 0.5f, 1f, true)
        "center"        -> AODPosition(0.5f, 0.5f, 1f, true)
        "center_right"  -> AODPosition(1f, 0.5f, 1f, true)
        "bottom_left"   -> AODPosition(0f, 1f, 1f, true)
        "bottom_center" -> AODPosition(0.5f, 1f, 1f, true)
        "bottom_right"  -> AODPosition(1f, 1f, 1f, true)
        else            -> null
    }
}
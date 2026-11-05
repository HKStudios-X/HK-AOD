package com.hktamizhan.hkaod

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// Clock style options — add as many font families as you want here
enum class ClockStyle(val label: String) {
    STYLE_1("Super"),   // hkFontFamily  (hksuper)
    STYLE_2("JJK"),     // hkFontFamily2 (hkjjk)
    STYLE_3("Exo"),     // hkFontFamily3 (hkexo)
    STYLE_4("Firelest"),
    STYLE_5("High"),
    STYLE_6("Rocky"),

    // Appended bundled-font presets (res/font). Alphabetical.
    STYLE_7("Aclonica"),
    STYLE_8("Amarante"),
    STYLE_9("Bariol"),
    STYLE_10("Cagliostro"),
    STYLE_11("Comfortaa"),
    STYLE_12("Comic Sans"),
    STYLE_13("Coolstory"),
    STYLE_14("Dot Matrix"),
    STYLE_15("Fluid Sans"),
    STYLE_16("Gear Tinkerbell"),
    STYLE_17("Google Sans"),
    STYLE_18("HarmonyOS Sans"),
    STYLE_19("Inter"),
    STYLE_20("JetBrains Mono"),
    STYLE_21("LG Smart Gothic"),
    STYLE_22("Linotte"),
    STYLE_23("Manrope"),
    STYLE_24("Montserrat"),
    STYLE_25("Ndot"),
    STYLE_26("Ndot 57"),
    STYLE_27("OnePlus Slate"),
    STYLE_28("OPPO Sans"),
    STYLE_29("OP Sans"),
    STYLE_30("Roboto"),
    STYLE_31("Rosemary"),
    STYLE_32("Samsung One"),
    STYLE_33("Slate"),
    STYLE_34("Sony Sketch"),
    STYLE_35("Storopia"),
    STYLE_36("Surfer"),
}

object ClockStyleRepository {
    // Where an imported clock font is copied to inside app storage.
    private const val CUSTOM_FONT_FILE = "clock_font.ttf"
    private const val PREF_USE_CUSTOM  = "clock_use_custom"
    private const val PREF_FONT_NAME   = "clock_font_name"
    private const val PREF_STYLE       = "clock_style"
    private const val PREF_SIZE        = "clock_size"

    private val _style = MutableStateFlow(ClockStyle.STYLE_1)
    val style: StateFlow<ClockStyle> = _style
    private val _size = MutableStateFlow(80f)
    val size: StateFlow<Float> = _size

    // ── Custom (user-imported) font ──────────────────────────────
    private val _customFont = MutableStateFlow<FontFamily?>(null)
    val customFont: StateFlow<FontFamily?> = _customFont
    private val _useCustomFont = MutableStateFlow(false)
    val useCustomFont: StateFlow<Boolean> = _useCustomFont
    private val _customFontName = MutableStateFlow<String?>(null)
    val customFontName: StateFlow<String?> = _customFontName

    fun setStyle(s: ClockStyle) {
        _style.value = s
        // Picking a built-in style turns the custom font off.
        _useCustomFont.value = false
    }
    fun setSize(s: Float) { _size.value = s }

    /** Persistently select a clock style (and switch off the custom font). */
    fun setStyle(context: Context, s: ClockStyle) {
        setStyle(s)
        context.prefs().edit()
            .putString(PREF_STYLE, s.name)
            .putBoolean(PREF_USE_CUSTOM, false)
            .apply()
    }

    /** Persistently change the clock size. */
    fun setSize(context: Context, s: Float) {
        setSize(s)
        context.prefs().edit().putFloat(PREF_SIZE, s).apply()
    }

    /**
     * Restore style, size and custom font from prefs. Call on app/service start
     * so the AOD renders the user's selection even after the process is killed.
     */
    fun loadFromPrefs(context: Context) {
        val prefs = context.prefs()
        val savedStyle = prefs.getString(PREF_STYLE, null)
        if (savedStyle != null) {
            runCatching { ClockStyle.valueOf(savedStyle) }
                .onSuccess { _style.value = it }
        }
        setSize(prefs.getFloat(PREF_SIZE, 80f).coerceIn(30f, 200f))
        loadPersistedCustomFont(context)
    }

    /** The font the clock should actually render with right now. */
    fun effectiveFontFamily(style: ClockStyle): FontFamily {
        val custom = _customFont.value
        return if (_useCustomFont.value && custom != null) custom else fontFamilyFor(style)
    }

    /**
     * Copy the picked font into app storage and activate it.
     * @return the font's display name on success, or null if it couldn't be loaded.
     */
    fun importCustomFont(context: Context, bytes: ByteArray, displayName: String): String? {
        return try {
            val outFile = File(context.filesDir, CUSTOM_FONT_FILE)
            outFile.writeBytes(bytes)
            // Validate by actually building a FontFamily from the file.
            val family = FontFamily(Font(outFile))
            _customFont.value     = family
            _customFontName.value = displayName
            _useCustomFont.value  = true
            context.prefs().edit()
                .putBoolean(PREF_USE_CUSTOM, true)
                .putString(PREF_FONT_NAME, displayName)
                .apply()
            displayName
        } catch (e: Exception) {
            // Bad/corrupt font file — clean up so we don't load it next time.
            File(context.filesDir, CUSTOM_FONT_FILE).delete()
            null
        }
    }

    /** Stop using the imported font and fall back to the selected built-in style. */
    fun clearCustomFont(context: Context) {
        File(context.filesDir, CUSTOM_FONT_FILE).delete()
        _customFont.value     = null
        _customFontName.value = null
        _useCustomFont.value  = false
        context.prefs().edit()
            .putBoolean(PREF_USE_CUSTOM, false)
            .remove(PREF_FONT_NAME)
            .apply()
    }

    /** Re-load a previously imported font after a process restart. */
    fun loadPersistedCustomFont(context: Context) {
        val prefs = context.prefs()
        if (!prefs.getBoolean(PREF_USE_CUSTOM, false)) {
            _useCustomFont.value = false
            return
        }
        val file = File(context.filesDir, CUSTOM_FONT_FILE)
        if (!file.exists()) {
            _useCustomFont.value = false
            return
        }
        try {
            _customFont.value     = FontFamily(Font(file))
            _customFontName.value = prefs.getString(PREF_FONT_NAME, "Custom font")
            _useCustomFont.value  = true
        } catch (e: Exception) {
            _useCustomFont.value = false
        }
    }

    private fun Context.prefs() =
        getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE)

    // Map enum → your existing FontFamily declarations in MainActivity.kt
    fun fontFamilyFor(style: ClockStyle): FontFamily = when (style) {
        ClockStyle.STYLE_1 -> hkFontFamily
        ClockStyle.STYLE_2 -> hkFontFamily2
        ClockStyle.STYLE_3 -> hkFontFamily3
        ClockStyle.STYLE_4 -> hkFontFamily4
        ClockStyle.STYLE_5 -> hkFontFamily5
        ClockStyle.STYLE_6 -> hkFontFamily6
        ClockStyle.STYLE_7 -> hkFontFamily7
        ClockStyle.STYLE_8 -> hkFontFamily8
        ClockStyle.STYLE_9 -> hkFontFamily9
        ClockStyle.STYLE_10 -> hkFontFamily10
        ClockStyle.STYLE_11 -> hkFontFamily11
        ClockStyle.STYLE_12 -> hkFontFamily12
        ClockStyle.STYLE_13 -> hkFontFamily13
        ClockStyle.STYLE_14 -> hkFontFamily14
        ClockStyle.STYLE_15 -> hkFontFamily15
        ClockStyle.STYLE_16 -> hkFontFamily16
        ClockStyle.STYLE_17 -> hkFontFamily17
        ClockStyle.STYLE_18 -> hkFontFamily18
        ClockStyle.STYLE_19 -> hkFontFamily19
        ClockStyle.STYLE_20 -> hkFontFamily20
        ClockStyle.STYLE_21 -> hkFontFamily21
        ClockStyle.STYLE_22 -> hkFontFamily22
        ClockStyle.STYLE_23 -> hkFontFamily23
        ClockStyle.STYLE_24 -> hkFontFamily24
        ClockStyle.STYLE_25 -> hkFontFamily25
        ClockStyle.STYLE_26 -> hkFontFamily26
        ClockStyle.STYLE_27 -> hkFontFamily27
        ClockStyle.STYLE_28 -> hkFontFamily28
        ClockStyle.STYLE_29 -> hkFontFamily29
        ClockStyle.STYLE_30 -> hkFontFamily30
        ClockStyle.STYLE_31 -> hkFontFamily31
        ClockStyle.STYLE_32 -> hkFontFamily32
        ClockStyle.STYLE_33 -> hkFontFamily33
        ClockStyle.STYLE_34 -> hkFontFamily34
        ClockStyle.STYLE_35 -> hkFontFamily35
        ClockStyle.STYLE_36 -> hkFontFamily36
    }
}

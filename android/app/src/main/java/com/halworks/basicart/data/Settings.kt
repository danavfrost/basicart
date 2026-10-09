package com.halworks.basicart.data

import android.content.Context
import com.halworks.basicart.model.Colors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"), JPEG("JPG", "jpg", "image/jpeg"), GIF("GIF", "gif", "image/gif")
}

/** App settings in SharedPreferences, exposed as StateFlows (applied instantly). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(ThemeMode.entries.getOrElse(prefs.getInt("theme", 0)) { ThemeMode.SYSTEM })
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(t: ThemeMode) { _theme.value = t; prefs.edit().putInt("theme", t.ordinal).apply() }

    private val _snapping = MutableStateFlow(prefs.getBoolean("snapping", true))
    val snapping: StateFlow<Boolean> = _snapping
    fun setSnapping(v: Boolean) { _snapping.value = v; prefs.edit().putBoolean("snapping", v).apply() }

    private val _exportFormat = MutableStateFlow(ExportFormat.entries.getOrElse(prefs.getInt("exportFormat", 0)) { ExportFormat.PNG })
    val exportFormat: StateFlow<ExportFormat> = _exportFormat
    fun setExportFormat(f: ExportFormat) { _exportFormat.value = f; prefs.edit().putInt("exportFormat", f.ordinal).apply() }

    private val _jpegQuality = MutableStateFlow(prefs.getInt("jpegQuality", 90))
    val jpegQuality: StateFlow<Int> = _jpegQuality
    fun setJpegQuality(q: Int) { _jpegQuality.value = q.coerceIn(50, 100); prefs.edit().putInt("jpegQuality", _jpegQuality.value).apply() }

    private fun colors(key: String) = (prefs.getString(key, "") ?: "").split(",").mapNotNull { Colors.parse(it) }
    private fun saveColors(key: String, c: List<Int>) = prefs.edit().putString(key, c.joinToString(",") { Colors.format(it) }).apply()

    private val _recentColors = MutableStateFlow(colors("recentColors"))
    val recentColors: StateFlow<List<Int>> = _recentColors
    fun addRecentColor(c: Int) {
        val l = (listOf(c) + _recentColors.value.filter { it != c }).take(12)
        _recentColors.value = l; saveColors("recentColors", l)
    }

    private val _myColors = MutableStateFlow(colors("myColors"))
    val myColors: StateFlow<List<Int>> = _myColors
    fun addMyColor(c: Int) {
        if (c in _myColors.value) return
        val l = (_myColors.value + c).takeLast(40)
        _myColors.value = l; saveColors("myColors", l)
    }
    fun removeMyColor(c: Int) {
        val l = _myColors.value.filter { it != c }
        _myColors.value = l; saveColors("myColors", l)
    }

    /** Docked side panels on tablets: null = never set (use the width-based default). */
    fun dockedPanel(key: String): Boolean? = if (prefs.contains("dock_$key")) prefs.getBoolean("dock_$key", true) else null
    fun setDockedPanel(key: String, expanded: Boolean) = prefs.edit().putBoolean("dock_$key", expanded).apply()

    private val _recentFonts = MutableStateFlow((prefs.getString("recentFonts", "") ?: "").split(",").filter { it.isNotBlank() })
    val recentFonts: StateFlow<List<String>> = _recentFonts
    fun addRecentFont(id: String) {
        val l = (listOf(id) + _recentFonts.value.filter { it != id }).take(10)
        _recentFonts.value = l; prefs.edit().putString("recentFonts", l.joinToString(",")).apply()
    }
}

package ua.controldex

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Спільні налаштування й тема для всіх екранів. */
object Ui {
    const val PREFS = "controldex"
    const val KEY_DEBUG = "debug"
    const val KEY_THEME = "theme"
    const val KEY_GREEN = "greenMode"
    const val KEY_AUTO = "autoConnect"
    const val KEY_LAST_MAC = "lastMac"
    const val THEME_LIGHT = 0
    const val THEME_DARK = 1
    const val THEME_SYSTEM = 2

    const val VERSION = "0.7"

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun themeMode(c: Context) = prefs(c).getInt(KEY_THEME, THEME_SYSTEM)
    fun debug(c: Context) = prefs(c).getBoolean(KEY_DEBUG, false)
    /** Режим GREEN: GATT через класичний Bluetooth (типово увімкнено). */
    fun greenMode(c: Context) = prefs(c).getBoolean(KEY_GREEN, true)
    fun autoConnect(c: Context) = prefs(c).getBoolean(KEY_AUTO, true)

    fun isDark(a: Activity): Boolean = when (themeMode(a)) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Викликати до super.onCreate / setContentView. */
    fun applyTheme(a: Activity) {
        a.setTheme(if (isDark(a)) android.R.style.Theme_Material else android.R.style.Theme_Material_Light_DarkActionBar)
    }
}

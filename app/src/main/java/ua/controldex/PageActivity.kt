package ua.controldex

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.view.View

/** Допоміжні екрани: «Про програму», «Налаштування», «Довідка» і окремий посібник. */
class PageActivity : Activity() {

    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_GUIDE = "guide"
        const val ABOUT = "about"
        const val SETTINGS = "settings"
        const val HELP = "help"
        const val GUIDE = "guide"

        fun open(a: Activity, page: String, guide: Int = -1) =
            a.startActivity(Intent(a, PageActivity::class.java).putExtra(EXTRA_PAGE, page).putExtra(EXTRA_GUIDE, guide))
    }

    private lateinit var root: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(b: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(b)
        Core.init(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), px(24)) }
        setContentView(ScrollView(this).apply { addView(root) })
        when (intent.getStringExtra(EXTRA_PAGE)) {
            ABOUT -> about()
            SETTINGS -> settings()
            HELP -> help()
            GUIDE -> guide(HelpTexts.ALL.getOrNull(intent.getIntExtra(EXTRA_GUIDE, 0)) ?: HelpTexts.APP_USAGE)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------- Про програму ----------
    private fun about() {
        title = "Про програму"
        root.addView(TextView(this).apply {
            text = "ControlDex"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_info, 0, 0, 0); compoundDrawablePadding = px(10)
        })
        para("Версія ${Ui.VERSION}")
        head("Що це")
        para("Неофіційний застосунок для керування стримером Widex COM-DEX і слуховими апаратами з Android-телефона: гучність, програми, Zen+, фокус, Boost, мікрофони слухових апаратів під час трансляції, стан батареї та neck loop.")
        para("ControlDex тримає з'єднання у фоні (постійне сповіщення), сам перепідключається після втрати зв'язку і підключається до останнього COM-DEX під час запуску.")
        para("ControlDex з'єднується з COM-DEX так само, як оригінальний застосунок у режимі для проблемних телефонів: GATT іде через уже наявне класичне Bluetooth-з'єднання (BR/EDR), без окремого BLE-каналу. Тому звук не заїкається під час старту музики.")
        head("Розробник")
        para("SantinoDnepr\ngithub.com/SantinoDnepr/ControlDex")
        para("Код написано за допомогою Claude (Anthropic).")
        head("Бібліотеки та ресурси")
        para("• Android SDK (Apache License 2.0)\n• Kotlin standard library (Apache License 2.0)\n• Іконки Material Icons, Google (Apache License 2.0)\nСторонніх бібліотек, аналітики й реклами немає. Застосунок не збирає й не передає жодних даних; журнал зберігається лише на телефоні.")
        head("Застереження")
        para("Протокол COM-DEX і тексти довідки взято з аналізу офіційного застосунку COM-DEX 1.3.0 (Widex A/S). ControlDex не пов'язаний з Widex A/S і не схвалений нею. Widex і COM-DEX — торговельні марки їхніх власників. Користуйтеся на власний ризик.")
    }

    // ---------- Налаштування ----------
    private fun settings() {
        title = "Налаштування"
        val prefs = Ui.prefs(this)
        root.addView(CheckBox(this).apply {
            text = "Налагодження"
            isChecked = Ui.debug(this@PageActivity)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Ui.KEY_DEBUG, on).apply() }
        })
        small("Показує на головному екрані кнопки «Оновити стан COM-DEX», «Заїкається!» і журнал.")
        root.addView(CheckBox(this).apply {
            text = "Режим Green для проблемних телефонів"
            isChecked = Ui.greenMode(this@PageActivity)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Ui.KEY_GREEN, on).apply(); Core.log("Опція: greenMode = $on") }
        })
        small("GATT через класичне Bluetooth-з'єднання, без окремого BLE-каналу (як прихований режим оригіналу). Усуває заїкання звуку на телефонах на кшталт Redmi. Діє з наступного підключення.")
        root.addView(CheckBox(this).apply {
            text = "Автопідключення при запуску"
            isChecked = Ui.autoConnect(this@PageActivity)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Ui.KEY_AUTO, on).apply() }
        })
        small("Під час запуску ControlDex підключається до останнього COM-DEX. Після втрати зв'язку застосунок перепідключається сам, доки ви не натиснете «Відключитися».")
        head("Тема")
        val ids = mapOf(Ui.THEME_LIGHT to 101, Ui.THEME_DARK to 102, Ui.THEME_SYSTEM to 103)
        val group = RadioGroup(this)
        listOf(Ui.THEME_LIGHT to "Світла", Ui.THEME_DARK to "Темна", Ui.THEME_SYSTEM to "Системна").forEach { (mode, name) ->
            group.addView(RadioButton(this).apply { id = ids.getValue(mode); text = name })
        }
        group.check(ids.getValue(Ui.themeMode(this)))
        group.setOnCheckedChangeListener { _, id ->
            val mode = ids.entries.first { it.value == id }.key
            if (mode != Ui.themeMode(this)) { prefs.edit().putInt(Ui.KEY_THEME, mode).apply(); recreate() }
        }
        root.addView(group)

        head("Робота у фоні")
        para("Поки COM-DEX підключений, ControlDex показує постійне сповіщення: так система не закриває з'єднання, коли застосунок згорнуто або екран вимкнено. На Xiaomi/Redmi (MIUI) цього мало, потрібно ще два кроки:")
        para("1. Вимкніть оптимізацію батареї для ControlDex (режим «Без обмежень»).")
        val pm = getSystemService(android.os.PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) == true
        root.addView(Button(this).apply {
            text = if (ignoring) "Оптимізацію батареї вже вимкнено" else "Вимкнути оптимізацію батареї"
            setAllCaps(false)
            setOnClickListener { batteryOptimization() }
        })
        para("2. Дозвольте автозапуск: Безпека → Дозволи → Автозапуск → ControlDex. На MIUI також у «Недавніх застосунках» затисніть картку ControlDex і натисніть замок.")
        root.addView(Button(this).apply {
            text = "Відкрити автозапуск (MIUI)"
            setAllCaps(false)
            setOnClickListener { autostart() }
        })
        root.addView(Button(this).apply {
            text = "Параметри застосунку (сповіщення, батарея)"
            setAllCaps(false)
            setOnClickListener { appDetails() }
        })
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun batteryOptimization() {
        val tries = listOf(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")),
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        if (!tries.any { tryStart(it) }) appDetails()
    }

    private fun autostart() {
        val tries = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        )
        if (!tries.any { tryStart(it) }) {
            android.widget.Toast.makeText(this, "Автозапуск не знайдено — відкриваю параметри застосунку", android.widget.Toast.LENGTH_LONG).show()
            appDetails()
        }
    }

    private fun appDetails() {
        tryStart(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    }

    private fun tryStart(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }

    // ---------- Довідка ----------
    private fun help() {
        title = "Довідка"
        HelpTexts.ALL.forEachIndexed { i, g ->
            root.addView(Button(this).apply {
                text = g.title; setAllCaps(false); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_help, 0, 0, 0); compoundDrawablePadding = px(12)
                setOnClickListener { open(this@PageActivity, GUIDE, i) }
            })
        }
    }

    private fun guide(g: HelpTexts.Guide) {
        title = "Довідка"
        root.addView(TextView(this).apply { text = g.title; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
        g.intro?.let { para(it) }
        g.sections.forEach { s ->
            head(s.title)
            s.items.forEach { para(if (it.first().isDigit()) it else "• $it") }
        }
        g.outro.forEach { para(it) }
    }

    // ---------- допоміжне ----------
    private fun px(v: Int) = (v * dp).toInt()
    private fun head(t: String) = root.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, px(18), 0, px(4)) })
    private fun para(t: String) = root.addView(TextView(this).apply { text = t; textSize = 15f; setPadding(0, px(4), 0, px(4)); setLineSpacing(0f, 1.15f) })
    private fun small(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f; alpha = 0.7f; setPadding(px(32), 0, 0, px(8)) })
}

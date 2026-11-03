package ua.controldex

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Space
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

@SuppressLint("MissingPermission", "SetTextI18n", "ClickableViewAccessibility")
class MainActivity : Activity(), Core.View {

    private companion object {
        const val REQ_ENABLE_BT = 10
        const val REQ_ENABLE_PERM = 11
    }

    private val k = Core
    private val gatt get() = Core.gatt
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var batteryView: BatteryView
    private lateinit var batteryText: TextView
    private lateinit var micBtn: Button
    private lateinit var connBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var progInfo: TextView
    private lateinit var debugPanel: LinearLayout
    private lateinit var menuAnchor: View
    private lateinit var boostSwitch: Switch
    private val controls = mutableListOf<View>()
    private val focusBtns = mutableListOf<Pair<ByteArray, Button>>()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { Ui.prefs(this) }
    private var updatingBoost = false
    private var themeAtCreate = -1
    private var darkAtCreate = false
    private var muteLocked = false
    private val dp by lazy { resources.displayMetrics.density }

    /** Увімкнення/вимкнення Bluetooth міняє напис кнопки підключення навіть до першого підключення. */
    private val btStateRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { onChanged() }
    }

    override fun onCreate(b: Bundle?) {
        Ui.applyTheme(this)
        themeAtCreate = Ui.themeMode(this)
        darkAtCreate = Ui.isDark(this)
        super.onCreate(b)
        Core.init(this)
        title = "ControlDex"
        buildUi()
        val f = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btStateRx, f, RECEIVER_EXPORTED) else registerReceiver(btStateRx, f)
        Core.view = this
        onChanged()
        if (!Core.autoTried && !gatt.wantConnected && Ui.autoConnect(this) && prefs.getString(Ui.KEY_LAST_MAC, null) != null) {
            // автопідключення до останнього COM-DEX; якщо бракує дозволів — спершу запит
            if (missingPerms().isEmpty()) Core.autoConnect() else connect()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        menuAnchor = View(this)
        setContentView(FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(root) })
            addView(menuAnchor, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        })
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
            setHomeActionContentDescription("Меню")
        }

        status = TextView(this).apply { textSize = 18f }
        info = TextView(this)
        root.addView(status)
        root.addView(info)

        // одна кнопка: «Увімкнути Bluetooth» / «Під'єднатись» / «Від'єднатись»
        connBtn = btn("") { onConnButton() }
        root.addView(connBtn)

        val bat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16, 0, 0) }
        batteryView = BatteryView(this)
        batteryText = TextView(this).apply { textSize = 18f; text = "  Батарея COM-DEX: —" }
        bat.addView(batteryView)
        bat.addView(batteryText)
        root.addView(bat)

        root.addView(label("Гучність"))
        val vol = LinearLayout(this)
        vol.addView(repeatCtl("−") { volume(-1) }, lp())
        vol.addView(repeatCtl("+") { volume(+1) }, lp())
        root.addView(vol)

        // Mute як в оригіналі: без трансляції — 22 × VOL_DOWN (блок на 5 с), unmute — VOL_UP
        muteBtn = ctl("") { toggleMute() }.apply { setAllCaps(false) }
        root.addView(muteBtn)

        // як кнопка room в оригіналі: мікрофони слухових апаратів під час трансляції
        micBtn = btn("") { toggleMic() }.apply { setAllCaps(false); compoundDrawablePadding = (8 * dp).toInt() }
        root.addView(micBtn)

        root.addView(label("Програма"))
        val prog = LinearLayout(this)
        prog.addView(ctl("Наступна програма") { k.progSwitches++; pi(ComDex.NEXT_PROG); onChanged() }, lp())
        prog.addView(ctl("Zen+") { pi(ComDex.ZEN_TOGGLE) }, lp())
        root.addView(prog)
        progInfo = TextView(this).apply { textSize = 13f; alpha = 0.75f }
        root.addView(progInfo)

        root.addView(label("Фокус"))
        // сітка 3 × 3: усі кнопки однакової ширини, «Вперед» і «Назад» — по центру
        val r1 = LinearLayout(this)
        r1.addView(Space(this), lp())
        r1.addView(focusBtn("▲ Вперед", ComDex.FOCUS_FRONT), lp())
        r1.addView(Space(this), lp())
        val r2 = LinearLayout(this)
        r2.addView(focusBtn("◀ Ліво", ComDex.FOCUS_LEFT), lp())
        r2.addView(focusBtn("● Omni", ComDex.FOCUS_OMNI), lp())
        r2.addView(focusBtn("Право ▶", ComDex.FOCUS_RIGHT), lp())
        val r3 = LinearLayout(this)
        r3.addView(Space(this), lp())
        r3.addView(focusBtn("▼ Назад", ComDex.FOCUS_REAR), lp())
        r3.addView(Space(this), lp())
        root.addView(r1)
        root.addView(r2)
        root.addView(r3)

        boostSwitch = Switch(this).apply {
            text = "Boost COM-DEX media volume"
            setOnCheckedChangeListener { _, on ->
                if (!updatingBoost && k.ready) {
                    gatt.write(ComDex.BOOST, byteArrayOf(if (on) 2 else 0))
                    gatt.read(ComDex.SERVICE, ComDex.BOOST)
                }
            }
        }
        controls += boostSwitch
        root.addView(boostSwitch)

        // ---------- налагодження: усе від «Оновити стан» / «Заїкається!» донизу ----------
        debugPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (12 * dp).toInt(), 0, 0) }
        root.addView(debugPanel)
        val dbgRow = LinearLayout(this)
        dbgRow.addView(btn("Оновити стан COM-DEX") { if (k.ready) gatt.refreshState() }, lp())
        dbgRow.addView(btn("⚠ Заїкається!") { Core.log("=== MARK: заїкання звуку ===") }, lp())
        debugPanel.addView(dbgRow)
        val logRow = LinearLayout(this)
        logRow.addView(btn("Копіювати журнал") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", Core.fileLog.tail(200_000)))
        }, lp())
        logRow.addView(btn("Поділитися (zip)") { shareLog() }, lp())
        debugPanel.addView(logRow)
        debugPanel.addView(TextView(this).apply { textSize = 11f; text = "Файл журналу: ${Core.fileLog.file.absolutePath}" })
        debugPanel.addView(label("Журнал"))
        logView = TextView(this).apply { textSize = 11f }
        debugPanel.addView(logView)
        applyDebug()
    }

    private fun applyDebug() {
        val on = Ui.debug(this)
        debugPanel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) logView.text = k.logLines.reversed().joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        if (Ui.themeMode(this) != themeAtCreate || Ui.isDark(this) != darkAtCreate) { recreate(); return }
        Core.view = this
        applyDebug()
        onChanged()
    }

    // ---------- меню ☰ ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home) return super.onOptionsItemSelected(item)
        val pm = PopupMenu(this, menuAnchor, Gravity.START)
        pm.menu.add(0, 1, 0, "Про програму").setIcon(R.drawable.ic_info)
        pm.menu.add(0, 2, 1, "Налаштування").setIcon(R.drawable.ic_settings)
        pm.menu.add(0, 3, 2, "Довідка").setIcon(R.drawable.ic_help)
        if (Build.VERSION.SDK_INT >= 29) pm.setForceShowIcon(true)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> PageActivity.open(this, PageActivity.ABOUT)
                2 -> PageActivity.open(this, PageActivity.SETTINGS)
                3 -> PageActivity.open(this, PageActivity.HELP)
            }
            true
        }
        pm.show()
        return true
    }

    // ---------- підключення ----------
    private fun btAdapter(): BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private fun btOn() = btAdapter()?.isEnabled == true
    private fun linked() = gatt.wantConnected || gatt.connected

    private fun onConnButton() = when {
        !btOn() -> enableBt()
        linked() -> Core.disconnect()
        else -> connect()
    }

    private fun refreshConnBtn() {
        connBtn.text = when {
            btAdapter() == null -> "Bluetooth недоступний"
            !btOn() -> "Увімкнути Bluetooth"
            linked() -> "Від'єднатись"
            else -> "Під'єднатись"
        }
        connBtn.isEnabled = btAdapter() != null
    }

    /** Системний діалог увімкнення Bluetooth (на Android 12+ потрібен дозвіл BLUETOOTH_CONNECT). */
    private fun enableBt() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_ENABLE_PERM); return
        }
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
        } catch (e: Exception) {
            Core.log("Увімкнення Bluetooth: ${e.message}")
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT) onChanged()
    }

    private fun missingPerms(): List<String> {
        val all = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) all += listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) all += Manifest.permission.POST_NOTIFICATIONS
        return all.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun connect() {
        val need = missingPerms()
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onStateText("Увімкніть Bluetooth")
            return
        }
        val devs = adapter.bondedDevices.filter { it.name?.contains("COM-DEX") == true }
        when {
            devs.isEmpty() -> {
                onStateText("COM-DEX не спарено. Спаруйте в налаштуваннях Bluetooth (відкрийте й закрийте neck loop).")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            devs.size == 1 -> start(devs[0])
            else -> {
                val last = prefs.getString(Ui.KEY_LAST_MAC, null)
                AlertDialog.Builder(this).setTitle("Оберіть пристрій")
                    .setItems(devs.map { "${it.name} ${it.address}${if (it.address == last) " (останній)" else ""}" }.toTypedArray()) { _, i -> start(devs[i]) }
                    .show()
            }
        }
    }

    private fun start(d: BluetoothDevice) {
        Core.autoTried = true
        k.focus = null
        k.progSwitches = 0
        Core.connect(d)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (code == REQ_ENABLE_PERM) {
            if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) enableBt() else onStateText("Потрібен дозвіл Bluetooth")
            return
        }
        // сповіщення не обов'язкові: без них сервіс працює, лише сповіщення не видно
        val bt = p.indices.filter { p[it] != Manifest.permission.POST_NOTIFICATIONS }.all { r.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
        if (bt) connect() else onStateText("Потрібен дозвіл Bluetooth")
    }

    private fun onStateText(t: String) { k.status = t; status.text = t }

    private fun shareLog() {
        val zip = Core.fileLog.makeZip(cacheDir)
        if (zip == null) android.widget.Toast.makeText(this, "Не вдалося створити zip", android.widget.Toast.LENGTH_LONG).show()
        else {
            val uri = android.net.Uri.parse("content://ua.controldex.logs/${zip.name}")
            val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("", uri)
            startActivity(Intent.createChooser(send, "Журнал ControlDex"))
        }
    }

    // ---------- команди ----------
    private fun pi(cmd: ByteArray) {
        if (k.ready) gatt.write(ComDex.PI_CMD, cmd)
    }

    private fun focus(cmd: ByteArray) {
        pi(ComDex.ROOM_ON)
        pi(cmd)
        k.focus = cmd
        onChanged()
    }

    /** Як в оригіналі: Idle -> PI_COMMAND; A2DP -> гучність медіа; HFP -> гучність SCO. */
    private fun volume(dir: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val d = if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        if (dir > 0 && k.muted) { k.muted = false; onChanged() }
        try {
            when (k.profile) {
                3 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
                2 -> am.adjustStreamVolume(6, d, AudioManager.FLAG_SHOW_UI)
                else -> pi(if (dir > 0) ComDex.VOL_UP else ComDex.VOL_DOWN)
            }
        } catch (e: Exception) {
            Core.log("volume: ${e.message}")
        }
    }

    /**
     * Mute як в оригіналі: без трансляції — 22 × VOL_DOWN у слухові апарати (повтор заблоковано на 5 с),
     * unmute — один VOL_UP; під час музики/дзвінка — гучність потоку телефону до нуля (setStreamSilent).
     */
    private fun toggleMute() {
        if (!k.ready || muteLocked) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val stream = when (k.profile) { 3 -> AudioManager.STREAM_MUSIC; 2 -> 6; else -> -1 }
        if (!k.muted) {
            Core.log("Mute (профіль ${k.profile})")
            if (stream < 0) {
                repeat(22) { pi(ComDex.VOL_DOWN) }
                muteLocked = true
                ui.postDelayed({ muteLocked = false; onChanged() }, 5000)
            } else repeat(15) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            k.muted = true
        } else {
            Core.log("Unmute (профіль ${k.profile})")
            if (stream < 0) pi(ComDex.VOL_UP) else am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            k.muted = false
        }
        onChanged()
    }

    private fun streaming() = k.profile == 2 || k.profile == 3 || k.profile == 4

    /** Як RoomCheckedChangeListener оригіналу: під час трансляції пишемо MICROPHONE_CONTROL 0/1. */
    private fun toggleMic() {
        if (!k.ready || !streaming()) return
        val on = k.mic == 0
        Core.log("Мікрофони слухових апаратів: ${if (on) "увімкнути" else "вимкнути"}")
        gatt.write(ComDex.MIC_CTRL, byteArrayOf(if (on) ComDex.MIC_ON else ComDex.MIC_OFF))
        gatt.read(ComDex.SERVICE, ComDex.MIC_CTRL)
        k.mic = if (on) 1 else 0
        onChanged()
    }

    private fun refreshMic() {
        val off = k.mic == 0
        micBtn.text = when {
            !streaming() -> "Мікрофони апаратів (лише під час трансляції)"
            off -> "Мікрофони апаратів вимкнено — увімкнути"
            else -> "Мікрофони апаратів увімкнено — вимкнути"
        }
        micBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(if (off && streaming()) R.drawable.ic_mic_off else R.drawable.ic_mic, 0, 0, 0)
        micBtn.isEnabled = k.ready && streaming() && k.charging == 0 && k.neckloop == 1
    }

    // ---------- Core.View ----------
    override fun onChanged() {
        status.text = k.status
        info.text = "Профіль: ${ComDex.PROFILE_NAMES[k.profile] ?: k.profile}\n" +
            "Neck loop: ${if (k.neckloop == 1) "закрито (увімкнено)" else "відкрито (вимкнено)"}\n" +
            "Прошивка: ${k.fw}   Серійний №: ${k.serial}"
        batteryView.level = k.battery
        batteryView.charging = k.charging == 1
        batteryText.text = "  Батарея COM-DEX: ${if (k.battery >= 0) "${k.battery} %" else "—"}${if (k.charging == 1) " (заряджається)" else ""}"
        updatingBoost = true
        boostSwitch.isChecked = k.boost == 2
        updatingBoost = false
        // як в оригіналі: при зарядці або відкритому neck loop — лише статус
        val enabled = k.ready && k.charging == 0 && k.neckloop == 1
        controls.forEach { it.isEnabled = enabled }
        if (k.fw != "?") boostSwitch.visibility = if (fwAtLeast(k.fw, "1.2.51")) View.VISIBLE else View.GONE
        muteBtn.text = when {
            muteLocked -> "🔇 Вимкнення звуку…"
            k.muted -> "🔇 Звук вимкнено — увімкнути"
            else -> "🔈 Вимкнути звук (Mute)"
        }
        muteBtn.isEnabled = enabled && !muteLocked
        progInfo.text = (if (k.program >= 0) "Поточна програма (…70002): ${k.program}   " else "") +
            "Перемикань за сеанс: ${k.progSwitches}"
        focusBtns.forEach { (cmd, b) -> b.backgroundTintList = focusTint(k.focus.contentEquals(cmd)) }
        refreshConnBtn()
        refreshMic()
    }

    override fun onLogLine(line: String) {
        if (::logView.isInitialized && debugPanel.visibility == View.VISIBLE) logView.text = k.logLines.reversed().joinToString("\n")
    }

    override fun onAuthLost() {
        AlertDialog.Builder(this).setTitle("Пару втрачено")
            .setMessage("COM-DEX більше не спарений з телефоном. Спаруйте його заново в налаштуваннях Bluetooth (відкрийте й закрийте neck loop), потім натисніть «Під'єднатись».")
            .setPositiveButton("Налаштування Bluetooth") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("Закрити", null)
            .show()
    }

    /** Фон кнопок фокусу: світла тема — світло-блакитний / обраний світло-зелений; темна — темно-синій / темно-зелений. */
    private fun focusTint(selected: Boolean): ColorStateList {
        val dark = Ui.isDark(this)
        val c = when {
            dark && selected -> 0xFF1B5E20.toInt()
            dark -> 0xFF0D2A5C.toInt()
            selected -> 0xFFB9F6CA.toInt()
            else -> 0xFFB3E5FC.toInt()
        }
        val disabled = (c and 0x00FFFFFF) or 0x66000000
        return ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, c))
    }

    private fun fwAtLeast(v: String, min: String): Boolean {
        val a = Regex("\\d+(\\.\\d+)+").find(v)?.value?.split('.')?.map { it.toInt() } ?: return true
        val m = min.split('.').map { it.toInt() }
        for (i in 0 until minOf(a.size, m.size)) if (a[i] != m[i]) return a[i] > m[i]
        return true
    }

    // ---------- допоміжне ----------
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 24, 0, 4) }
    private fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun btn(t: String, a: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); a() }
    }
    private fun ctl(t: String, a: () -> Unit) = btn(t, a).also { controls += it }
    private fun focusBtn(t: String, cmd: ByteArray) = ctl(t) { focus(cmd) }.also { focusBtns += cmd to it }

    /** Кнопка з авторепітом: одне спрацювання при натисканні, далі — кожні 250 мс, поки утримується. */
    private fun repeatCtl(t: String, a: () -> Unit): Button {
        val b = Button(this).apply { text = t; textSize = 22f }
        val tick = object : Runnable {
            override fun run() {
                if (!b.isPressed || !b.isEnabled) return
                b.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                a()
                ui.postDelayed(this, 250)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    a()
                    ui.postDelayed(tick, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ui.removeCallbacks(tick)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        controls += b
        return b
    }

    override fun onStop() {
        Core.fileLog.flush()
        super.onStop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(btStateRx) } catch (_: Exception) {}
        if (Core.view === this) Core.view = null
        ui.removeCallbacksAndMessages(null)
        Core.fileLog.flush()
        // з'єднання не рвемо: його тримає Core і foreground-сервіс; «Від'єднатись» — кнопкою або зі сповіщення
        super.onDestroy()
    }
}

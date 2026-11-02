package ua.controldex

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), GattClient.Listener {

    /** Те, що має пережити перестворення Activity (зміна теми, поворот): з'єднання й стан COM-DEX. */
    private class Kept(val gatt: GattClient) {
        var profile = -1
        var charging = 0
        var neckloop = 1
        var battery = -1
        var mic = -1
        var boost = -1
        var fw = "?"
        var serial = "?"
        var ready = false
        var status = "Відключено"
        val logLines = ArrayDeque<String>()
    }

    private lateinit var k: Kept
    private val gatt get() = k.gatt
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var batteryView: BatteryView
    private lateinit var batteryText: TextView
    private lateinit var micBtn: Button
    private lateinit var debugPanel: LinearLayout
    private lateinit var menuAnchor: View
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private lateinit var boostSwitch: Switch
    private val controls = mutableListOf<View>()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var fileLog: FileLog
    private val prefs by lazy { Ui.prefs(this) }
    private var updatingBoost = false
    private var themeAtCreate = -1
    private var darkAtCreate = false
    private val dp by lazy { resources.displayMetrics.density }

    /** Системні події BT: допомагають зіставити заїкання з A2DP/HFP/станом адаптера. */
    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            val st = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
            val prev = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            log("SYS ${i.action?.substringAfterLast('.')} state=$st prev=$prev" +
                if (i.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) " adapter=${i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)}" else "")
            if (i.action == android.bluetooth.BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED && st == android.bluetooth.BluetoothA2dp.STATE_PLAYING) gatt.logAudio("play")
        }
    }

    private val batteryTick = object : Runnable {
        override fun run() {
            if (k.ready) gatt.read(ComDex.BATTERY_SVC, ComDex.BATTERY_LVL)
            ui.postDelayed(this, 3 * 60_000L)
        }
    }

    override fun onCreate(b: Bundle?) {
        Ui.applyTheme(this)
        themeAtCreate = Ui.themeMode(this)
        darkAtCreate = Ui.isDark(this)
        super.onCreate(b)
        fileLog = FileLog(this)
        @Suppress("DEPRECATION")
        val kept = lastNonConfigurationInstance as? Kept
        k = kept ?: Kept(GattClient(this, this))
        gatt.listener = this
        if (kept == null) {
            // старі A/B-налаштування налагодження; GREEN тепер типовий режим
            gatt.skipReboot = prefs.getBoolean("skipReboot", false)
            gatt.noNotify = prefs.getBoolean("noNotify", false)
            gatt.greenMode = prefs.getBoolean("greenMode", true)
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, f, RECEIVER_EXPORTED) else registerReceiver(btReceiver, f)
        if (kept == null) log("=== ControlDex v${Ui.VERSION} запущено ===")
        title = "ControlDex"
        buildUi()
        refreshInfo()
        logView.text = k.logLines.reversed().joinToString("\n")
        if (k.ready) ui.postDelayed(batteryTick, 3 * 60_000L)
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

        status = TextView(this).apply { textSize = 18f; text = k.status }
        info = TextView(this)
        root.addView(status)
        root.addView(info)

        val row = LinearLayout(this)
        row.addView(btn("Підключитися") { connect() }, lp())
        row.addView(btn("Відключитися") { gatt.disconnect() }, lp())
        root.addView(row)

        val bat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16, 0, 0) }
        batteryView = BatteryView(this)
        batteryText = TextView(this).apply { textSize = 18f; text = "  Батарея COM-DEX: —" }
        bat.addView(batteryView)
        bat.addView(batteryText)
        root.addView(bat)

        root.addView(label("Гучність"))
        val vol = LinearLayout(this)
        vol.addView(ctl("−") { volume(-1) }, lp())
        vol.addView(ctl("+") { volume(+1) }, lp())
        root.addView(vol)

        // як кнопка room в оригіналі: мікрофони слухових апаратів під час трансляції
        micBtn = btn("") { toggleMic() }.apply { setAllCaps(false); compoundDrawablePadding = (8 * dp).toInt() }
        root.addView(micBtn)

        root.addView(label("Програма"))
        val prog = LinearLayout(this)
        prog.addView(ctl("Наступна програма") { pi(ComDex.NEXT_PROG) }, lp())
        prog.addView(ctl("Zen+") { pi(ComDex.ZEN_TOGGLE) }, lp())
        root.addView(prog)

        root.addView(label("Фокус"))
        val r1 = LinearLayout(this)
        r1.addView(ctl("▲ Вперед") { focus(ComDex.FOCUS_FRONT) }, lp())
        val r2 = LinearLayout(this)
        r2.addView(ctl("◀ Ліво") { focus(ComDex.FOCUS_LEFT) }, lp())
        r2.addView(ctl("● Omni") { focus(ComDex.FOCUS_OMNI) }, lp())
        r2.addView(ctl("Право ▶") { focus(ComDex.FOCUS_RIGHT) }, lp())
        val r3 = LinearLayout(this)
        r3.addView(ctl("▼ Назад") { focus(ComDex.FOCUS_REAR) }, lp())
        root.addView(r1)
        root.addView(r2)
        root.addView(r3)

        boostSwitch = Switch(this).apply {
            text = "Boost COM-DEX media volume"
            isChecked = k.boost == 2
            setOnCheckedChangeListener { _, on ->
                if (!updatingBoost && k.ready) {
                    gatt.write(ComDex.BOOST, byteArrayOf(if (on) 2 else 0))
                    gatt.read(ComDex.SERVICE, ComDex.BOOST)
                }
            }
        }
        controls += boostSwitch
        root.addView(boostSwitch)

        // ---------- налагодження: усе від «Заїкається!» донизу ----------
        debugPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(debugPanel)
        debugPanel.addView(btn("⚠ Заїкається!") { log("=== MARK: заїкання звуку ===") })
        debugPanel.addView(label("Тестові режими (діють з наступного «Підключитися»)"))
        debugPanel.addView(check("Режим GREEN: GATT через класичний Bluetooth (BR/EDR)", "greenMode", true) { gatt.greenMode = it })
        debugPanel.addView(check("Без нотифікацій (як nRF Connect)", "noNotify", false) { gatt.noNotify = it })
        debugPanel.addView(check("Не слати BLE_Reb (WDXAPP:2), лише для BLE", "skipReboot", false) { gatt.skipReboot = it })
        debugPanel.addView(btn("Оновити стан COM-DEX") { if (k.ready) gatt.refreshState() })
        val logRow = LinearLayout(this)
        logRow.addView(btn("Копіювати журнал") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", fileLog.tail(200_000)))
        }, lp())
        logRow.addView(btn("Поділитися (zip)") { shareLog() }, lp())
        debugPanel.addView(logRow)
        debugPanel.addView(TextView(this).apply { textSize = 11f; text = "Файл журналу: ${fileLog.file.absolutePath}" })
        debugPanel.addView(label("Журнал"))
        logView = TextView(this).apply { textSize = 11f }
        debugPanel.addView(logView)
        applyDebug()
    }

    private fun check(text: String, key: String, def: Boolean, set: (Boolean) -> Unit) = android.widget.CheckBox(this).apply {
        this.text = text
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); set(on); log("Опція: $key = $on") }
    }

    private fun applyDebug() {
        val on = Ui.debug(this)
        debugPanel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) logView.text = k.logLines.reversed().joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        if (Ui.themeMode(this) != themeAtCreate || Ui.isDark(this) != darkAtCreate) { recreate(); return }
        applyDebug()
    }

    override fun onRetainNonConfigurationInstance(): Any = k

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
    private fun connect() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onState("Увімкніть Bluetooth")
            return
        }
        val devs = adapter.bondedDevices.filter { it.name?.contains("COM-DEX") == true }
        when {
            devs.isEmpty() -> {
                onState("COM-DEX не спарено. Спаруйте в налаштуваннях Bluetooth (відкрийте й закрийте neck loop).")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            devs.size == 1 -> start(devs[0])
            else -> AlertDialog.Builder(this).setTitle("Оберіть пристрій")
                .setItems(devs.map { "${it.name} ${it.address}" }.toTypedArray()) { _, i -> start(devs[i]) }
                .show()
        }
    }

    private fun start(d: BluetoothDevice) {
        log("connect ${d.name} ${d.address}")
        gatt.connect(d)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) connect() else onState("Потрібен дозвіл Bluetooth")
    }

    private fun shareLog() {
        val zip = fileLog.makeZip(cacheDir)
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
    }

    /** Як в оригіналі: Idle -> PI_COMMAND; A2DP -> гучність медіа; HFP -> гучність SCO. */
    private fun volume(dir: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val d = if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        try {
            when (k.profile) {
                3 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
                2 -> am.adjustStreamVolume(6, d, AudioManager.FLAG_SHOW_UI)
                else -> pi(if (dir > 0) ComDex.VOL_UP else ComDex.VOL_DOWN)
            }
        } catch (e: Exception) {
            log("volume: ${e.message}")
        }
    }

    private fun streaming() = k.profile == 2 || k.profile == 3 || k.profile == 4

    /** Як RoomCheckedChangeListener оригіналу: під час трансляції пишемо MICROPHONE_CONTROL 0/1. */
    private fun toggleMic() {
        if (!k.ready || !streaming()) return
        val on = k.mic == 0
        log("Мікрофони слухових апаратів: ${if (on) "увімкнути" else "вимкнути"}")
        gatt.write(ComDex.MIC_CTRL, byteArrayOf(if (on) ComDex.MIC_ON else ComDex.MIC_OFF))
        gatt.read(ComDex.SERVICE, ComDex.MIC_CTRL)
        k.mic = if (on) 1 else 0
        refreshMic()
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

    // ---------- GattClient.Listener ----------
    override fun onState(text: String) {
        k.status = text
        status.text = text
        if (!gatt.connected) {
            k.ready = false
            refreshInfo()
        }
    }

    override fun onReady() {
        k.ready = true
        log("READY")
        refreshInfo()
        ui.removeCallbacks(batteryTick)
        ui.postDelayed(batteryTick, 3 * 60_000L)
    }

    override fun onValue(char: UUID, value: ByteArray) {
        val v = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        when (char) {
            ComDex.PROFILE -> k.profile = v
            ComDex.MIC_CTRL -> k.mic = v
            ComDex.CHARGING -> k.charging = v
            ComDex.NECKLOOP -> k.neckloop = v
            ComDex.BATTERY_LVL -> k.battery = v
            ComDex.BOOST -> k.boost = v
            ComDex.FW_REV -> k.fw = String(value)
            ComDex.SERIAL -> k.serial = String(value)
        }
        log("${char.toString().takeLast(8)} = ${value.joinToString(" ") { "%02X".format(it) }}")
        refreshInfo()
    }

    override fun onLog(line: String) = log(line)

    private fun refreshInfo() {
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
        setControlsEnabled(k.ready && k.charging == 0 && k.neckloop == 1)
        if (k.fw != "?") boostSwitch.visibility = if (fwAtLeast(k.fw, "1.2.51")) View.VISIBLE else View.GONE
        refreshMic()
    }

    private fun fwAtLeast(v: String, min: String): Boolean {
        val a = Regex("\\d+(\\.\\d+)+").find(v)?.value?.split('.')?.map { it.toInt() } ?: return true
        val m = min.split('.').map { it.toInt() }
        for (i in 0 until minOf(a.size, m.size)) if (a[i] != m[i]) return a[i] > m[i]
        return true
    }

    // ---------- допоміжне ----------
    private fun setControlsEnabled(on: Boolean) = controls.forEach { it.isEnabled = on }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 24, 0, 4) }
    private fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
    private fun ctl(t: String, a: () -> Unit) = btn(t, a).also { controls += it }
    private fun log(s: String) {
        val line = "${timeFmt.format(Date())} $s"
        fileLog.add(line)
        k.logLines.addLast(line)
        while (k.logLines.size > 150) k.logLines.removeFirst()
        if (::logView.isInitialized && debugPanel.visibility == View.VISIBLE) logView.text = k.logLines.reversed().joinToString("\n")
    }

    override fun onStop() {
        fileLog.flush()
        super.onStop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        fileLog.flush()
        ui.removeCallbacks(batteryTick)
        if (!isChangingConfigurations) {
            gatt.disconnect()
            gatt.release()
        }
        super.onDestroy()
    }
}

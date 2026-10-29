package ua.controldex

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.UUID

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), GattClient.Listener {

    private lateinit var gatt: GattClient
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var boostSwitch: Switch
    private val controls = mutableListOf<View>()
    private val ui = Handler(Looper.getMainLooper())

    private var profile = -1
    private var charging = 0
    private var neckloop = 1
    private var battery = -1
    private var fw = "?"
    private var serial = "?"
    private var ready = false
    private var updatingBoost = false

    private val batteryTick = object : Runnable {
        override fun run() {
            if (ready) gatt.read(ComDex.BATTERY_SVC, ComDex.BATTERY_LVL)
            ui.postDelayed(this, 3 * 60_000L)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        gatt = GattClient(this, this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })

        status = TextView(this).apply { textSize = 18f; text = "Відключено" }
        info = TextView(this)
        root.addView(status)
        root.addView(info)

        val row = LinearLayout(this)
        row.addView(btn("Підключитися") { connect() }, lp())
        row.addView(btn("Відключитися") { gatt.disconnect() }, lp())
        root.addView(row)

        root.addView(label("Гучність"))
        val vol = LinearLayout(this)
        vol.addView(ctl("−") { volume(-1) }, lp())
        vol.addView(ctl("+") { volume(+1) }, lp())
        root.addView(vol)

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
            setOnCheckedChangeListener { _, on ->
                if (!updatingBoost && ready) {
                    gatt.write(ComDex.BOOST, byteArrayOf(if (on) 2 else 0))
                    gatt.read(ComDex.SERVICE, ComDex.BOOST)
                }
            }
        }
        controls += boostSwitch
        root.addView(boostSwitch)

        root.addView(label("Журнал"))
        logView = TextView(this).apply { textSize = 11f }
        root.addView(logView)
        setControlsEnabled(false)
    }

    // ---------- підключення ----------
    private fun connect() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Увімкніть Bluetooth"
            return
        }
        val devs = adapter.bondedDevices.filter { it.name?.contains("COM-DEX") == true }
        when {
            devs.isEmpty() -> {
                status.text = "COM-DEX не спарено. Спаруйте в налаштуваннях Bluetooth (відкрийте й закрийте neck loop)."
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
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) connect() else status.text = "Потрібен дозвіл Bluetooth"
    }

    // ---------- команди ----------
    private fun pi(cmd: ByteArray) {
        if (ready) gatt.write(ComDex.PI_CMD, cmd)
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
            when (profile) {
                3 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
                2 -> am.adjustStreamVolume(6, d, AudioManager.FLAG_SHOW_UI)
                else -> pi(if (dir > 0) ComDex.VOL_UP else ComDex.VOL_DOWN)
            }
        } catch (e: Exception) {
            log("volume: ${e.message}")
        }
    }

    // ---------- GattClient.Listener ----------
    override fun onState(text: String) {
        status.text = text
        if (!gatt.connected) {
            ready = false
            setControlsEnabled(false)
        }
    }

    override fun onReady() {
        ready = true
        log("READY")
        refreshInfo()
        ui.removeCallbacks(batteryTick)
        ui.postDelayed(batteryTick, 3 * 60_000L)
    }

    override fun onValue(char: UUID, value: ByteArray) {
        val v = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        when (char) {
            ComDex.PROFILE -> profile = v
            ComDex.CHARGING -> charging = v
            ComDex.NECKLOOP -> neckloop = v
            ComDex.BATTERY_LVL -> battery = v
            ComDex.BOOST -> {
                updatingBoost = true
                boostSwitch.isChecked = v == 2
                updatingBoost = false
            }
            ComDex.FW_REV -> fw = String(value)
            ComDex.SERIAL -> serial = String(value)
        }
        log("${char.toString().takeLast(8)} = ${value.joinToString(" ") { "%02X".format(it) }}")
        refreshInfo()
    }

    override fun onLog(line: String) = log(line)

    private fun refreshInfo() {
        info.text = "Профіль: ${ComDex.PROFILE_NAMES[profile] ?: profile}\n" +
            "Батарея COM-DEX: ${if (battery >= 0) "$battery %" else "?"}${if (charging == 1) " (заряджається)" else ""}\n" +
            "Neck loop: ${if (neckloop == 1) "закрито (увімкнено)" else "відкрито (вимкнено)"}\n" +
            "Прошивка: $fw   Серійний №: $serial"
        // як в оригіналі: при зарядці або відкритому neck loop — лише статус
        setControlsEnabled(ready && charging == 0 && neckloop == 1)
        if (fw != "?") boostSwitch.visibility = if (fwAtLeast(fw, "1.2.51")) View.VISIBLE else View.GONE
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
        logView.text = (s + "\n" + logView.text).take(2000)
    }

    override fun onDestroy() {
        ui.removeCallbacks(batteryTick)
        gatt.disconnect()
        super.onDestroy()
    }
}

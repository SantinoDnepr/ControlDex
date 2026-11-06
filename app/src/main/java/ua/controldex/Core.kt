package ua.controldex

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Стан застосунку на весь час життя процесу: GATT-клієнт, останні значення COM-DEX і журнал.
 * Екран лише показує цей стан, тож закриття/перестворення Activity не рве з'єднання,
 * а foreground-сервіс (ComDexService) не дає системі вбити процес у фоні.
 * Сервіс (і сповіщення в шторці) вмикається лише кнопкою «Під'єднатись» і вимикається «Від'єднатись».
 */
@SuppressLint("StaticFieldLeak", "MissingPermission")
object Core : GattClient.Listener {

    /** Те, що слухає екран. */
    interface View {
        fun onChanged()
        fun onLogLine(line: String)
        fun onAuthLost() {}
    }

    private lateinit var app: Context
    lateinit var gatt: GattClient
        private set
    lateinit var fileLog: FileLog
        private set
    var view: View? = null

    var profile = -1
    var charging = 0
    var neckloop = 1
    var battery = -1
    var mic = -1
    var boost = -1
    var program = -1
    var fw = "?"
    var serial = "?"
    var ready = false
    var status = "Відключено"
    /** Локальний стан: останній вибраний напрямок фокусу і кількість перемикань програми за сеанс. */
    var focus: ByteArray? = null
    var progSwitches = 0
    var muted = false
    /** Автопідключення при запуску робимо лише раз за життя процесу. */
    var autoTried = false
    val logLines = ArrayDeque<String>()

    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val batteryTick = object : Runnable {
        override fun run() {
            if (ready) gatt.read(ComDex.BATTERY_SVC, ComDex.BATTERY_LVL)
            ui.postDelayed(this, 3 * 60_000L)
        }
    }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        fileLog = FileLog(app)
        gatt = GattClient(app, this)
        log("=== ControlDex v${Ui.VERSION} запущено ===")
    }

    /** [background] — увімкнути роботу у фоні (foreground-сервіс зі сповіщенням); лише за явною дією користувача. */
    fun connect(d: BluetoothDevice, background: Boolean) {
        Ui.prefs(app).edit().putString(Ui.KEY_LAST_MAC, d.address).apply()
        gatt.greenMode = Ui.greenMode(app)
        log("connect ${d.name} ${d.address}${if (background) " (у фоні)" else ""}")
        if (background) ComDexService.start(app)
        gatt.connect(d)
    }

    fun disconnect() {
        gatt.disconnect()
        ComDexService.stop(app)
    }

    /** Спарений COM-DEX з останньою запам'ятованою адресою (для автопідключення). */
    fun lastDevice(): BluetoothDevice? {
        val mac = Ui.prefs(app).getString(Ui.KEY_LAST_MAC, null) ?: return null
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val a = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return null
        if (!a.isEnabled) return null
        return try { a.bondedDevices.firstOrNull { it.address == mac } } catch (_: Exception) { null }
    }

    /** Автопідключення до останнього COM-DEX під час запуску застосунку — без фонової роботи й сповіщення. */
    fun autoConnect(): Boolean {
        if (autoTried || gatt.wantConnected || !Ui.autoConnect(app)) return false
        autoTried = true
        val d = lastDevice() ?: return false
        log("Автопідключення до ${d.address}")
        connect(d, background = false)
        return true
    }

    /** Система перезапустила вбитий процес, коли працював сервіс: відновлюємо з'єднання з останнім COM-DEX. */
    fun restoreBackground(): Boolean {
        if (gatt.wantConnected) return true
        autoTried = true
        val d = lastDevice() ?: return false
        log("Відновлення з'єднання у фоні з ${d.address}")
        connect(d, background = false)
        return true
    }

    fun log(s: String) {
        val line = "${timeFmt.format(Date())} $s"
        fileLog.add(line)
        logLines.addLast(line)
        while (logLines.size > 150) logLines.removeFirst()
        view?.onLogLine(line)
    }

    // ---------- GattClient.Listener ----------
    override fun onState(text: String) {
        status = text
        if (!gatt.connected) ready = false
        if (!gatt.wantConnected && !gatt.connected) ComDexService.stop(app)
        ComDexService.update(app)
        view?.onChanged()
    }

    override fun onReady() {
        ready = true
        status = "Підключено"
        log("READY")
        ComDexService.update(app)
        ui.removeCallbacks(batteryTick)
        ui.postDelayed(batteryTick, 3 * 60_000L)
        view?.onChanged()
    }

    override fun onValue(char: UUID, value: ByteArray) {
        val v = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        when (char) {
            ComDex.PROFILE -> { if (v != profile) muted = false; profile = v }
            ComDex.MIC_CTRL -> mic = v
            ComDex.CHARGING -> charging = v
            ComDex.NECKLOOP -> neckloop = v
            ComDex.BATTERY_LVL -> { battery = v; ComDexService.update(app) }
            ComDex.BOOST -> boost = v
            ComDex.SELECT_PROGRAM -> program = v
            ComDex.FW_REV -> fw = String(value)
            ComDex.SERIAL -> serial = String(value)
        }
        log("${char.toString().takeLast(8)} = ${value.joinToString(" ") { "%02X".format(it) }}")
        view?.onChanged()
    }

    override fun onLog(line: String) = log(line)

    override fun onAuthLost() {
        ComDexService.stop(app)
        view?.onAuthLost()
    }
}

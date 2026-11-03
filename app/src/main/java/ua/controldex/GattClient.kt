package ua.controldex

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * GATT-клієнт COM-DEX з послідовною чергою операцій (Android допускає лише одну незавершену).
 * Живе весь час існування процесу (див. Core), тож переживає закриття екрана.
 * Усі зміни стану відбуваються в головному потоці: колбеки GATT і системні події перекидаються в ui.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class GattClient(context: Context, var listener: Listener) {
    private val ctx: Context = context.applicationContext

    interface Listener {
        fun onState(text: String)
        fun onReady()
        fun onValue(char: UUID, value: ByteArray)
        fun onLog(line: String)
        /** Пару з COM-DEX втрачено (bond зник або помилка автентифікації). */
        fun onAuthLost() {}
    }

    private val ui = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var gatt: BluetoothGatt? = null
    private var userDisconnect = false
    var connected = false
        private set
    /** Режим GREEN оригіналу: GATT через класичний Bluetooth (BR/EDR), без окремого BLE-каналу. */
    var greenMode = true
    /** Користувач хоче бути підключеним: після втрати зв'язку перепідключаємось, доки не натиснуто «Відключитися». */
    var wantConnected = false
        private set
    var target: BluetoothDevice? = null
        private set

    private var greenRebootSent = false
    private var profileRx: BroadcastReceiver? = null
    private var sysRx: BroadcastReceiver? = null
    private var attempt = 0
    private var initDone = false
    private var scanCb: ScanCallback? = null
    private var headset: BluetoothHeadset? = null
    private var a2dp: BluetoothA2dp? = null
    private val stageTimers = mutableListOf<Runnable>()
    private val seen = HashSet<String>()

    private var reconnectDelay = RECONNECT_MIN
    private var reconnectPending = false
    private val reconnectTask = Runnable { reconnectNow() }
    private val watchdog = Runnable { onWatchdog("GATT-операція без відповіді ${OP_TIMEOUT / 1000} с") }
    private val discoverWatchdog = Runnable { onWatchdog("пошук сервісів без відповіді ${DISCOVER_TIMEOUT / 1000} с") }

    companion object {
        const val RECONNECT_MIN = 2_000L
        const val RECONNECT_MAX = 60_000L
        const val OP_TIMEOUT = 5_000L
        const val DISCOVER_TIMEOUT = 15_000L
        /** COM-DEX рекламує 16-бітний сервіс 0x6F2D (видно у скані поруч із 180F і 111E). */
        val ADV_SERVICE: ParcelUuid = ParcelUuid.fromString("00006f2d-0000-1000-8000-00805f9b34fb")
        /** Статуси GATT, що означають проблему з парою/шифруванням. */
        val AUTH_STATUSES = setOf(5, 137)
    }

    private val adapter: BluetoothAdapter? get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ---------------------------------------------------------------- публічне API

    fun connect(device: BluetoothDevice) {
        userDisconnect = false
        wantConnected = true
        target = device
        reconnectDelay = RECONNECT_MIN
        cancelReconnect()
        resetFlow()
        log("Режим: ${if (greenMode) "GREEN (GATT через BR/EDR)" else "BLUE (GATT через BLE)"}")
        log("Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Пристрій ${device.name} ${device.address}, type=${device.type} (1 classic, 2 LE, 3 dual), bond=${device.bondState}")
        ensureHeadsetProxy()
        registerSysRx()
        begin()
    }

    /** Лише GATT-від'єднання; пара та A2DP/HFP не чіпаються. */
    fun disconnect() {
        userDisconnect = true
        wantConnected = false
        log("disconnect() викликано користувачем (connected=$connected)")
        logAudio("disconnect")
        cancelReconnect()
        cancelStages()
        stopScan()
        unregisterProfileRx()
        ui.removeCallbacks(discoverWatchdog)
        resetQueue()
        initDone = false
        gatt?.disconnect()
        if (!connected) { gatt?.close(); gatt = null; listener.onState("Відключено") }
    }

    /** Закрити проксі й приймачі (процес завершується). */
    fun release() {
        unregisterProfileRx()
        unregisterSysRx()
        try {
            val a = adapter
            headset?.let { a?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            a2dp?.let { a?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (_: Exception) {}
        headset = null; a2dp = null
    }

    fun read(svc: UUID, chr: UUID) = enqueue { find(svc, chr)?.let { gatt?.readCharacteristic(it) } ?: false }

    fun write(chr: UUID, value: ByteArray, svc: UUID = ComDex.SERVICE) = enqueue {
        val c = find(svc, chr) ?: return@enqueue false
        c.value = value
        gatt?.writeCharacteristic(c) ?: false
    }

    /** Ручне оновлення стану. */
    fun refreshState() {
        read(ComDex.BATTERY_SVC, ComDex.BATTERY_LVL)
        listOf(ComDex.PROFILE, ComDex.MIC_CTRL, ComDex.NECKLOOP, ComDex.CHARGING, ComDex.BOOST, ComDex.SELECT_PROGRAM).forEach { read(ComDex.SERVICE, it) }
    }

    /** Діагностика звуку: стан A2DP, кодек (прихований API, через рефлексію) і Wi-Fi. */
    fun logAudio(tag: String) {
        val d = target
        val a = a2dp
        if (a == null || d == null) log("[$tag] A2DP-проксі недоступне")
        else {
            val st = try { a.getConnectionState(d) } catch (e: Exception) { -1 }
            val playing = try { a.isA2dpPlaying(d) } catch (e: Exception) { false }
            var codec: String
            try {
                val cs = a.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a, d)
                val cfg = cs?.javaClass?.getMethod("getCodecConfig")?.invoke(cs)
                codec = cfg?.toString() ?: "null"
            } catch (e: Exception) { codec = "недоступно (${e.javaClass.simpleName})" }
            log("[$tag] A2DP conn=$st playing=$playing codec=$codec")
        }
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val i = wm.connectionInfo
            log("[$tag] WiFi enabled=${wm.isWifiEnabled} freq=${i?.frequency} МГц rssi=${i?.rssi}")
        } catch (e: Exception) { log("[$tag] WiFi: ${e.message}") }
    }

    // ---------------------------------------------------------------- потік підключення

    private fun resetFlow() {
        attempt = 0
        initDone = false
        greenRebootSent = false
    }

    private fun ensureHeadsetProxy() {
        if (headset != null && a2dp != null) return
        try {
            val a = adapter ?: return
            val l = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) headset = proxy as BluetoothHeadset
                    if (profile == BluetoothProfile.A2DP) a2dp = proxy as BluetoothA2dp
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) headset = null
                    if (profile == BluetoothProfile.A2DP) a2dp = null
                }
            }
            if (headset == null) a.getProfileProxy(ctx, l, BluetoothProfile.HEADSET)
            if (a2dp == null) a.getProfileProxy(ctx, l, BluetoothProfile.A2DP)
        } catch (e: Exception) { log("HFP/A2DP-проксі: ${e.message}") }
    }

    /** HFP vendor-команда, як в оригіналі: +ANDROID з аргументом WDXAPP:n (4 = BLE_Adv, 2 = BLE_Reb, 3 = BR_EDR_Reb). */
    private fun vendorCmd(arg: String) {
        val h = headset
        val d = target
        if (h == null || d == null) { log("HFP-проксі недоступне, +ANDROID $arg не надіслано"); return }
        val ok = try { h.sendVendorSpecificResultCode(d, "+ANDROID", arg) } catch (e: Exception) { log("+ANDROID $arg: ${e.message}"); false }
        log("HFP +ANDROID $arg -> $ok")
    }

    /**
     * BLUE: скан із фільтром по сервісу 0x6F2D і MAC; 1 с: BLE_Adv, 8 с: BLE_Reb, 45 с: autoConnect у фоні.
     * GREEN: див. beginGreen().
     */
    private fun begin() {
        cancelStages()
        stopScan()
        unregisterProfileRx()
        if (greenMode) { beginGreen(); return }
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            log("Немає дозволу BLUETOOTH_SCAN — підключаюсь напряму"); doConnect(); return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { log("BLE-сканер недоступний"); doConnect(); return }
        val t = target ?: return
        seen.clear()
        listener.onState("Пошук COM-DEX в ефірі (BLE)…")
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { ui.post { onSeen(r) } }
            override fun onScanFailed(code: Int) { ui.post { log("Скан не вдався, код $code"); stopScan(); cancelStages(); doConnect() } }
        }
        scanCb = cb
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ADV_SERVICE).build(),
            ScanFilter.Builder().setDeviceAddress(t.address).build(),
        )
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) { log("Скан: ${e.message}"); scanCb = null; doConnect(); return }
        stage(1000) {
            log("Не в ефірі за 1 с. Надсилаю BLE_Adv")
            listener.onState("COM-DEX не рекламується — команда BLE_Adv…")
            vendorCmd("WDXAPP:4")
        }
        stage(8000) {
            log("Усе ще не в ефірі (бачить ${seen.size}). Надсилаю BLE_Reb")
            listener.onState("Команда BLE_Reb (звук може коротко перерватись)…")
            vendorCmd("WDXAPP:2")
        }
        stage(45000) {
            log("Не знайдено за 45 с. Чекаю у фоні (autoConnect)")
            stopScan()
            doConnect(auto = true)
        }
    }

    /**
     * GREEN, як в оригіналі: без BLE-сканування. Спершу GATT через BR/EDR одразу
     * (COM-DEX міг залишитися в цьому режимі). Не вийшло — BR_EDR_Reb (WDXAPP:3), чекаємо,
     * поки COM-DEX перепідключить HFP і A2DP, і пробуємо знову.
     */
    private fun beginGreen() {
        listener.onState("Підключення GATT через класичний Bluetooth…")
        doConnect()
        stage(15000) {
            if (!connected) { log("GREEN: немає з'єднання за 15 с"); greenFallback() }
        }
    }

    private fun greenFallback() {
        if (!greenRebootSent) sendGreenReboot()
        else { closeGatt(); fail("GREEN не вдався: COM-DEX не відповідає по BR/EDR") }
    }

    private fun sendGreenReboot() {
        cancelStages()
        greenRebootSent = true
        closeGatt()
        log("GREEN: надсилаю BR_EDR_Reb (WDXAPP:3), COM-DEX перезапуститься в режимі BR/EDR")
        listener.onState("Перезапуск COM-DEX (звук коротко перерветься)…")
        registerProfileRx()
        vendorCmd("WDXAPP:3")
        stage(40000) {
            unregisterProfileRx()
            log("GREEN: HFP/A2DP не перепідключились за 40 с, пробую GATT усе одно")
            doConnect()
            stage(15000) { if (!connected) greenFallback() }
        }
    }

    /** Як в оригіналі (BluetoothProfileConnectionStateReceiver): GATT після того, як підключені і HFP, і A2DP. */
    private fun registerProfileRx() {
        unregisterProfileRx()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = target ?: return
                val st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val dev: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != d.address || st != BluetoothProfile.STATE_CONNECTED) return
                val hfp = try { headset?.getConnectionState(d) } catch (_: Exception) { null }
                val a2 = try { a2dp?.getConnectionState(d) } catch (_: Exception) { null }
                log("GREEN: профілі після перезапуску HFP=$hfp A2DP=$a2")
                if (hfp == BluetoothProfile.STATE_CONNECTED && a2 == BluetoothProfile.STATE_CONNECTED) {
                    unregisterProfileRx()
                    cancelStages()
                    log("GREEN: HFP і A2DP підключені, GATT через BR/EDR за 2 с")
                    stage(2000) { doConnect() }
                    stage(17000) { if (!connected) { log("GREEN: немає з'єднання за 15 с після перезапуску"); greenFallback() } }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (register(rx, f)) profileRx = rx
    }

    private fun unregisterProfileRx() {
        val rx = profileRx ?: return
        profileRx = null
        try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
    }

    private fun register(rx: BroadcastReceiver, f: IntentFilter): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(rx, f)
        true
    } catch (e: Exception) { log("receiver: ${e.message}"); false }

    private fun onSeen(r: ScanResult) {
        val t = target ?: return
        if (scanCb == null) return
        val name = r.scanRecord?.deviceName ?: r.device.name
        seen.add(r.device.address)
        val byAddr = r.device.address == t.address
        val byName = name?.contains("COM-DEX") == true
        if (!byAddr && !byName) return
        log("Скан: знайдено ${r.device.address} ім'я=$name rssi=${r.rssi}${if (byAddr) "" else " (інша адреса!)"}")
        stopScan(); cancelStages()
        doConnect(r.device)
    }

    private fun stage(delay: Long, run: () -> Unit) {
        val x = Runnable { run() }
        stageTimers += x
        ui.postDelayed(x, delay)
    }

    private fun cancelStages() { stageTimers.forEach { ui.removeCallbacks(it) }; stageTimers.clear() }

    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun doConnect(dev: BluetoothDevice? = target, auto: Boolean = false) {
        val d = dev ?: return
        attempt++
        gatt?.close(); gatt = null
        listener.onState(if (auto) "Очікування COM-DEX (autoConnect)…" else "Підключення до ${d.name}, спроба $attempt…")
        val tr = if (greenMode) BluetoothDevice.TRANSPORT_BREDR else BluetoothDevice.TRANSPORT_LE
        log("connectGatt(${if (greenMode) "BR/EDR" else "LE"}, autoConnect=$auto) ${d.address}, спроба $attempt")
        gatt = d.connectGatt(ctx, auto, cb, tr)
    }

    /** Закрити GATT без очікування колбека (після close колбеків уже не буде). */
    private fun closeGatt() {
        ui.removeCallbacks(discoverWatchdog)
        gatt?.let { try { it.disconnect() } catch (_: Exception) {}; it.close() }
        gatt = null
        connected = false
        resetQueue()
    }

    private fun statusName(s: Int) = when (s) {
        0 -> "OK"; 5 -> "INSUFFICIENT_AUTHENTICATION"; 8 -> "TIMEOUT"; 19 -> "PEER_TERMINATED"; 22 -> "LOCAL_TERMINATED"
        62 -> "FAILED_TO_ESTABLISH"; 133 -> "GATT_ERROR"; 137 -> "AUTH_FAIL"; 257 -> "GATT_FAILURE"; else -> "?"
    }

    private fun log(s: String) = ui.post { listener.onLog(s) }

    // ---------------------------------------------------------------- перепідключення

    /** Невдача: якщо користувач хоче бути підключеним — повтор з експоненційною паузою, інакше лише статус. */
    private fun fail(msg: String) {
        if (wantConnected && !userDisconnect) scheduleReconnect(msg) else listener.onState(msg)
    }

    private fun scheduleReconnect(msg: String) {
        cancelStages()
        stopScan()
        unregisterProfileRx()
        if (!wantConnected || userDisconnect) { listener.onState(msg); return }
        log("$msg. Перепідключення через ${reconnectDelay / 1000} с")
        listener.onState("$msg. Перепідключення через ${reconnectDelay / 1000} с…")
        ui.removeCallbacks(reconnectTask)
        ui.postDelayed(reconnectTask, reconnectDelay)
        reconnectPending = true
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(RECONNECT_MAX)
    }

    private fun cancelReconnect() {
        ui.removeCallbacks(reconnectTask)
        reconnectPending = false
    }

    /** Подія (Bluetooth увімкнено, COM-DEX з'явився) — перепідключитись швидко, з мінімальною паузою. */
    private fun reconnectSoon(delay: Long, why: String) {
        if (!wantConnected || userDisconnect || connected) return
        log("$why — перепідключення за ${delay / 1000} с")
        reconnectDelay = RECONNECT_MIN
        ui.removeCallbacks(reconnectTask)
        ui.postDelayed(reconnectTask, delay)
        reconnectPending = true
    }

    private fun reconnectNow() {
        reconnectPending = false
        if (!wantConnected || userDisconnect || connected) return
        val d = target ?: return
        val a = adapter
        if (a == null || !a.isEnabled) { listener.onState("Bluetooth вимкнено — підключусь, коли його увімкнете"); return }
        if (d.bondState != BluetoothDevice.BOND_BONDED) { authLost("bond=${d.bondState}"); return }
        val h = headset; val a2 = a2dp
        if (h != null && a2 != null) {
            val hs = try { h.getConnectionState(d) } catch (_: Exception) { -1 }
            val as2 = try { a2.getConnectionState(d) } catch (_: Exception) { -1 }
            if (hs != BluetoothProfile.STATE_CONNECTED && as2 != BluetoothProfile.STATE_CONNECTED) {
                scheduleReconnect("COM-DEX поза зоною або вимкнений (HFP і A2DP не підключені)")
                return
            }
        }
        log("Перепідключення до ${d.address}")
        resetFlow()
        begin()
    }

    private fun authLost(why: String) {
        log("Пару втрачено ($why)")
        wantConnected = false
        cancelReconnect()
        cancelStages()
        stopScan()
        unregisterProfileRx()
        closeGatt()
        initDone = false
        listener.onState("Пару з COM-DEX втрачено — спаруйте заново в налаштуваннях Bluetooth")
        listener.onAuthLost()
    }

    // ---------------------------------------------------------------- системні події

    private fun registerSysRx() {
        if (sysRx != null) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onSystem(i)
        }
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }
        if (register(rx, f)) sysRx = rx
    }

    private fun unregisterSysRx() {
        val rx = sysRx ?: return
        sysRx = null
        try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
    }

    private fun onSystem(i: Intent) {
        val act = i.action ?: return
        val short = act.substringAfterLast('.')
        if (act == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            log("SYS адаптер state=$st")
            when (st) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    cancelReconnect(); cancelStages(); stopScan(); unregisterProfileRx()
                    closeGatt()
                    initDone = false
                    listener.onState(if (wantConnected) "Bluetooth вимкнено — підключусь, коли його увімкнете" else "Bluetooth вимкнено")
                }
                BluetoothAdapter.STATE_ON -> reconnectSoon(3000, "Bluetooth увімкнено")
            }
            return
        }
        val dev: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val mine = dev != null && dev.address == target?.address
        when (act) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (mine) {
                log("SYS ACL підключено (COM-DEX у зоні)")
                if (reconnectPending) reconnectSoon(4000, "COM-DEX з'явився")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (mine) {
                log("SYS ACL відключено (COM-DEX вимкнено, поза зоною або перезапуск)")
                if (!connected && reconnectPending) listener.onState("COM-DEX поза зоною або вимкнений — чекаю на нього…")
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (mine) {
                val b = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                log("SYS bond=$b")
                if (b == BluetoothDevice.BOND_NONE && wantConnected) authLost("пару видалено")
            }
            else -> {
                if (dev != null && !mine) return
                val st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val prev = i.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                log("SYS $short state=$st prev=$prev")
                if (act == BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED && st == BluetoothA2dp.STATE_PLAYING) logAudio("play")
            }
        }
    }

    // ---------------------------------------------------------------- черга GATT і watchdog

    private fun find(svc: UUID, chr: UUID) = gatt?.getService(svc)?.getCharacteristic(chr)

    private fun enableNotify(svc: UUID, chr: UUID) = enqueue {
        val c = find(svc, chr) ?: return@enqueue false
        gatt?.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(ComDex.CCCD) ?: return@enqueue false
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt?.writeDescriptor(d) ?: false
    }

    private fun enqueue(op: () -> Boolean) {
        ui.post { queue.addLast(op); pump() }
    }

    /** Операція, що не стартувала (немає характеристики), пропускається, як в оригіналі. */
    private fun pump() {
        if (busy) return
        while (queue.isNotEmpty()) {
            val op = queue.removeFirst()
            if (op()) {
                busy = true
                ui.removeCallbacks(watchdog)
                ui.postDelayed(watchdog, OP_TIMEOUT)
                return
            }
        }
    }

    private fun done() {
        ui.removeCallbacks(watchdog)
        busy = false
        pump()
    }

    private fun resetQueue() {
        ui.removeCallbacks(watchdog)
        queue.clear()
        busy = false
    }

    /** Завислa операція блокує всю чергу: скидаємо GATT і перепідключаємось. */
    private fun onWatchdog(why: String) {
        if (gatt == null) return
        log("Watchdog: $why — скидаю чергу й перепідключаюсь")
        closeGatt()
        initDone = false
        fail("Зв'язок із COM-DEX завис")
    }

    private fun initQueue() {
        // як в оригіналі й nRF Connect: параметри з'єднання не чіпаємо (requestConnectionPriority і RSSI-опитування ламали A2DP)
        logAudio("ready")
        read(ComDex.BATTERY_SVC, ComDex.BATTERY_LVL) // батарея першою: її видно одразу
        ComDex.NOTIFY_CHARS.forEach { enableNotify(ComDex.SERVICE, it) }
        listOf(ComDex.PROFILE, ComDex.MIC_CTRL, ComDex.NECKLOOP, ComDex.CHARGING, ComDex.BOOST).forEach { read(ComDex.SERVICE, it) }
        read(ComDex.DEVINFO_SVC, ComDex.SERIAL)
        read(ComDex.DEVINFO_SVC, ComDex.FW_REV)
        // оригінал …70002 (SELECT_PROGRAM) не читає; пробуємо — результат видно в журналі
        read(ComDex.SERVICE, ComDex.SELECT_PROGRAM)
        enqueue {
            initDone = true
            reconnectDelay = RECONNECT_MIN
            listener.onReady()
            false
        }
    }

    private fun discover(g: BluetoothGatt) {
        if (gatt !== g) return
        ui.removeCallbacks(discoverWatchdog)
        ui.postDelayed(discoverWatchdog, DISCOVER_TIMEOUT)
        if (!g.discoverServices()) log("discoverServices() повернув false")
    }

    // ---------------------------------------------------------------- колбеки GATT (binder-потік → ui)

    private fun onConn(g: BluetoothGatt, status: Int, newState: Int) {
        log("onConnectionStateChange status=$status (${statusName(status)}) newState=$newState userDisconnect=$userDisconnect")
        if (g !== gatt) { g.close(); return } // застарілий GATT
        if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
            connected = true
            cancelStages()
            listener.onState("З'єднано, пошук сервісів…")
            // спарений пристрій: сервіси без затримки; інакше даємо стеку 1 с на шифрування
            if (g.device.bondState == BluetoothDevice.BOND_BONDED) discover(g)
            else ui.postDelayed({ discover(g) }, 1000)
            return
        }
        val wasReady = initDone
        ui.removeCallbacks(discoverWatchdog)
        connected = false
        initDone = false
        logAudio("gatt-off")
        g.close(); gatt = null
        resetQueue()
        when {
            userDisconnect -> listener.onState("Відключено")
            status in AUTH_STATUSES -> authLost("статус $status")
            wasReady -> fail("З'єднання втрачено (код $status ${statusName(status)})")
            greenMode && !greenRebootSent -> { log("GREEN: GATT через BR/EDR не вдався (код $status)"); sendGreenReboot() }
            !greenMode && attempt < 4 -> { log("Невдача, повтор усього потоку через 1,5 с"); stage(1500) { if (!userDisconnect) begin() } }
            else -> fail("Не вдалося підключитись (код $status ${statusName(status)})")
        }
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        ui.removeCallbacks(discoverWatchdog)
        if (status == 0 && g.getService(ComDex.SERVICE)?.getCharacteristic(ComDex.PROFILE) != null) initQueue()
        else if (greenMode && !greenRebootSent) {
            log("GREEN: сервіс COM-DEX не видно через BR/EDR (status=$status)")
            sendGreenReboot()
        } else {
            wantConnected = false
            listener.onState("Пристрій не підтримується (немає сервісу COM-DEX)")
            disconnect()
        }
    }

    private fun onOpStatus(kind: String, c: UUID, status: Int) {
        if (status == 0) return
        log("$kind ${c.toString().takeLast(4)}: помилка $status (${statusName(status)})")
        if (status in AUTH_STATUSES) authLost("$kind, статус $status")
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            ui.post { onConn(g, status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ui.post { onServices(g, status) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value?.copyOf() ?: ByteArray(0)
            ui.post {
                if (g !== gatt) return@post
                if (status == 0) listener.onValue(c.uuid, v) else onOpStatus("read", c.uuid, status)
                if (gatt != null) done()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            ui.post {
                if (g !== gatt) return@post
                listener.onLog("write ${c.uuid.toString().takeLast(4)} status=$status")
                onOpStatus("write", c.uuid, status) // як в оригіналі: іншу помилку логуємо та йдемо далі
                if (gatt != null) done()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ui.post {
                if (g !== gatt) return@post
                onOpStatus("cccd", d.characteristic.uuid, status)
                if (gatt != null) done()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value?.copyOf() ?: ByteArray(0)
            ui.post { listener.onValue(c.uuid, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            ui.post { listener.onValue(c.uuid, value) }
        }
    }
}

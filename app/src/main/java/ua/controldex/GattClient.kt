package ua.controldex

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Мінімальний GATT-клієнт з послідовною чергою операцій (Android допускає лише одну незавершену). */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class GattClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onState(text: String)
        fun onReady()
        fun onValue(char: UUID, value: ByteArray)
        fun onLog(line: String)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var gatt: BluetoothGatt? = null
    private var userDisconnect = false
    var connected = false
        private set
    /** Не слати WDXAPP:2 (BLE_Reb): перезапуск BLE у COM-DEX може псувати класичне аудіо. */
    var skipReboot = false
    private val rssiTick = object : Runnable {
        override fun run() {
            if (connected) { gatt?.readRemoteRssi(); ui.postDelayed(this, 10_000) }
        }
    }

    private var target: BluetoothDevice? = null
    private var attempt = 0
    private var initDone = false
    private var scanCb: ScanCallback? = null
    private var headset: BluetoothHeadset? = null
    private val stageTimers = mutableListOf<Runnable>()
    private val seen = HashSet<String>()

    fun connect(device: BluetoothDevice) {
        userDisconnect = false
        target = device
        attempt = 0
        initDone = false
        log("Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Пристрій ${device.name} ${device.address}, type=${device.type} (1 classic, 2 LE, 3 dual), bond=${device.bondState}")
        ensureHeadsetProxy()
        begin()
    }

    private fun ensureHeadsetProxy() {
        if (headset != null) return
        try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getProfileProxy(ctx,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET) headset = proxy as BluetoothHeadset
                    }
                    override fun onServiceDisconnected(profile: Int) { if (profile == BluetoothProfile.HEADSET) headset = null }
                }, BluetoothProfile.HEADSET)
        } catch (e: Exception) { log("HFP-проксі: ${e.message}") }
    }

    /** HFP vendor-команда, як в оригіналі: +ANDROID з аргументом WDXAPP:n (4 = BLE_Adv, 2 = BLE_Reb). */
    private fun vendorCmd(arg: String) {
        val h = headset
        val d = target
        if (h == null || d == null) { log("HFP-проксі недоступне, +ANDROID $arg не надіслано"); return }
        val ok = try { h.sendVendorSpecificResultCode(d, "+ANDROID", arg) } catch (e: Exception) { log("+ANDROID $arg: ${e.message}"); false }
        log("HFP +ANDROID $arg -> $ok")
    }

    /**
     * COM-DEX рекламується по BLE лише після HFP-команди (як в оригіналі).
     * Скануємо без фільтра; 0 с: скан, 2 с: BLE_Adv, 8 с: BLE_Reb, 45 с: autoConnect у фоні.
     */
    private fun begin() {
        cancelStages()
        stopScan()
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            log("Немає дозволу BLUETOOTH_SCAN — підключаюсь напряму"); doConnect(); return
        }
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
        if (scanner == null) { log("BLE-сканер недоступний"); doConnect(); return }
        seen.clear()
        listener.onState("Пошук COM-DEX в ефірі (BLE)…")
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { ui.post { onSeen(r) } }
            override fun onScanFailed(code: Int) { ui.post { log("Скан не вдався, код $code"); stopScan(); cancelStages(); doConnect() } }
        }
        scanCb = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) { log("Скан: ${e.message}"); scanCb = null; doConnect(); return }
        stage(2000) {
            log("Не в ефірі (скан бачить ${seen.size} пристроїв). Надсилаю BLE_Adv")
            listener.onState("COM-DEX не рекламується — команда BLE_Adv…")
            vendorCmd("WDXAPP:4")
        }
        if (!skipReboot) stage(8000) {
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

    private fun onSeen(r: ScanResult) {
        val t = target ?: return
        if (scanCb == null) return
        val name = r.scanRecord?.deviceName ?: r.device.name
        seen.add(r.device.address)
        val byAddr = r.device.address == t.address
        val byName = name?.contains("COM-DEX") == true
        if (!byAddr && !byName) return
        log("Скан: знайдено ${r.device.address} ім'я=$name rssi=${r.rssi}${if (byAddr) "" else " (інша адреса!)"} uuids=${r.scanRecord?.serviceUuids}")
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
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun doConnect(dev: BluetoothDevice? = target, auto: Boolean = false) {
        val d = dev ?: return
        attempt++
        gatt?.close(); gatt = null
        listener.onState(if (auto) "Очікування COM-DEX (autoConnect)…" else "Підключення до ${d.name}, спроба $attempt…")
        log("connectGatt(LE, autoConnect=$auto) ${d.address}, спроба $attempt")
        gatt = d.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun statusName(s: Int) = when (s) {
        0 -> "OK"; 8 -> "TIMEOUT"; 19 -> "PEER_TERMINATED"; 22 -> "LOCAL_TERMINATED"
        62 -> "FAILED_TO_ESTABLISH"; 133 -> "GATT_ERROR"; 257 -> "GATT_FAILURE"; else -> "?"
    }

    private fun log(s: String) = ui.post { listener.onLog(s) }

    /** Лише GATT-від'єднання; пара та A2DP/HFP не чіпаються. */
    fun disconnect() {
        userDisconnect = true
        cancelStages()
        stopScan()
        queue.clear(); busy = false
        gatt?.disconnect()
        if (!connected) { gatt?.close(); gatt = null; listener.onState("Відключено") }
    }

    private fun find(svc: UUID, chr: UUID) = gatt?.getService(svc)?.getCharacteristic(chr)

    fun read(svc: UUID, chr: UUID) = enqueue { find(svc, chr)?.let { gatt?.readCharacteristic(it) } ?: false }

    fun write(chr: UUID, value: ByteArray, svc: UUID = ComDex.SERVICE) = enqueue {
        val c = find(svc, chr) ?: return@enqueue false
        c.value = value
        gatt?.writeCharacteristic(c) ?: false
    }

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
            if (op()) { busy = true; return }
        }
    }

    private fun done() { busy = false; ui.post { pump() } }

    private fun initQueue() {
        // не тримаємо агресивних параметрів з'єднання — менше конкуренції з A2DP
        log("requestConnectionPriority(BALANCED) -> ${gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)}")
        ui.removeCallbacks(rssiTick); ui.postDelayed(rssiTick, 10_000)
        ComDex.NOTIFY_CHARS.forEach { enableNotify(ComDex.SERVICE, it) }
        listOf(ComDex.PROFILE, ComDex.NECKLOOP, ComDex.CHARGING, ComDex.BOOST).forEach { read(ComDex.SERVICE, it) }
        read(ComDex.DEVINFO_SVC, ComDex.SERIAL)
        read(ComDex.DEVINFO_SVC, ComDex.FW_REV)
        read(ComDex.BATTERY_SVC, ComDex.BATTERY_LVL)
        enqueue { initDone = true; ui.post { listener.onReady() }; false }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status (${statusName(status)}) newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                connected = true
                ui.post { listener.onState("З'єднано, пошук сервісів…") }
                ui.postDelayed({ g.discoverServices() }, 1000)
            } else {
                connected = false
                ui.removeCallbacks(rssiTick)
                g.close(); if (gatt === g) gatt = null
                queue.clear(); busy = false
                ui.post {
                    if (!userDisconnect && !initDone && attempt < 4) {
                        log("Невдача, повтор усього потоку через 1,5 с")
                        ui.postDelayed({ if (!userDisconnect) begin() }, 1500)
                    } else {
                        listener.onState(if (userDisconnect) "Відключено" else "З'єднання втрачено (код $status ${statusName(status)})")
                    }
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            log("RSSI=$rssi status=$status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == 0 && g.getService(ComDex.SERVICE)?.getCharacteristic(ComDex.PROFILE) != null) {
                ui.post { initQueue() }
            } else {
                ui.post { listener.onState("Пристрій не підтримується (немає сервісу COM-DEX)"); disconnect() }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == 0) deliver(c.uuid, c.value ?: ByteArray(0))
            done()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            ui.post { listener.onLog("write ${c.uuid.toString().takeLast(4)} status=$status") }
            done() // як в оригіналі: помилку запису логуємо та йдемо далі
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = done()

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            deliver(c.uuid, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c.uuid, value)
        }

        private fun deliver(u: UUID, v: ByteArray) = ui.post { listener.onValue(u, v) }
    }
}

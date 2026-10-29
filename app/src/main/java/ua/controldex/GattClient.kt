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

    private var target: BluetoothDevice? = null
    private var attempt = 0
    private var initDone = false
    private var scanCb: ScanCallback? = null
    private val scanTimeout = Runnable { stopScan(); log("Не знайдено в BLE-ефірі за 8 с; пробую підключитись напряму"); doConnect() }

    fun connect(device: BluetoothDevice) {
        userDisconnect = false
        target = device
        attempt = 0
        initDone = false
        log("Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Пристрій ${device.name} ${device.address}, type=${device.type} (1 classic, 2 LE, 3 dual), bond=${device.bondState}")
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scan(device)
        else doConnect()
    }

    private fun scan(device: BluetoothDevice) {
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
        if (scanner == null) { log("BLE-сканер недоступний"); doConnect(); return }
        listener.onState("Пошук COM-DEX в ефірі (BLE)…")
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                ui.post {
                    if (scanCb == null) return@post
                    log("Скан: знайдено ${r.device.address} rssi=${r.rssi} uuids=${r.scanRecord?.serviceUuids} raw=${r.scanRecord?.bytes?.take(31)?.joinToString("") { "%02X".format(it) }}")
                    stopScan(); doConnect()
                }
            }
            override fun onScanFailed(code: Int) { ui.post { log("Скан не вдався, код $code"); stopScan(); doConnect() } }
        }
        scanCb = cb
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(device.address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            ui.postDelayed(scanTimeout, 8000)
        } catch (e: Exception) { log("Скан: ${e.message}"); scanCb = null; doConnect() }
    }

    private fun stopScan() {
        ui.removeCallbacks(scanTimeout)
        val cb = scanCb ?: return
        scanCb = null
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun doConnect() {
        val d = target ?: return
        attempt++
        gatt?.close(); gatt = null
        listener.onState("Підключення до ${d.name}, спроба $attempt…")
        log("connectGatt(LE), спроба $attempt")
        gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun statusName(s: Int) = when (s) {
        0 -> "OK"; 8 -> "TIMEOUT"; 19 -> "PEER_TERMINATED"; 22 -> "LOCAL_TERMINATED"
        62 -> "FAILED_TO_ESTABLISH"; 133 -> "GATT_ERROR"; 257 -> "GATT_FAILURE"; else -> "?"
    }

    private fun log(s: String) = ui.post { listener.onLog(s) }

    /** Лише GATT-від'єднання; пара та A2DP/HFP не чіпаються. */
    fun disconnect() {
        userDisconnect = true
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
                g.close(); if (gatt === g) gatt = null
                queue.clear(); busy = false
                ui.post {
                    if (!userDisconnect && !initDone && attempt < 5) {
                        log("Невдача, повтор через 2 с")
                        ui.postDelayed({ if (!userDisconnect) doConnect() }, 2000)
                    } else {
                        listener.onState(if (userDisconnect) "Відключено" else "З'єднання втрачено (код $status ${statusName(status)})")
                    }
                }
            }
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

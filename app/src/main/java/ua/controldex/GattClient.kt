package ua.controldex

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
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

    fun connect(device: BluetoothDevice) {
        userDisconnect = false
        listener.onState("Підключення до ${device.name}…")
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    /** Лише GATT-від'єднання; пара та A2DP/HFP не чіпаються. */
    fun disconnect() {
        userDisconnect = true
        queue.clear(); busy = false
        gatt?.disconnect()
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
        enqueue { ui.post { listener.onReady() }; false }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                connected = true
                ui.post { listener.onState("З'єднано, пошук сервісів…") }
                ui.postDelayed({ g.discoverServices() }, 1000)
            } else {
                connected = false
                g.close(); gatt = null; queue.clear(); busy = false
                ui.post {
                    listener.onState(if (userDisconnect) "Відключено" else "З'єднання втрачено (код $status)")
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

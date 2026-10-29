package ua.controldex

import java.util.UUID

/** GATT-профіль Widex COM-DEX (отримано реверсом офіційного застосунку 1.3.0). */
object ComDex {
    private fun u(s: String) = UUID.fromString(s)
    private fun w(suffix: String) = u("95986f2d-d705-4421-9890-2d32bff7$suffix")

    val SERVICE = w("0000")
    val MIC_CTRL = w("0003")
    val NECKLOOP = w("0004")
    val PROFILE = w("0005")
    val CHARGING = w("0007")
    val PI_CMD = w("0100")
    val BOOST = w("0202")
    val NOTIFY_CHARS = listOf(w("0001"), MIC_CTRL, NECKLOOP, PROFILE, CHARGING, BOOST)

    val BATTERY_SVC = u("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LVL = u("00002a19-0000-1000-8000-00805f9b34fb")
    val DEVINFO_SVC = u("0000180a-0000-1000-8000-00805f9b34fb")
    val SERIAL = u("00002a25-0000-1000-8000-00805f9b34fb")
    val FW_REV = u("00002a28-0000-1000-8000-00805f9b34fb")
    val CCCD = u("00002902-0000-1000-8000-00805f9b34fb")

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    val VOL_UP = hex("A2 00 02 80 00 27 76 7F")
    val VOL_DOWN = hex("A2 00 02 80 00 28 76 70")
    val NEXT_PROG = hex("A2 00 02 80 00 29 76 71")
    val ZEN_TOGGLE = hex("A2 00 02 80 00 2A 76 72")
    val ROOM_ON = hex("A2 01 40 A8 20 11 00 0A 08 00 04 00 16 64 8D")
    val FOCUS_OMNI = hex("A2 01 40 A8 20 11 00 10 00 00 00 00 13 64 86")
    val FOCUS_FRONT = hex("A2 01 40 A8 20 11 00 10 00 00 0C 10 13 1B 43")
    val FOCUS_REAR = hex("A2 01 40 A8 20 11 00 10 00 00 0D 10 13 A1 EE")
    val FOCUS_LEFT = hex("A2 01 40 A8 20 11 00 10 00 00 0E 10 13 D4 B4")
    val FOCUS_RIGHT = hex("A2 01 40 A8 20 11 00 10 00 00 0F 10 13 6E 19")

    val PROFILE_NAMES = mapOf(0 to "не підключено", 1 to "Idle", 2 to "HFP (дзвінок)", 3 to "A2DP (музика)", 4 to "Remote Mic")
}

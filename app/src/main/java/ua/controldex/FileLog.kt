package ua.controldex

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Журнал у файл порціями: рядки накопичуються в малому буфері й скидаються на диск
 * у фоновому потоці раз на кілька секунд або коли набралась порція. Файл ротується,
 * тому ні пам'ять, ні сховище не переповнюються.
 */
class FileLog(ctx: Context) {

    private val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val file = File(dir, "controldex.log")
    private val old = File(dir, "controldex.log.1")
    private val thread = HandlerThread("filelog").apply { start() }
    private val h = Handler(thread.looper)
    private val buf = ArrayList<String>()   // доступ лише з потоку h
    private val flushTask = Runnable { flushNow() }

    fun add(line: String) {
        h.post {
            buf.add(line)
            if (buf.size >= BATCH) { h.removeCallbacks(flushTask); flushNow() }
            else if (buf.size == 1) h.postDelayed(flushTask, FLUSH_MS)
        }
    }

    /** Скинути буфер негайно (onStop/onDestroy). */
    fun flush() { h.post { h.removeCallbacks(flushTask); flushNow() } }

    private fun flushNow() {
        if (buf.isEmpty()) return
        val chunk = buf.joinToString("\n", postfix = "\n")
        buf.clear()
        try {
            if (file.length() > MAX_BYTES) { old.delete(); file.renameTo(old) }
            FileOutputStream(file, true).use { it.write(chunk.toByteArray()) }
        } catch (_: Exception) {}
    }

    /** Останні ~maxBytes байт журналу (читається з кінця файлу, не весь файл). */
    fun tail(maxBytes: Int = 60_000): String {
        flushNow2()
        return try {
            RandomAccessFile(file, "r").use { f ->
                val n = minOf(f.length(), maxBytes.toLong()).toInt()
                f.seek(f.length() - n)
                val b = ByteArray(n); f.readFully(b)
                String(b)
            }
        } catch (_: Exception) { "" }
    }

    // синхронний скид для tail(): виконуємо на потоці журналу й чекаємо
    private fun flushNow2() {
        val l = java.util.concurrent.CountDownLatch(1)
        h.post { h.removeCallbacks(flushTask); flushNow(); l.countDown() }
        l.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    companion object {
        private const val BATCH = 20
        private const val FLUSH_MS = 3000L
        private const val MAX_BYTES = 512 * 1024L
    }
}

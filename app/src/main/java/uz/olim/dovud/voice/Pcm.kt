package uz.olim.dovud.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object Pcm {
    const val RATE = 16000

    fun rms(buf: ShortArray, n: Int = buf.size): Double {
        if (n == 0) return 0.0
        var s = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); s += v * v }
        return sqrt(s / n)
    }

    fun toBytes(pcm: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    fun wav(pcm: ShortArray, rate: Int = RATE): ByteArray {
        val data = toBytes(pcm)
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size)
        out.write(h.array()); out.write(data)
        return out.toByteArray()
    }

    fun durationSec(pcm: ShortArray) = pcm.size.toDouble() / RATE
}

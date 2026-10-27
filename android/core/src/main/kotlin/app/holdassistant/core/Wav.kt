package app.holdassistant.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    /** Parse a PCM16 WAV and resample to [targetRate] mono (linear interpolation). */
    fun toMonoPcm(bytes: ByteArray, targetRate: Int): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 0
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(bits == 16 && rate > 0) { "unsupported WAV: $bits-bit $rate Hz" }
                val end = minOf(bytes.size, body + if (size > 0) size else bytes.size)
                val frames = (end - body) / (2 * channels)
                val mono = FloatArray(frames) { f ->
                    var sum = 0f
                    for (c in 0 until channels) sum += b.getShort(body + (f * channels + c) * 2)
                    sum / channels
                }
                val outLen = (frames.toLong() * targetRate / rate).toInt()
                return ShortArray(outLen) { i ->
                    val src = i.toDouble() * rate / targetRate
                    val i0 = src.toInt().coerceAtMost(frames - 1)
                    val i1 = (i0 + 1).coerceAtMost(frames - 1)
                    val frac = (src - i0).toFloat()
                    (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk")
    }
}

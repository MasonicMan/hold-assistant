package app.holdassistant.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav(rate: Int, channels: Int, samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test fun resamplesAndDownmixes() {
        // 24 kHz stereo, 1 s of a constant level per channel
        val stereo = ShortArray(48000) { if (it % 2 == 0) 1000 else 3000 }
        val out = Wav.toMonoPcm(wav(24000, 2, stereo), 16000)
        assertEquals(16000, out.size)
        assertEquals(2000, out[500].toInt())
    }

    @Test fun passthroughAtSameRate() {
        val s = ShortArray(160) { (it * 10).toShort() }
        assertEquals(s.toList(), Wav.toMonoPcm(wav(16000, 1, s), 16000).toList())
    }
}

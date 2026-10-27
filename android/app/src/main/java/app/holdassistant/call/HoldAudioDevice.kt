package app.holdassistant.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Process
import android.util.Log
import com.twilio.voice.AudioDevice
import com.twilio.voice.AudioDeviceContext
import com.twilio.voice.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Twilio audio device that owns both directions of call audio.
 *
 * Remote audio (render) always goes to the transcriber and a ring buffer, and
 * only reaches the user's ears while [listening]. That way the user's own
 * music keeps playing during hold. Local audio (capture) is silence unless
 * listening, with probe speech injected over it when queued.
 */
class HoldAudioDevice(
    private val onRemoteAudio: (ShortArray) -> Unit,
) : AudioDevice {
    private val format = AudioFormat(SAMPLE_RATE, AudioFormat.AUDIO_SAMPLE_MONO)

    @Volatile var listening = false
        private set
    /** Mic muted by the probe or the user. Silence is sent instead. */
    @Volatile var micMuted = false

    private val ring = ShortArray(SAMPLE_RATE * RING_SECONDS)
    private var ringPos = 0
    private var ringFilled = 0

    private val playQueue = LinkedBlockingDeque<ShortArray>()
    private val inject = ConcurrentLinkedQueue<ShortArray>()

    @Volatile private var renderCtx: AudioDeviceContext? = null
    @Volatile private var captureCtx: AudioDeviceContext? = null
    private var renderThread: Thread? = null
    private var captureThread: Thread? = null
    private var playerThread: Thread? = null

    // --- Public controls ---

    /** Start sending call audio to the user's ears, replaying the last [replaySeconds] first. */
    fun startListening(replaySeconds: Int, chime: ShortArray?) {
        if (listening) return
        playQueue.clear()
        chime?.let { playQueue.add(it) }
        synchronized(ring) {
            val n = minOf(ringFilled, replaySeconds * SAMPLE_RATE)
            var start = (ringPos - n + ring.size) % ring.size
            var left = n
            while (left > 0) {
                val len = minOf(FRAME * 10, left, ring.size - start)
                playQueue.add(ring.copyOfRange(start, start + len))
                start = (start + len) % ring.size
                left -= len
            }
        }
        listening = true
        startPlayer()
    }

    fun stopListening() {
        listening = false
        playerThread?.interrupt()
        playerThread = null
        playQueue.clear()
    }

    /** Queue 16 kHz mono speech to send to the far end instead of the mic. */
    fun injectSpeech(pcm: ShortArray) {
        var i = 0
        while (i < pcm.size) {
            inject.add(pcm.copyOfRange(i, minOf(i + FRAME, pcm.size)))
            i += FRAME
        }
    }

    val injecting: Boolean get() = inject.isNotEmpty()

    // --- Renderer: remote audio coming from the call ---

    override fun getRendererFormat(): AudioFormat = format
    override fun onInitRenderer(): Boolean = true

    override fun onStartRendering(ctx: AudioDeviceContext): Boolean {
        renderCtx = ctx
        renderThread = Thread({ renderLoop() }, "hold-render").apply { start() }
        return true
    }

    override fun onStopRendering(): Boolean {
        renderCtx = null
        renderThread?.interrupt()
        renderThread = null
        stopListening()
        return true
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteBuffer.allocateDirect(FRAME * 2).order(ByteOrder.nativeOrder())
        var next = System.nanoTime()
        while (!Thread.currentThread().isInterrupted) {
            val ctx = renderCtx ?: break
            buf.clear()
            AudioDevice.audioDeviceReadRenderData(ctx, buf)
            buf.rewind()
            val frame = ShortArray(FRAME)
            buf.asShortBuffer().get(frame)
            synchronized(ring) {
                for (s in frame) {
                    ring[ringPos] = s
                    ringPos = (ringPos + 1) % ring.size
                }
                ringFilled = minOf(ring.size, ringFilled + FRAME)
            }
            onRemoteAudio(frame)
            if (listening) playQueue.add(frame)
            // Pace at real time: 10 ms per frame.
            next += FRAME_NANOS
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleep)
                } catch (_: InterruptedException) {
                    break
                }
            } else if (sleep < -200_000_000L) {
                next = System.nanoTime() // fell far behind (e.g. device asleep); resync
            }
        }
    }

    // --- Player: drains playQueue into an AudioTrack, catching up after a replay ---

    private fun startPlayer() {
        playerThread?.interrupt()
        playerThread = Thread({ playLoop() }, "hold-player").apply { start() }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AndroidAudioFormat.CHANNEL_OUT_MONO, AndroidAudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME * 2 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        var fast = false
        try {
            track.play()
            while (listening && !Thread.currentThread().isInterrupted) {
                val chunk = playQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val backlog = playQueue.size
                // Behind live (after a replay): skip near-silent frames, and play faster where supported.
                if (backlog > CATCH_UP_FRAMES && rms(chunk) < SILENCE_RMS) continue
                val wantFast = backlog > CATCH_UP_FRAMES
                if (wantFast != fast) {
                    fast = wantFast
                    try {
                        track.playbackParams = PlaybackParams().setSpeed(if (fast) 1.3f else 1.0f)
                    } catch (e: RuntimeException) {
                        Log.d(TAG, "playback speed not supported: $e")
                    }
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    // --- Capturer: what the far end hears ---

    override fun getCapturerFormat(): AudioFormat = format
    override fun onInitCapturer(): Boolean = true

    override fun onStartCapturing(ctx: AudioDeviceContext): Boolean {
        captureCtx = ctx
        captureThread = Thread({ captureLoop() }, "hold-capture").apply { start() }
        return true
    }

    override fun onStopCapturing(): Boolean {
        captureCtx = null
        captureThread?.interrupt()
        captureThread = null
        return true
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before a call starts
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteBuffer.allocateDirect(FRAME * 2).order(ByteOrder.nativeOrder())
        val frame = ShortArray(FRAME)
        var record: AudioRecord? = null
        var next = System.nanoTime()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val ctx = captureCtx ?: break
                // The mic is only opened while the user is on the line, so Bluetooth
                // stays in music mode during hold.
                if (listening && record == null) record = openMic()
                if (!listening && record != null) {
                    record.stop(); record.release(); record = null
                }
                val injected = inject.poll()
                val mic = record
                when {
                    injected != null -> {
                        frame.fill(0)
                        injected.copyInto(frame)
                        mic?.read(ShortArray(FRAME), 0, FRAME) // keep the mic drained
                    }
                    mic != null -> {
                        val n = mic.read(frame, 0, FRAME)
                        if (n < FRAME) frame.fill(0, maxOf(n, 0))
                        if (micMuted) frame.fill(0)
                    }
                    else -> frame.fill(0)
                }
                buf.clear()
                buf.asShortBuffer().put(frame)
                buf.rewind()
                AudioDevice.audioDeviceWriteCaptureData(ctx, buf)
                if (mic == null || injected != null) {
                    // No blocking mic read to set the pace.
                    next += FRAME_NANOS
                    val sleep = next - System.nanoTime()
                    if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep) else if (sleep < -200_000_000L) next = System.nanoTime()
                } else {
                    next = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            record?.let { runCatching { it.stop() }; it.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AndroidAudioFormat.CHANNEL_IN_MONO, AndroidAudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 8),
        ).also { it.startRecording() }
    } catch (e: RuntimeException) {
        Log.w(TAG, "mic unavailable", e)
        null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = SAMPLE_RATE / 100 // 10 ms
        private const val FRAME_NANOS = 10_000_000L
        private const val RING_SECONDS = 15
        private const val CATCH_UP_FRAMES = 30 // 300 ms behind live
        private const val SILENCE_RMS = 300.0
        private const val TAG = "HoldAudioDevice"

        fun rms(pcm: ShortArray): Double {
            if (pcm.isEmpty()) return 0.0
            var sum = 0.0
            for (s in pcm) sum += s.toDouble() * s
            return sqrt(sum / pcm.size)
        }

        /** Two short rising beeps, played before the call audio when an alert fires. */
        fun chime(): ShortArray {
            val tone = { hz: Double, ms: Int ->
                ShortArray(SAMPLE_RATE * ms / 1000) { i ->
                    val env = minOf(1.0, minOf(i, SAMPLE_RATE * ms / 1000 - i) / 160.0)
                    (Math.sin(2 * Math.PI * hz * i / SAMPLE_RATE) * 9000 * env).toInt().toShort()
                }
            }
            return tone(660.0, 140) + ShortArray(SAMPLE_RATE / 20) + tone(990.0, 180) + ShortArray(SAMPLE_RATE / 10)
        }
    }
}

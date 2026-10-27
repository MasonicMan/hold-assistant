package app.holdassistant.call

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/** Streams call audio into a transcriber and gets back utterances. */
interface Transcriber {
    fun start()
    fun feed(pcm: ShortArray)
    fun stop()
}

/**
 * Free transcription on the phone itself: Android's on-device recognizer fed
 * from a pipe (Android 13+), in segmented mode so it keeps going for the
 * whole call. Less accurate than Deepgram on phone audio, but needs no key.
 */
@RequiresApi(33)
class OnDeviceStt(
    private val context: Context,
    private val clockS: () -> Double,
    private val onSegment: (startS: Double, text: String) -> Unit,
    private val onInterim: (text: String) -> Unit,
    private val onError: (String) -> Unit,
) : Transcriber {
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ShortArray>(3000) // 30 s of 10 ms frames
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    private var recognizer: SpeechRecognizer? = null
    private var writer: Thread? = null
    private var segmentStart: Double? = null
    private var failures = 0

    override fun start() {
        stopped = false
        writer = Thread({ writeLoop() }, "stt-writer").apply { start() }
        main.post { startSession() }
    }

    override fun feed(pcm: ShortArray) {
        if (!stopped) queue.offer(pcm) // drops audio rather than stalling the call if the recognizer falls behind
    }

    override fun stop() {
        stopped = true
        writer?.interrupt()
        runCatching { out?.close() }
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun writeLoop() {
        val bytes = ByteBuffer.allocate(HoldAudioDevice.FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (!stopped) {
                val frame = queue.take()
                val o = out ?: continue
                bytes.clear()
                bytes.asShortBuffer().put(frame)
                try {
                    o.write(bytes.array(), 0, frame.size * 2)
                } catch (e: IOException) {
                    out = null // session ended; the next one opens a new pipe
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun intent(source: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, HoldAudioDevice.SAMPLE_RATE)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
    }

    private fun startSession() {
        if (stopped) return
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val (read, write) = ParcelFileDescriptor.createPipe()
        out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        r.startListening(intent(read))
        read.close() // the recognizer holds its own copy
    }

    private fun restart(delayMs: Long) {
        runCatching { out?.close() }
        out = null
        if (!stopped) main.postDelayed({ startSession() }, delayMs)
    }

    private fun first(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            failures = 0
        }

        override fun onBeginningOfSpeech() {
            if (segmentStart == null) segmentStart = clockS()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = first(partialResults)
            if (text.isNotEmpty()) {
                if (segmentStart == null) segmentStart = clockS()
                onInterim(text)
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) = emit(first(segmentResults))

        override fun onResults(results: Bundle?) {
            emit(first(results))
            restart(100)
        }

        override fun onEndOfSegmentedSession() = restart(100)

        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error $error")
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                    onError("Downloading the on-device English speech model. Transcription starts when it's ready.")
                    runCatching {
                        recognizer?.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US"))
                    }
                    restart(15_000)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Microphone permission is needed for transcription.")
                else -> {
                    failures++
                    if (failures == 5) onError("On-device transcription keeps failing (error $error). A Deepgram key in Settings is more reliable.")
                    restart(minOf(5000L, 300L * failures))
                }
            }
        }

        private fun emit(text: String) {
            onInterim("")
            val start = segmentStart ?: (clockS() - 2.0).coerceAtLeast(0.0)
            segmentStart = null
            if (text.isNotEmpty()) onSegment(start, text)
        }
    }

    companion object {
        private const val TAG = "OnDeviceStt"
    }
}

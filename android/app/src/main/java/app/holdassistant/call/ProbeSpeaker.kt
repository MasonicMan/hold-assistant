package app.holdassistant.call

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.holdassistant.core.Wav
import java.io.File
import java.util.Locale

/** Renders probe prompts with the phone's TTS engine into 16 kHz mono PCM. */
class ProbeSpeaker(context: Context) {
    private val cacheDir = context.cacheDir
    @Volatile private var ready = false
    private val callbacks = mutableMapOf<String, (ShortArray?) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}

            override fun onDone(utteranceId: String) {
                val file = File(cacheDir, "$utteranceId.wav")
                val pcm = runCatching { Wav.toMonoPcm(file.readBytes(), HoldAudioDevice.SAMPLE_RATE) }
                    .onFailure { Log.w(TAG, "could not read TTS output", it) }.getOrNull()
                file.delete()
                synchronized(callbacks) { callbacks.remove(utteranceId) }?.invoke(pcm)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                synchronized(callbacks) { callbacks.remove(utteranceId) }?.invoke(null)
            }
        })
    }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) runCatching { tts.language = Locale.US }
    }

    /** Synthesize [text]; [done] gets the PCM, or null if TTS is unavailable. */
    fun render(text: String, done: (ShortArray?) -> Unit) {
        if (!ready) {
            done(null)
            return
        }
        val id = "probe-${System.nanoTime()}"
        synchronized(callbacks) { callbacks[id] = done }
        val result = tts.synthesizeToFile(text, null, File(cacheDir, "$id.wav"), id)
        if (result != TextToSpeech.SUCCESS) synchronized(callbacks) { callbacks.remove(id) }?.invoke(null)
    }

    fun shutdown() = tts.shutdown()

    companion object {
        private const val TAG = "ProbeSpeaker"
    }
}

package app.holdassistant.call

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Streaming speech-to-text for the far end of the call via Deepgram's live
 * API. Emits one segment per utterance (when Deepgram detects the end of
 * speech) with its start time in seconds since the stream began.
 */
class DeepgramStt(
    private val apiKey: String,
    private val onSegment: (startS: Double, text: String) -> Unit,
    private val onInterim: (text: String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var reconnects = 0

    private val pending = ByteBuffer.allocate(HoldAudioDevice.SAMPLE_RATE / 10 * 2).order(ByteOrder.LITTLE_ENDIAN) // 100 ms
    private val finals = StringBuilder()
    private var utteranceStart: Double? = null
    private var sentSamples = 0L
    /** Deepgram times restart at 0 on reconnect; this maps them back to call time. */
    @Volatile private var timeOffsetS = 0.0

    fun start() {
        closed = false
        connect()
    }

    private fun connect() {
        timeOffsetS = synchronized(pending) { sentSamples.toDouble() / HoldAudioDevice.SAMPLE_RATE }
        val url = "wss://api.deepgram.com/v1/listen?model=nova-3&language=en-US&encoding=linear16" +
            "&sample_rate=${HoldAudioDevice.SAMPLE_RATE}&channels=1&interim_results=true&smart_format=true" +
            "&endpointing=400&utterance_end_ms=1200"
        val req = Request.Builder().url(url).header("Authorization", "Token $apiKey").build()
        socket = client.newWebSocket(req, Listener())
    }

    /** Feed 16 kHz mono PCM; sent in 100 ms chunks. */
    fun feed(pcm: ShortArray) {
        val ws = socket ?: return
        synchronized(pending) {
            for (s in pcm) {
                pending.putShort(s)
                if (!pending.hasRemaining()) {
                    pending.flip()
                    ws.send(pending.toByteString())
                    pending.clear()
                }
            }
            sentSamples += pcm.size
        }
    }

    fun stop() {
        closed = true
        socket?.send("{\"type\":\"CloseStream\"}")
        socket?.close(1000, "call ended")
        socket = null
    }

    private fun flushUtterance() {
        val text = finals.toString().trim()
        val start = utteranceStart
        finals.clear()
        utteranceStart = null
        if (text.isNotEmpty() && start != null) onSegment(start, text)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "Results" -> {
                    val transcript = msg.optJSONObject("channel")?.optJSONArray("alternatives")
                        ?.optJSONObject(0)?.optString("transcript").orEmpty()
                    if (msg.optBoolean("is_final")) {
                        if (transcript.isNotBlank()) {
                            if (utteranceStart == null) utteranceStart = timeOffsetS + msg.optDouble("start", 0.0)
                            finals.append(transcript).append(' ')
                        }
                        onInterim("")
                        if (msg.optBoolean("speech_final")) flushUtterance()
                    } else if (transcript.isNotBlank()) {
                        onInterim((finals.toString() + transcript).trim())
                    }
                }
                "UtteranceEnd" -> flushUtterance()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Deepgram socket failed (${response?.code})", t)
            if (closed) return
            if (response?.code == 401) {
                onError("Deepgram rejected the API key")
                return
            }
            if (reconnects++ < 5) {
                Thread.sleep(1000L * reconnects)
                if (!closed) connect()
            } else {
                onError("Transcription disconnected: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "DeepgramStt"
    }
}

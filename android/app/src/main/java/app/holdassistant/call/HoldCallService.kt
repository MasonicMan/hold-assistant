package app.holdassistant.call

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.holdassistant.HoldApp
import app.holdassistant.R
import app.holdassistant.core.AlertTracker
import app.holdassistant.core.ClaudeLineTier
import app.holdassistant.core.Classifier
import app.holdassistant.core.Decision
import app.holdassistant.core.Fingerprint
import app.holdassistant.core.LineState
import app.holdassistant.core.MenuOptions
import app.holdassistant.core.PhoneNumbers
import app.holdassistant.core.TwilioToken
import app.holdassistant.core.ProbeController
import app.holdassistant.core.Segment
import app.holdassistant.core.claudeClient
import app.holdassistant.data.CallLog
import app.holdassistant.data.LoggedSegment
import app.holdassistant.ui.MainActivity
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Owns one VoIP call: connects through Twilio, transcribes the far end,
 * classifies it, and interrupts the user's media only when needed.
 */
class HoldCallService : Service(), Call.Listener {
    private val app get() = application as HoldApp
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private lateinit var audioManager: AudioManager

    private var call: Call? = null
    private var audio: HoldAudioDevice? = null
    private var stt: Transcriber? = null
    private var speaker: ProbeSpeaker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null

    // Touched only on the worker thread.
    private var classifier: Classifier? = null
    private var probe: ProbeController? = null
    private val tracker = AlertTracker()
    private var alertedFor: LineState? = null // what made us switch the user to the call
    private var pinned = false // the user chose to listen; don't auto-return to hold
    private val segments = mutableListOf<LoggedSegment>()
    private var sawHuman = false

    private var number = ""
    private var name = ""
    private var startedAt = 0L
    private var clockStart = 0L
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground()
                if (call == null && !ended) {
                    number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { PhoneNumbers.pretty(number) }
                    startCall()
                }
            }
            ACTION_HANGUP -> call?.disconnect() ?: finish("Cancelled")
            ACTION_LISTEN -> onWorker { pinned = true; enterListening(chime = false) }
            ACTION_HOLD -> onWorker { pinned = false; leaveListening() }
            ACTION_DIGITS -> intent.getStringExtra(EXTRA_DIGITS)?.let { d -> call?.sendDigits(d) }
            ACTION_SPEAKER -> setSpeaker(intent.getBooleanExtra(EXTRA_ON, false))
            ACTION_PROBE -> {
                val on = intent.getBooleanExtra(EXTRA_ON, false)
                onWorker { probe?.enabled = on }
                CallRepository.update { it.copy(probeEnabled = on) }
            }
        }
        return START_NOT_STICKY
    }

    // --- Call setup ---

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this, NOTIF_CALL, buildNotification(CallRepository.state.value),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    private fun startCall() {
        val settings = app.settings
        startedAt = System.currentTimeMillis()
        CallRepository.update {
            CallUiState(active = true, name = name, number = number, status = CallStatus.CONNECTING, startedAt = startedAt)
        }
        if (!settings.twilioReady) {
            finish("Finish setup first: tap Set up calling on the home screen.")
            return
        }
        if (!settings.transcriptionReady) {
            finish("This phone has no on-device speech recognition. Add a Deepgram key in Settings.")
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "holdassistant:call").apply { acquire(3 * 60 * 60 * 1000L) }

        onWorker {
            val token = TwilioToken.create(settings.accountSid, settings.apiKeySid, settings.apiKeySecret, settings.twimlAppSid)
            val line = app.lines.byNumber(number)
            val llm = settings.anthropicKey.takeIf { it.isNotEmpty() }?.let { key ->
                ClaudeLineTier(claudeClient(key)) { msg -> Log.w(TAG, msg) }
            }
            classifier = Classifier(llm = llm, known = line?.fingerprint ?: Fingerprint())
            val probeOn = line?.probe ?: settings.probeByDefault
            probe = ProbeController(enabled = probeOn)
            CallRepository.update { it.copy(probeEnabled = probeOn) }
            main.post { connect(token, settings.deepgramKey, settings.callerId) }
        }
        worker.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun connect(token: String, deepgramKey: String, callerId: String) {
        if (ended) return
        speaker = ProbeSpeaker(this)
        val onSeg = { t: Double, text: String -> onWorker { onSegment(t, text) } }
        val onInterim = { text: String -> CallRepository.update { it.copy(interim = text) } }
        val onError = { msg: String -> CallRepository.update { it.copy(message = msg) } }
        // Deepgram when the user added a key (more accurate on phone audio), else free on-device recognition.
        val stt: Transcriber = if (deepgramKey.isNotEmpty() || Build.VERSION.SDK_INT < 33) {
            DeepgramStt(deepgramKey, onSeg, onInterim, onError)
        } else {
            OnDeviceStt(this, ::clockS, onSeg, onInterim, onError)
        }
        this.stt = stt
        val device = HoldAudioDevice { pcm -> stt.feed(pcm) }.also { audio = it }
        Voice.setAudioDevice(device)
        clockStart = System.nanoTime()
        stt.start()
        val params = mapOf("To" to number, "CallerId" to callerId)
        call = Voice.connect(this, ConnectOptions.Builder(token).params(params).build(), this)
    }

    /** Run on the classifier thread; dropped once the call has been torn down. */
    private fun onWorker(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun clockS() = (System.nanoTime() - clockStart) / 1e9

    // --- Classification (worker thread) ---

    private fun onSegment(t: Double, text: String) {
        val c = classifier ?: return
        val seg = Segment(t, text)
        val d = c.classify(seg)
        segments += LoggedSegment(t, text, d.state, d.tier, d.reason)
        if (d.state == LineState.HUMAN) sawHuman = true
        CallRepository.update { s ->
            s.copy(
                transcript = s.transcript + TranscriptLine(t, text, d.state, d.reason),
                lineState = d.state,
                menuOptions = when (d.state) {
                    LineState.IVR_MENU -> MenuOptions.parse(text).ifEmpty { s.menuOptions }
                    LineState.HOLD -> emptyList()
                    else -> s.menuOptions
                },
            )
        }
        react(d)
        probe?.observe(seg, d)?.forEach { handleProbe(it) }
        audio?.micMuted = probe?.micMuted == true
        refreshNotification()
    }

    private fun react(d: Decision) {
        val listening = CallRepository.state.value.listening
        when {
            tracker.update(d.state) -> alert(d.state)
            // Escalation while already listening: a menu or maybe-person became a person.
            d.state == LineState.HUMAN && listening && alertedFor != LineState.HUMAN -> {
                alertedFor = LineState.HUMAN
                postAlert(alertTitle(LineState.HUMAN))
            }
            d.state == LineState.HOLD && listening && !pinned && alertedFor != LineState.HUMAN &&
                app.settings.autoReturnToHold && probe?.waiting != true -> leaveListening()
        }
    }

    private fun tick() {
        val p = probe ?: return
        p.tick(clockS()).forEach { handleProbe(it) }
        audio?.micMuted = p.micMuted
    }

    private fun handleProbe(e: ProbeController.Event) {
        val kw = probe?.keyword
        when (e.kind) {
            ProbeController.Kind.SPEAK -> {
                speaker?.render(e.detail) { pcm -> if (pcm != null) audio?.injectSpeech(pcm) }
                CallRepository.update { it.copy(probeStatus = "Asked the line to say \"$kw\"") }
            }
            ProbeController.Kind.CONFIRMED_HUMAN -> {
                CallRepository.update { it.copy(probeStatus = "Confirmed a person (${e.detail})", lineState = LineState.HUMAN) }
                sawHuman = true
                if (tracker.update(LineState.HUMAN)) alert(LineState.HUMAN) else {
                    alertedFor = LineState.HUMAN
                    postAlert(alertTitle(LineState.HUMAN))
                }
            }
            ProbeController.Kind.NO_RESPONSE, ProbeController.Kind.REJECTED_ECHO, ProbeController.Kind.IVR_REACTED -> {
                CallRepository.update { it.copy(probeStatus = "No person: ${e.detail}") }
                if (alertedFor == LineState.UNSURE && !pinned && app.settings.autoReturnToHold) leaveListening()
            }
        }
    }

    // --- Switching the user between their media and the call ---

    private fun alert(state: LineState) {
        alertedFor = state
        enterListening(chime = true)
        postAlert(alertTitle(state))
        vibrate()
    }

    private fun alertTitle(state: LineState) = when (state) {
        LineState.HUMAN -> "A person picked up"
        LineState.IVR_MENU -> "The menu needs you"
        else -> "Someone may be on the line"
    }

    private fun enterListening(chime: Boolean) {
        if (CallRepository.state.value.listening) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(req) // pauses Spotify, YouTube and friends
        focus = req
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeAudio(CallRepository.state.value.speakerOn)
        audio?.startListening(app.settings.replaySeconds, if (chime) HoldAudioDevice.chime() else null)
        CallRepository.update { it.copy(listening = true) }
        refreshNotification()
    }

    private fun leaveListening() {
        if (!CallRepository.state.value.listening) return
        audio?.stopListening()
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        focus?.let { audioManager.abandonAudioFocusRequest(it) } // media resumes
        focus = null
        alertedFor = null
        tracker.update(LineState.HOLD) // re-arm alerts
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
        CallRepository.update { it.copy(listening = false, alert = null) }
        refreshNotification()
    }

    private fun setSpeaker(on: Boolean) {
        CallRepository.update { it.copy(speakerOn = on) }
        if (CallRepository.state.value.listening) routeAudio(on)
    }

    @Suppress("DEPRECATION")
    private fun routeAudio(speakerOn: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            val preferred = if (speakerOn) {
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            } else {
                listOf(
                    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                )
            }
            preferred.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            audioManager.isSpeakerphoneOn = speakerOn
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
    }

    // --- Twilio Call.Listener (main thread) ---

    override fun onRinging(call: Call) {
        CallRepository.update { it.copy(status = CallStatus.RINGING) }
        refreshNotification()
    }

    override fun onConnected(call: Call) {
        CallRepository.update { it.copy(status = CallStatus.CONNECTED) }
        refreshNotification()
    }

    override fun onConnectFailure(call: Call, e: CallException) {
        finish("Call failed: ${e.message} (Twilio ${e.errorCode})")
    }

    override fun onReconnecting(call: Call, e: CallException) {
        CallRepository.update { it.copy(status = CallStatus.RECONNECTING) }
    }

    override fun onReconnected(call: Call) {
        CallRepository.update { it.copy(status = CallStatus.CONNECTED) }
    }

    override fun onDisconnected(call: Call, e: CallException?) {
        finish(if (e != null) "Call dropped: ${e.message}" else "Call ended")
    }

    // --- Teardown ---

    private fun finish(outcome: String) {
        if (ended) return
        ended = true
        stt?.stop()
        onWorker {
            leaveListening()
            val c = classifier
            if (c != null && segments.isNotEmpty()) {
                app.lines.learn(number, c.fingerprint())
                app.callLogs.save(
                    CallLog(
                        id = startedAt.toString(), name = name, number = number, startedAt = startedAt,
                        durationS = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
                        outcome = if (sawHuman) "$outcome after reaching a person" else outcome,
                        segments = segments.toList(),
                    ),
                )
            }
            main.post { teardown(outcome) }
        }
    }

    private fun teardown(outcome: String) {
        call?.release()
        call = null
        speaker?.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        worker.shutdownNow()
        CallRepository.update { it.copy(active = false, status = CallStatus.ENDED, message = outcome, listening = false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!ended) {
            call?.disconnect()
            finish("Call ended")
        }
        super.onDestroy()
    }

    // --- Notifications ---

    private fun refreshNotification() {
        if (ended) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, buildNotification(CallRepository.state.value))
    }

    private fun buildNotification(s: CallUiState): Notification {
        val stateLabel = when {
            s.status == CallStatus.CONNECTING -> "Connecting…"
            s.status == CallStatus.RINGING -> "Ringing…"
            s.status == CallStatus.RECONNECTING -> "Reconnecting…"
            s.listening -> "On the line"
            else -> when (s.lineState) {
                LineState.HOLD -> "On hold"
                LineState.IVR_MENU -> "Menu"
                LineState.HUMAN -> "Person"
                LineState.UNSURE -> "Unsure"
            }
        }
        val recent = s.transcript.takeLast(6).joinToString("\n") { "• ${it.text}" }
        val b = NotificationCompat.Builder(this, HoldApp.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${s.name.ifEmpty { name }}: $stateLabel")
            .setContentText(s.transcript.lastOrNull()?.text ?: "Transcript appears here")
            .setStyle(NotificationCompat.BigTextStyle().bigText(recent.ifEmpty { "Transcript appears here" }))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(true)
            .setWhen(if (startedAt > 0) startedAt else System.currentTimeMillis())
            .setContentIntent(openAppIntent())
        if (s.listening) {
            s.menuOptions.take(2).forEach { o -> b.addAction(0, "${o.digit}: ${o.label}".take(24), action(ACTION_DIGITS, o.digit)) }
            b.addAction(0, "Back to hold", action(ACTION_HOLD))
        } else {
            b.addAction(0, "Listen", action(ACTION_LISTEN))
        }
        if (s.menuOptions.isEmpty() || !s.listening) b.addAction(0, "Hang up", action(ACTION_HANGUP))
        return b.build()
    }

    private fun postAlert(title: String) {
        CallRepository.update { it.copy(alert = title) }
        val last = CallRepository.state.value.transcript.lastOrNull()?.text.orEmpty()
        val n = NotificationCompat.Builder(this, HoldApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$title: ${name}")
            .setContentText(last)
            .setStyle(NotificationCompat.BigTextStyle().bigText(last))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Back to hold", action(ACTION_HOLD))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, digits: String? = null): PendingIntent {
        val i = Intent(this, HoldCallService::class.java).setAction(action)
        if (digits != null) i.putExtra(EXTRA_DIGITS, digits)
        val code = action.hashCode() + (digits?.hashCode() ?: 0)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "HoldCallService"
        private const val NOTIF_CALL = 1
        private const val NOTIF_ALERT = 2

        const val ACTION_START = "app.holdassistant.START"
        const val ACTION_HANGUP = "app.holdassistant.HANGUP"
        const val ACTION_LISTEN = "app.holdassistant.LISTEN"
        const val ACTION_HOLD = "app.holdassistant.HOLD"
        const val ACTION_DIGITS = "app.holdassistant.DIGITS"
        const val ACTION_SPEAKER = "app.holdassistant.SPEAKER"
        const val ACTION_PROBE = "app.holdassistant.PROBE"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        const val EXTRA_DIGITS = "digits"
        const val EXTRA_ON = "on"

        fun start(context: Context, number: String, name: String) {
            CallRepository.reset()
            ContextCompat.startForegroundService(
                context,
                Intent(context, HoldCallService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_NAME, name),
            )
        }

        fun send(context: Context, action: String, digits: String? = null, on: Boolean? = null) {
            val i = Intent(context, HoldCallService::class.java).setAction(action)
            digits?.let { i.putExtra(EXTRA_DIGITS, it) }
            on?.let { i.putExtra(EXTRA_ON, it) }
            context.startService(i)
        }
    }
}

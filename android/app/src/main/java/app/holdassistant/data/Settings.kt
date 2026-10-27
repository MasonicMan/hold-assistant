package app.holdassistant.data

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.edit
import app.holdassistant.core.CallingConfig

/** User configuration. Keys stay on the device in app-private storage. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String) = prefs.getString(key, "").orEmpty()
    private fun put(key: String, v: String) = prefs.edit { putString(key, v.trim()) }

    // Twilio account (entered once in Setup).
    var accountSid: String
        get() = str("account_sid")
        set(v) = put("account_sid", v)
    var authToken: String
        get() = str("auth_token")
        set(v) = put("auth_token", v)
    var callerId: String
        get() = str("caller_id")
        set(v) = put("caller_id", v)

    // Created by Setup in the user's Twilio account.
    val apiKeySid: String get() = str("api_key_sid")
    val apiKeySecret: String get() = str("api_key_secret")
    val twimlAppSid: String get() = str("twiml_app_sid")

    fun saveCallingConfig(c: CallingConfig) = prefs.edit {
        putString("api_key_sid", c.apiKeySid)
        putString("api_key_secret", c.apiKeySecret)
        putString("twiml_app_sid", c.twimlAppSid)
    }

    var deepgramKey: String
        get() = str("deepgram_key")
        set(v) = put("deepgram_key", v)

    var anthropicKey: String
        get() = str("anthropic_key")
        set(v) = put("anthropic_key", v)

    /** Ask "say pineapple" when unsure. Off by default: probing can confuse an IVR. */
    var probeByDefault: Boolean
        get() = prefs.getBoolean("probe_default", false)
        set(v) = prefs.edit { putBoolean("probe_default", v) }

    /** Seconds of call audio replayed when an alert switches you to the call. */
    var replaySeconds: Int
        get() = prefs.getInt("replay_seconds", 8)
        set(v) = prefs.edit { putInt("replay_seconds", v.coerceIn(0, 15)) }

    /** Go back to your media by itself when a menu or unsure alert turns out to be hold. */
    var autoReturnToHold: Boolean
        get() = prefs.getBoolean("auto_return", true)
        set(v) = prefs.edit { putBoolean("auto_return", v) }

    val twilioReady: Boolean
        get() = listOf(accountSid, callerId, apiKeySid, apiKeySecret, twimlAppSid).all { it.isNotEmpty() }

    /** Free speech recognition on the phone itself (Android 13+ with an on-device model). */
    val onDeviceTranscription: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val transcriptionReady: Boolean get() = deepgramKey.isNotEmpty() || onDeviceTranscription

    val callingReady: Boolean get() = twilioReady && transcriptionReady
}

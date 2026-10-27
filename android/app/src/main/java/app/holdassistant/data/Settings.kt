package app.holdassistant.data

import android.content.Context
import androidx.core.content.edit

/** User configuration. Keys stay on the device in app-private storage. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tokenUrl: String
        get() = prefs.getString("token_url", "").orEmpty()
        set(v) = prefs.edit { putString("token_url", v.trim()) }

    var appSecret: String
        get() = prefs.getString("app_secret", "").orEmpty()
        set(v) = prefs.edit { putString("app_secret", v.trim()) }

    var deepgramKey: String
        get() = prefs.getString("deepgram_key", "").orEmpty()
        set(v) = prefs.edit { putString("deepgram_key", v.trim()) }

    var anthropicKey: String
        get() = prefs.getString("anthropic_key", "").orEmpty()
        set(v) = prefs.edit { putString("anthropic_key", v.trim()) }

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

    val callingReady: Boolean get() = tokenUrl.isNotEmpty() && appSecret.isNotEmpty() && deepgramKey.isNotEmpty()
}

package app.holdassistant.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Twilio Voice access token, signed on the device with the account's API key.
 * Replaces a token server: this is a single-user app, so the key lives with the user.
 */
object TwilioToken {
    private val mapper = ObjectMapper()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun create(
        accountSid: String,
        apiKeySid: String,
        apiKeySecret: String,
        twimlAppSid: String,
        identity: String = "hold-assistant",
        ttlSeconds: Long = 3600,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val header = mapOf("typ" to "JWT", "alg" to "HS256", "cty" to "twilio-fpa;v=1")
        val payload = mapOf(
            "jti" to "$apiKeySid-$nowSeconds",
            "iss" to apiKeySid,
            "sub" to accountSid,
            "iat" to nowSeconds,
            "exp" to nowSeconds + ttlSeconds,
            "grants" to mapOf(
                "identity" to identity,
                "voice" to mapOf(
                    "incoming" to mapOf("allow" to false),
                    "outgoing" to mapOf("application_sid" to twimlAppSid),
                ),
            ),
        )
        val signingInput = b64.encodeToString(mapper.writeValueAsBytes(header)) + "." +
            b64.encodeToString(mapper.writeValueAsBytes(payload))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(apiKeySecret.toByteArray(), "HmacSHA256")) }
        return signingInput + "." + b64.encodeToString(mac.doFinal(signingInput.toByteArray()))
    }
}

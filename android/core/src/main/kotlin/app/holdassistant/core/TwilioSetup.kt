package app.holdassistant.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TwilioException(message: String, val status: Int = 0, val code: Int = 0) : IOException(message)

data class TwilioAccount(val name: String, val trial: Boolean, val active: Boolean)

data class TwilioBalance(val amount: String, val currency: String) {
    override fun toString() = if (currency == "USD") "$$amount" else "$amount $currency"
}

/** What the app needs to place calls, produced by [TwilioSetup.provision]. */
data class CallingConfig(val apiKeySid: String, val apiKeySecret: String, val twimlAppSid: String, val voiceUrl: String)

/**
 * Sets up a user's Twilio account from the app, given only the Account SID and
 * Auth Token: API key, a Twilio Function that dials out, and a TwiML App
 * pointing at it. Also reads balance and manages caller IDs.
 *
 * Everything is blocking; call off the main thread. Billing (upgrading the
 * account, adding a card or funds) has no API and stays on twilio.com.
 */
class TwilioSetup(
    private val accountSid: String,
    authToken: String,
    private val apiBase: String = "https://api.twilio.com",
    private val serverlessBase: String = "https://serverless.twilio.com",
    private val uploadBase: String = "https://serverless-upload.twilio.com",
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val auth = Credentials.basic(accountSid, authToken)
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val mapper = ObjectMapper()
    private val account = "$apiBase/2010-04-01/Accounts/$accountSid"

    fun account(): TwilioAccount {
        val a = get("$account.json")
        return TwilioAccount(a.text("friendly_name"), a.text("type") == "Trial", a.text("status") == "active")
    }

    fun balance(): TwilioBalance {
        val b = get("$account/Balance.json")
        return TwilioBalance(b.text("balance"), b.text("currency"))
    }

    /** Numbers this account may use as caller ID: verified numbers and numbers it owns. */
    fun callerIds(): List<String> {
        val verified = get("$account/OutgoingCallerIds.json").path("outgoing_caller_ids").map { it.text("phone_number") }
        val owned = get("$account/IncomingPhoneNumbers.json").path("incoming_phone_numbers").map { it.text("phone_number") }
        return (verified + owned).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Start verifying [number] as a caller ID. Twilio phones it; the user
     * enters the returned code on the keypad.
     */
    fun startCallerIdVerification(number: String): String =
        post("$account/OutgoingCallerIds.json", mapOf("PhoneNumber" to number, "FriendlyName" to "Hold Assistant")).text("validation_code")

    fun isCallerId(number: String): Boolean = number in callerIds()

    /** Create (or replace) everything calling needs. [progress] gets short status lines. */
    fun provision(progress: (String) -> Unit = {}): CallingConfig {
        progress("Creating an API key")
        val key = post("$account/Keys.json", mapOf("FriendlyName" to "Hold Assistant"))
        val apiKeySid = key.text("sid")
        val apiKeySecret = key.text("secret")

        progress("Deploying the call function")
        val voiceUrl = deployFunction()

        progress("Creating the TwiML app")
        val existing = get("$account/Applications.json?FriendlyName=$APP_NAME_ENCODED").path("applications")
            .firstOrNull()?.text("sid")
        val appParams = mapOf("FriendlyName" to APP_NAME, "VoiceUrl" to voiceUrl, "VoiceMethod" to "POST")
        val appSid = if (existing != null) {
            post("$account/Applications/$existing.json", appParams).text("sid")
        } else {
            post("$account/Applications.json", appParams).text("sid")
        }
        progress("Done")
        return CallingConfig(apiKeySid, apiKeySecret, appSid, voiceUrl)
    }

    private fun deployFunction(): String {
        val sl = "$serverlessBase/v1/Services"
        // Start clean so re-running setup always gets the current function code.
        if (exists("$sl/$SERVICE_NAME")) delete("$sl/$SERVICE_NAME")
        val service = post(sl, mapOf("UniqueName" to SERVICE_NAME, "FriendlyName" to "Hold Assistant", "IncludeCredentials" to "true")).text("sid")
        val function = post("$sl/$service/Functions", mapOf("FriendlyName" to "outgoing")).text("sid")

        val upload = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("Path", "/outgoing")
            .addFormDataPart("Visibility", "protected")
            .addFormDataPart("Content", "outgoing.js", OUTGOING_JS.toRequestBody("application/javascript".toMediaType()))
            .build()
        val version = send(Request.Builder().url("$uploadBase/v1/Services/$service/Functions/$function/Versions").post(upload)).text("sid")

        var build = post(
            "$sl/$service/Builds",
            mapOf("FunctionVersions" to version, "Runtime" to "node22", "Dependencies" to DEPENDENCIES),
        )
        val buildSid = build.text("sid")
        var polls = 0
        while (build.text("status") != "completed") {
            if (build.text("status") == "failed") throw TwilioException("Twilio couldn't build the call function")
            if (polls++ >= 90) throw TwilioException("Building the call function timed out")
            sleep(2000)
            build = get("$sl/$service/Builds/$buildSid")
        }

        val env = post("$sl/$service/Environments", mapOf("UniqueName" to "production", "DomainSuffix" to "prod"))
        post("$sl/$service/Environments/${env.text("sid")}/Deployments", mapOf("BuildSid" to buildSid))
        return "https://${env.text("domain_name")}/outgoing"
    }

    // --- HTTP ---

    private fun get(url: String) = send(Request.Builder().url(url).get())

    private fun post(url: String, form: Map<String, String>): JsonNode {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return send(Request.Builder().url(url).post(body))
    }

    private fun delete(url: String) = send(Request.Builder().url(url).delete())

    private fun exists(url: String): Boolean = try {
        get(url)
        true
    } catch (e: TwilioException) {
        if (e.status == 404) false else throw e
    }

    private fun send(req: Request.Builder): JsonNode {
        http.newCall(req.header("Authorization", auth).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = if (text.isBlank()) mapper.createObjectNode() else runCatching { mapper.readTree(text) }.getOrElse { mapper.createObjectNode() }
            if (!resp.isSuccessful) {
                val message = when (resp.code) {
                    401 -> "Twilio didn't accept the Account SID and Auth Token"
                    else -> json.text("message").ifEmpty { "Twilio error ${resp.code}" }
                }
                throw TwilioException(message, resp.code, json.path("code").asInt())
            }
            return json
        }
    }

    private fun JsonNode?.text(field: String): String = this?.path(field)?.asText("").orEmpty().let { if (it == "null") "" else it }

    companion object {
        const val SERVICE_NAME = "hold-assistant"
        const val APP_NAME = "Hold Assistant"
        private const val APP_NAME_ENCODED = "Hold%20Assistant"
        private const val DEPENDENCIES =
            """[{"name":"twilio","version":"6.2.0"},{"name":"@twilio/runtime-handler","version":"2.1.0"}]"""

        /** Dials the number the app asks for, from the caller ID the app passes. Same as twilio/functions/outgoing.js. */
        val OUTGOING_JS = """
            exports.handler = function (context, event, callback) {
              const twiml = new Twilio.twiml.VoiceResponse();
              const to = (event.To || '').trim();
              const from = (event.CallerId || '').trim();
              const e164 = /^\+[1-9][0-9]{7,14}${'$'}/;
              if (!e164.test(to) || !e164.test(from)) {
                twiml.say('Sorry, that number is not valid.');
                return callback(null, twiml);
              }
              const dial = twiml.dial({ callerId: from, answerOnBridge: true });
              dial.number(to);
              return callback(null, twiml);
            };
        """.trimIndent() + "\n"
    }
}

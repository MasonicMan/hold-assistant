package app.holdassistant.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TwilioTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<String>()
    private var buildPolls = 0
    private var serviceExists = true

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                val body = request.body.readUtf8()
                requests += "${request.method} $path ${URLDecoder.decode(body, "UTF-8").take(3000)}"
                if (request.getHeader("Authorization") != okhttp3.Credentials.basic("AC1", "tok")) return json("""{"code":20003}""", 401)
                return when {
                    path == "/2010-04-01/Accounts/AC1.json" -> json("""{"friendly_name":"Me","type":"Trial","status":"active"}""")
                    path.endsWith("/Balance.json") -> json("""{"balance":"18.42","currency":"USD"}""")
                    path.endsWith("/OutgoingCallerIds.json") && request.method == "GET" ->
                        json("""{"outgoing_caller_ids":[{"phone_number":"+15125550100"}]}""")
                    path.endsWith("/OutgoingCallerIds.json") -> json("""{"validation_code":"123456"}""")
                    path.endsWith("/IncomingPhoneNumbers.json") -> json("""{"incoming_phone_numbers":[]}""")
                    path.endsWith("/Keys.json") -> json("""{"sid":"SK1","secret":"shh"}""")
                    path == "/v1/Services/hold-assistant" && request.method == "GET" ->
                        if (serviceExists) json("""{"sid":"ZSold"}""") else json("""{"code":20404}""", 404)
                    path == "/v1/Services/hold-assistant" && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path == "/v1/Services" -> json("""{"sid":"ZS1"}""")
                    path == "/v1/Services/ZS1/Functions" -> json("""{"sid":"ZH1"}""")
                    path == "/v1/Services/ZS1/Functions/ZH1/Versions" -> json("""{"sid":"ZN1"}""")
                    path == "/v1/Services/ZS1/Builds" -> json("""{"sid":"ZB1","status":"building"}""")
                    path == "/v1/Services/ZS1/Builds/ZB1" -> json("""{"sid":"ZB1","status":"${if (++buildPolls >= 2) "completed" else "building"}"}""")
                    path == "/v1/Services/ZS1/Environments" -> json("""{"sid":"ZE1","domain_name":"hold-assistant-1234-prod.twil.io"}""")
                    path == "/v1/Services/ZS1/Environments/ZE1/Deployments" -> json("""{"sid":"ZD1"}""")
                    path.startsWith("/2010-04-01/Accounts/AC1/Applications.json?") -> json("""{"applications":[]}""")
                    path == "/2010-04-01/Accounts/AC1/Applications.json" -> json("""{"sid":"AP1"}""")
                    else -> json("""{"message":"unexpected $path"}""", 400)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun setup(token: String = "tok"): TwilioSetup {
        val base = server.url("/").toString().trimEnd('/')
        return TwilioSetup("AC1", token, base, base, base, sleep = {})
    }

    @Test fun readsAccountBalanceAndCallerIds() {
        val t = setup()
        assertEquals(TwilioAccount("Me", trial = true, active = true), t.account())
        assertEquals("$18.42", t.balance().toString())
        assertEquals(listOf("+15125550100"), t.callerIds())
        assertTrue(t.isCallerId("+15125550100"))
        assertEquals("123456", t.startCallerIdVerification("+15125550199"))
    }

    @Test fun badCredentialsGiveAClearError() {
        try {
            setup("wrong").account()
            fail()
        } catch (e: TwilioException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("Auth Token"))
        }
    }

    @Test fun provisionsEverything() {
        val steps = mutableListOf<String>()
        val cfg = setup().provision { steps += it }
        assertEquals(CallingConfig("SK1", "shh", "AP1", "https://hold-assistant-1234-prod.twil.io/outgoing"), cfg)
        assertEquals("Done", steps.last())
        val log = requests.joinToString("\n")
        assertTrue(log, log.contains("DELETE /v1/Services/hold-assistant"))
        assertTrue(log, log.contains("Visibility") && log.contains("protected") && log.contains("Twilio.twiml.VoiceResponse"))
        assertTrue(log, log.contains("FunctionVersions=ZN1") && log.contains("Runtime=node22"))
        assertTrue(log, log.contains("BuildSid=ZB1"))
        assertTrue(log, log.contains("VoiceUrl=https://hold-assistant-1234-prod.twil.io/outgoing"))
    }

    @Test fun provisionSkipsDeleteForNewAccounts() {
        serviceExists = false
        setup().provision()
        assertFalse(requests.any { it.startsWith("DELETE") })
    }

    @Test fun accessTokenIsSignedAndScoped() {
        val jwt = TwilioToken.create("AC1", "SK1", "secret", "AP1", nowSeconds = 1000)
        val (h, p, sig) = jwt.split(".")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$h.$p".toByteArray())), sig)
        val m = ObjectMapper()
        assertEquals("twilio-fpa;v=1", m.readTree(Base64.getUrlDecoder().decode(h))["cty"].asText())
        val payload = m.readTree(Base64.getUrlDecoder().decode(p))
        assertEquals("SK1", payload["iss"].asText())
        assertEquals("AC1", payload["sub"].asText())
        assertEquals(4600, payload["exp"].asLong())
        assertEquals("AP1", payload["grants"]["voice"]["outgoing"]["application_sid"].asText())
    }
}

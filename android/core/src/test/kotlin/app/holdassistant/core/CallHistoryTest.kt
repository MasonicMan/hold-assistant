package app.holdassistant.core

import app.holdassistant.core.CallRecord.Type
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryTest {
    private var clock = 1_000_000L
    private fun call(number: String, type: Type = Type.OUTGOING, minutes: Long = 1, name: String? = null) =
        CallRecord(number, name, type, clock++, minutes * 60)

    private fun kinds(vararg records: CallRecord) =
        CallHistoryClassifier.analyze(records.toList()).associate { it.history.number to it.kind }

    @Test fun unsavedTollFreeIsAnInstitution() =
        assertEquals(ContactKind.INSTITUTION, kinds(call("1-800-935-9935"))["+18009359935"])

    @Test fun longUnsavedLocalCallLooksLikeHold() =
        assertEquals(ContactKind.INSTITUTION, kinds(call("(512) 555-0100", minutes = 34))["+15125550100"])

    @Test fun savedPersonIsPersonal() {
        val k = kinds(call("5125550101", minutes = 40, name = "Jordan Lee"), call("5125550102", name = "Mom"))
        assertEquals(ContactKind.PERSONAL, k["+15125550101"])
        assertEquals(ContactKind.PERSONAL, k["+15125550102"])
    }

    @Test fun savedCompanyNameCounts() =
        assertEquals(ContactKind.INSTITUTION, kinds(call("5125550103", name = "Verizon"))["+15125550103"])

    @Test fun shortUnsavedLocalCallIsUnclear() =
        assertEquals(ContactKind.UNCLEAR, kinds(call("5125550104"))["+15125550104"])

    @Test fun incomingOnlyAndShortCodesAreSkipped() {
        val k = kinds(call("5125550105", Type.INCOMING), call("611"))
        assertTrue(k.isEmpty())
    }

    @Test fun summaryMergesFormatsAndKeepsLatestName() {
        val h = CallHistoryClassifier.summarize(
            listOf(
                call("800-935-9935", minutes = 3),
                call("+1 (800) 935-9935", Type.INCOMING),
                call("18009359935", minutes = 41, name = "Chase"),
            ),
        ).single()
        assertEquals(NumberHistory("+18009359935", "Chase", 2, 1, 41 * 60, clock - 1), h)
    }

    @Test fun lookupParsesOnlyRequestedNumbers() {
        val text = """
            I searched for both numbers.
            +18009359935 | Chase Bank customer service
            (512) 555-0100 | unknown
            +19995550000 | Someone else
            **+18882804331** | "Wells Fargo"
        """.trimIndent()
        val got = ClaudeNumberLookup.parse(text, listOf("+18009359935", "+15125550100", "+18882804331"))
        assertEquals(mapOf("+18009359935" to "Chase Bank customer service", "+18882804331" to "Wells Fargo"), got)
    }

    @Test fun lookupRequestBuildsWithWebSearch() {
        val p = ClaudeNumberLookup(AnthropicOkHttpClient.builder().apiKey("test").build()).params(listOf("+18009359935"))
        assertTrue(p.tools().get().toString().contains("web_search"))
    }
}

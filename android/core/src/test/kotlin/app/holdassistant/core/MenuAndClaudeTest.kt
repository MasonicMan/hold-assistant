package app.holdassistant.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuAndClaudeTest {
    @Test fun parsesLabelFirstAndKeyFirstOptions() {
        val opts = MenuOptions.parse(
            "Please listen carefully. For account balances, press 1. For billing questions, press 2. " +
                "To speak with a representative, press zero. Press 9 to repeat these options.",
        )
        assertEquals(
            listOf(
                MenuOption("1", "account balances"),
                MenuOption("2", "billing questions"),
                MenuOption("0", "speak with a representative"),
                MenuOption("9", "repeat these options"),
            ),
            opts,
        )
    }

    @Test fun firstLabelWinsAndLongLabelsTruncate() {
        val opts = MenuOptions.parse(
            "To receive a callback when an agent is available instead of waiting on hold, press 1. " +
                "To continue holding, press 2. For something else press 1.",
        )
        assertEquals(listOf("1", "2"), opts.map { it.digit })
        assertTrue(opts[0].label.endsWith("…") && opts[0].label.length <= 40)
    }

    @Test fun noOptionsInHoldText() = assertTrue(MenuOptions.parse("Your call is important to us.").isEmpty())

    // Builds requests (which generates and locally validates the JSON schemas)
    // without sending them.
    @Test fun claudeRequestsBuildWithValidSchemas() {
        val client = AnthropicOkHttpClient.builder().apiKey("test").build()
        val line = ClaudeLineTier(client).params(listOf(0.0 to "Please hold."), "hm, okay")
        assertEquals(CLAUDE_MODEL, line.rawParams.model().asString())
        val contacts = ClaudeContactClassifier(client).params(
            listOf(ContactInfo("7", "Sam's Plumbing", phones = listOf(ContactInfo.Phone("18005550100")))),
        )
        val body = contacts.rawParams.messages().first().content().toString()
        assertTrue(body, "toll_free=true" in body && "1800" !in body)
    }
}

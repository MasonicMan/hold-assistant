package app.holdassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {
    private val hold = "Your call is important to us. Please stay on the line and your call will be answered in the order it was received."

    private fun run(c: Classifier, vararg texts: String) = texts.mapIndexed { i, t -> c.classify(Segment(i * 10.0, t)) }

    @Test fun silenceIsHold() = assertEquals(LineState.HOLD, run(Classifier(), "")[0].state)

    @Test fun repeatMatchCatchesUncuedRecording() {
        val ad = "Example Wireless now offers unlimited plans starting at thirty five dollars a month."
        val (first, second) = run(Classifier(), ad, ad)
        assertEquals(LineState.UNSURE, first.state)
        assertEquals(LineState.HOLD to "repeat", second.state to second.tier)
    }

    @Test fun repeatMatchToleratesSttCuts() {
        val d = run(Classifier(), hold, "stay on the line and your call will be answered in the order")[1]
        assertEquals(LineState.HOLD to "repeat", d.state to d.tier)
    }

    @Test fun repeatedMenuStaysMenu() {
        val menu = "For account balances press 1. For billing questions press 2. For anything else press 0."
        assertEquals(LineState.IVR_MENU, run(Classifier(), menu, menu)[1].state)
    }

    @Test fun humanCueOverridesHoldPhraseAndRepeat() =
        assertEquals(LineState.HUMAN, run(Classifier(), "Thank you for holding.", "Thank you for holding, my name is Ana.")[1].state)

    @Test fun menuWinsOverHumanCue() =
        assertEquals(LineState.IVR_MENU, run(Classifier(), "How can I help you today? You can say things like pay my bill.")[0].state)

    @Test fun ivrGreetingWithHelloIsNotHuman() =
        assertNotEquals(LineState.HUMAN, run(Classifier(), "Hello, and thank you for calling Example Bank.")[0].state)

    @Test fun endOfMenuAlerts() = assertTrue(run(Classifier(), "You have reached the end of this menu.")[0].state.alerts)

    @Test fun specialFlagsReported() {
        val d = run(Classifier(), "This call may be recorded for quality purposes.")[0]
        assertEquals(LineState.HOLD, d.state)
        assertTrue("recorded" in d.flags)
    }

    @Test fun llmOnlyForUnplacedTextAndLowConfidenceIsUnsure() {
        val calls = mutableListOf<List<Pair<Double, String>>>()
        val c = Classifier(llm = { w, _ -> calls += w; LlmVerdict(LineState.HUMAN, 0.4, "maybe") })
        val d = run(c, hold, "it shows it shipped on the fourth")
        assertEquals("phrase", d[0].tier)
        assertEquals(LineState.UNSURE to "llm", d[1].state to d[1].tier)
        assertEquals(1, calls.size)
        assertEquals("it shows it shipped on the fourth", calls[0].last().second)
    }

    @Test fun llmFailureFallsBackToUnsure() =
        assertEquals(LineState.UNSURE, run(Classifier(llm = { _, _ -> null }), "it shows it shipped on the fourth")[0].state)

    @Test fun fingerprintSkipsLlm() {
        val c = Classifier(llm = { _, _ -> error("LLM should not be called") }, known = Fingerprint(hold = listOf(hold)))
        val (d1, d2) = run(c, hold, "okay I've got your account up now")
        assertEquals(LineState.HOLD, d1.state)
        assertEquals(LineState.HUMAN to "fingerprint", d2.state to d2.tier)
    }

    @Test fun fingerprintExport() {
        val c = Classifier()
        run(c, hold, "For billing press 1.", "", "Hi, my name is Sam.")
        assertEquals(Fingerprint(listOf(hold), listOf("For billing press 1.")), c.fingerprint())
    }

    @Test fun alertTrackerFiresOnLeavingHoldOnly() {
        val t = AlertTracker()
        val states = listOf(LineState.HOLD, LineState.IVR_MENU, LineState.UNSURE, LineState.HOLD, LineState.HUMAN)
        assertEquals(listOf(false, true, false, false, true), states.map { t.update(it) })
    }

    @Test fun fuzzyHelpersMatchRapidfuzzScale() {
        assertEquals(100.0, ratio("abc", "abc"), 0.0)
        assertEquals(50.0, ratio("abcd", "abxy"), 0.0)
        assertEquals(0.0, ratio("ab", "cd"), 0.0)
        assertTrue(partialRatio("the order", "answered in the order it was") >= 100.0)
        assertTrue(containsWord("uh pine apple", "pineapple"))
        assertFalse(containsWord("please hold", "pineapple"))
    }
}

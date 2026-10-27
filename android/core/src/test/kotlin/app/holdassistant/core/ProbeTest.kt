package app.holdassistant.core

import app.holdassistant.core.ProbeController.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProbeTest {
    private val unsure = Decision(LineState.UNSURE, "fallback", 0.0, "")
    private val hold = Decision(LineState.HOLD, "phrase", 0.9, "")
    private val menu = Decision(LineState.IVR_MENU, "phrase", 0.9, "")

    private fun probe(menuQuietS: Double = 10.0) =
        ProbeController(enabled = true, keyword = "pineapple", menuQuietS = menuQuietS)

    private fun kinds(events: List<ProbeController.Event>) = events.map { it.kind }

    @Test fun keywordsAreVetted() {
        assertTrue(ProbeController.VETTED_KEYWORDS.none { it in ProbeController.IVR_TRIGGER_WORDS })
        assertTrue(ProbeController.VETTED_KEYWORDS.all { it.length >= 6 })
    }

    @Test fun keywordRandomPerCall() =
        assertTrue((0 until 30).map { ProbeController(random = Random(it)).keyword }.toSet().size > 3)

    @Test fun disabledByDefault() {
        val p = ProbeController(keyword = "pineapple")
        p.observe(Segment(0.0, "hm"), unsure)
        assertTrue(p.tick(10.0).isEmpty())
    }

    @Test fun firesAfterUnsurePersistsThenConfirms() {
        val p = probe()
        p.observe(Segment(0.0, "hm"), unsure)
        assertTrue(p.tick(2.0).isEmpty())
        assertEquals(listOf(Kind.SPEAK), kinds(p.tick(3.0)))
        assertTrue(p.micMuted)
        assertEquals(listOf(Kind.CONFIRMED_HUMAN), kinds(p.observe(Segment(5.0, "uh, pine apple?"), unsure)))
        assertFalse(p.micMuted)
    }

    @Test fun timeoutThenCooldownUntilUnsureEventEnds() {
        val p = probe()
        p.observe(Segment(0.0, "hm"), unsure)
        p.tick(3.0)
        assertEquals(listOf(Kind.NO_RESPONSE), kinds(p.tick(11.0)))
        assertTrue(p.tick(30.0).isEmpty())
        p.observe(Segment(31.0, "please hold"), hold)
        p.observe(Segment(40.0, "hm"), unsure)
        assertEquals(listOf(Kind.SPEAK), kinds(p.tick(43.0)))
    }

    @Test fun neverProbesRightAfterAMenu() {
        val p = probe()
        p.observe(Segment(0.0, "press 1"), menu)
        p.observe(Segment(2.0, "hm"), unsure)
        assertTrue(p.tick(6.0).isEmpty())
        assertEquals(listOf(Kind.SPEAK), kinds(p.tick(10.0)))
    }

    @Test fun echoGetsRepromptThenRejected() {
        val p = probe()
        p.observe(Segment(0.0, "hm"), unsure)
        p.tick(3.0)
        assertEquals(listOf(Kind.SPEAK), kinds(p.observe(Segment(5.0, "You said pineapple. Is that correct?"), menu)))
        assertEquals(listOf(Kind.REJECTED_ECHO), kinds(p.observe(Segment(9.0, "I heard pineapple, is that right?"), menu)))
    }

    @Test fun ivrReactionWithoutKeywordStopsProbe() {
        val p = probe()
        p.observe(Segment(0.0, "hm"), unsure)
        p.tick(3.0)
        assertEquals(listOf(Kind.IVR_REACTED), kinds(p.observe(Segment(5.0, "Sorry, I didn't get that."), menu)))
    }

    @Test fun contextCheckCanVeto() {
        val p = ProbeController(enabled = true, keyword = "pineapple", contextCheck = { _, _ -> false })
        p.observe(Segment(0.0, "hm"), unsure)
        p.tick(3.0)
        assertEquals(listOf(Kind.SPEAK), kinds(p.observe(Segment(5.0, "pineapple"), unsure)))
    }
}

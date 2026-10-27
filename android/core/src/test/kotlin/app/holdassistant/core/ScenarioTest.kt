package app.holdassistant.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Runs the shared gold-labeled scenarios (repo /scenarios) through the Kotlin port. */
class ScenarioTest {
    private val dir = File(System.getProperty("scenarios.dir") ?: "../../scenarios")

    @Test fun scenariosNeverMissAHuman() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no scenarios found in $dir", files.isNotEmpty())
        var falseAlarms = 0
        for (f in files) {
            val spec = ObjectMapper().readTree(f)
            val fp = spec["known_fingerprint"]
            val clf = Classifier(
                known = Fingerprint(
                    fp?.get("hold")?.map { it.asText() }.orEmpty(),
                    fp?.get("ivr_menu")?.map { it.asText() }.orEmpty(),
                ),
            )
            val probe = ProbeController(enabled = spec["probe"]?.asBoolean() == true, random = Random(7))
            var clock = 0.0
            var humanAlerted: Boolean? = null
            for (raw in spec["segments"]) {
                val seg = Segment(raw["t"].asDouble(), raw["text"].asText().replace("{keyword}", probe.keyword))
                while (clock + 1 <= seg.t) probe.tick(++clock)
                clock = seg.t
                val d = clf.classify(seg)
                val gold = LineState.fromWire(raw["label"].asText())!!
                if (gold == LineState.HUMAN) {
                    assertTrue("${f.name} @${seg.t}: human classified ${d.state}", d.state.alerts)
                    if (humanAlerted == null) humanAlerted = d.state.alerts
                }
                if (gold == LineState.HOLD && d.state.alerts) falseAlarms++
                probe.observe(seg, d)
            }
            repeat(15) { probe.tick(++clock) }
            spec["expect_probe"]?.asText()?.let { expected ->
                val last = probe.events.lastOrNull { it.kind != ProbeController.Kind.SPEAK }
                assertNotNull("${f.name}: probe never resolved", last)
                assertEquals(f.name, expected, last!!.kind.name.lowercase())
            }
        }
        // Same as the Python harness on these scenarios; raise only deliberately.
        assertTrue("false alarms: $falseAlarms", falseAlarms <= 3)
    }
}

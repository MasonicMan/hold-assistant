package app.holdassistant.data

import android.content.Context
import app.holdassistant.core.LineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LoggedSegment(
    val t: Double,
    val text: String,
    val state: LineState,
    val tier: String,
    val reason: String,
    val label: LineState? = null, // the user's correction, if any
) {
    val effectiveLabel: LineState get() = label ?: state
}

data class CallLog(
    val id: String, // start time millis, also the file name
    val name: String,
    val number: String,
    val startedAt: Long,
    val durationS: Int,
    val outcome: String,
    val segments: List<LoggedSegment>,
)

/**
 * One JSON file per call. Each can be exported in the harness's scenario
 * format, so real calls become test cases for the classifier.
 */
class CallLogStore(context: Context) {
    private val dir = File(context.filesDir, "calls").apply { mkdirs() }
    private val _logs = MutableStateFlow(loadAll())
    val logs: StateFlow<List<CallLog>> = _logs

    fun save(log: CallLog) {
        File(dir, "${log.id}.json").writeText(toJson(log).toString())
        _logs.value = (_logs.value.filter { it.id != log.id } + log).sortedByDescending { it.startedAt }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _logs.value = _logs.value.filter { it.id != id }
    }

    fun relabel(id: String, index: Int, label: LineState?) {
        val log = _logs.value.firstOrNull { it.id == id } ?: return
        val segs = log.segments.toMutableList()
        segs[index] = segs[index].copy(label = label)
        save(log.copy(segments = segs))
    }

    /** Harness scenario JSON (see /scenarios in the repo). */
    fun exportScenario(log: CallLog): String {
        val segs = JSONArray()
        log.segments.forEach { s ->
            segs.put(JSONObject().put("t", s.t).put("label", s.effectiveLabel.wire).put("text", s.text))
        }
        return JSONObject()
            .put("description", "Real call to ${log.name}, ${java.util.Date(log.startedAt)}. Outcome: ${log.outcome}.")
            .put("segments", segs)
            .toString(2)
    }

    private fun toJson(l: CallLog) = JSONObject()
        .put("id", l.id).put("name", l.name).put("number", l.number).put("startedAt", l.startedAt)
        .put("durationS", l.durationS).put("outcome", l.outcome)
        .put(
            "segments",
            JSONArray().apply {
                l.segments.forEach { s ->
                    put(
                        JSONObject().put("t", s.t).put("text", s.text).put("state", s.state.wire)
                            .put("tier", s.tier).put("reason", s.reason).put("label", s.label?.wire ?: JSONObject.NULL),
                    )
                }
            },
        )

    private fun loadAll(): List<CallLog> = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("segments")
            CallLog(
                id = o.getString("id"), name = o.getString("name"), number = o.getString("number"),
                startedAt = o.getLong("startedAt"), durationS = o.optInt("durationS"), outcome = o.optString("outcome"),
                segments = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    LoggedSegment(
                        t = s.getDouble("t"), text = s.getString("text"),
                        state = LineState.fromWire(s.getString("state")) ?: LineState.UNSURE,
                        tier = s.optString("tier"), reason = s.optString("reason"),
                        label = if (s.isNull("label")) null else LineState.fromWire(s.getString("label")),
                    )
                },
            )
        }.getOrNull()
    }.sortedByDescending { it.startedAt }
}

package app.holdassistant.data

import android.content.Context
import app.holdassistant.core.Fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A number the user calls and holds on: a bank, a carrier, an agency. */
data class Line(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val number: String, // E.164
    val category: String = "",
    val probe: Boolean? = null, // null = use the settings default
    val fingerprint: Fingerprint = Fingerprint(),
    val calls: Int = 0,
)

/** Saved lines, kept as one JSON file. */
class LineStore(context: Context) {
    private val file = File(context.filesDir, "lines.json")
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<Line>> = _lines

    fun upsert(line: Line) = save(_lines.value.filter { it.id != line.id } + line)

    fun addAll(new: List<Line>) {
        val known = _lines.value.map { it.number }.toSet()
        save(_lines.value + new.filter { it.number !in known }.distinctBy { it.number })
    }

    fun delete(id: String) = save(_lines.value.filter { it.id != id })

    fun byNumber(number: String): Line? = _lines.value.firstOrNull { it.number == number }

    /** Merge what this call learned about the number's hold loop and menus. */
    fun learn(number: String, fp: Fingerprint) {
        val line = byNumber(number) ?: return
        fun merge(old: List<String>, new: List<String>) = (new + old).distinct().take(MAX_FINGERPRINT)
        upsert(
            line.copy(
                fingerprint = Fingerprint(merge(line.fingerprint.hold, fp.hold), merge(line.fingerprint.ivrMenu, fp.ivrMenu)),
                calls = line.calls + 1,
            ),
        )
    }

    private fun save(list: List<Line>) {
        val sorted = list.sortedBy { it.name.lowercase() }
        _lines.value = sorted
        val arr = JSONArray()
        sorted.forEach { l ->
            arr.put(
                JSONObject()
                    .put("id", l.id).put("name", l.name).put("number", l.number).put("category", l.category)
                    .put("probe", l.probe ?: JSONObject.NULL).put("calls", l.calls)
                    .put("hold", JSONArray(l.fingerprint.hold)).put("menu", JSONArray(l.fingerprint.ivrMenu)),
            )
        }
        file.writeText(arr.toString())
    }

    private fun load(): List<Line> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(
                id = o.getString("id"),
                name = o.getString("name"),
                number = o.getString("number"),
                category = o.optString("category"),
                probe = if (o.isNull("probe")) null else o.getBoolean("probe"),
                fingerprint = Fingerprint(o.optJSONArray("hold").strings(), o.optJSONArray("menu").strings()),
                calls = o.optInt("calls"),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_FINGERPRINT = 30
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

package app.holdassistant.history

import android.content.Context
import android.provider.CallLog
import app.holdassistant.core.CallRecord

/** Reads the phone's call log (needs READ_CALL_LOG). Calls placed by this app aren't in it. */
object CallLogReader {
    fun read(context: Context, limit: Int = 3000): List<CallRecord> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        val out = ArrayList<CallRecord>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val type = when (c.getInt(2)) {
                    CallLog.Calls.OUTGOING_TYPE -> CallRecord.Type.OUTGOING
                    CallLog.Calls.INCOMING_TYPE -> CallRecord.Type.INCOMING
                    CallLog.Calls.MISSED_TYPE -> CallRecord.Type.MISSED
                    else -> CallRecord.Type.OTHER
                }
                out += CallRecord(number, c.getString(1), type, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }
}

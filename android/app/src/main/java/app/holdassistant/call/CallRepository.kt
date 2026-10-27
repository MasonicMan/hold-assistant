package app.holdassistant.call

import app.holdassistant.core.LineState
import app.holdassistant.core.MenuOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TranscriptLine(val t: Double, val text: String, val state: LineState, val reason: String)

enum class CallStatus { IDLE, CONNECTING, RINGING, CONNECTED, RECONNECTING, ENDED }

data class CallUiState(
    val active: Boolean = false,
    val name: String = "",
    val number: String = "",
    val status: CallStatus = CallStatus.IDLE,
    val message: String? = null, // errors and end-of-call notes
    val lineState: LineState = LineState.HOLD,
    val listening: Boolean = false,
    val transcript: List<TranscriptLine> = emptyList(),
    val interim: String = "",
    val menuOptions: List<MenuOption> = emptyList(),
    val probeEnabled: Boolean = false,
    val probeStatus: String? = null,
    val startedAt: Long = 0L,
    val speakerOn: Boolean = false,
    val alert: String? = null,
)

/** Live call state shared between the service and the UI. */
object CallRepository {
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state

    fun update(f: (CallUiState) -> CallUiState) = _state.update(f)

    fun reset() {
        _state.value = CallUiState()
    }
}

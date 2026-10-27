package app.holdassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.holdassistant.HoldApp
import app.holdassistant.call.CallStatus
import app.holdassistant.call.CallUiState
import app.holdassistant.call.HoldCallService
import app.holdassistant.core.LineState
import app.holdassistant.core.PhoneNumbers
import kotlinx.coroutines.delay

fun stateColor(s: LineState): Color = when (s) {
    LineState.HOLD -> Color(0xFF607D8B)
    LineState.IVR_MENU -> Color(0xFF1E88E5)
    LineState.HUMAN -> Color(0xFF2E7D32)
    LineState.UNSURE -> Color(0xFFEF6C00)
}

fun stateName(s: LineState) = when (s) {
    LineState.HOLD -> "Hold"
    LineState.IVR_MENU -> "Menu"
    LineState.HUMAN -> "Person"
    LineState.UNSURE -> "Unsure"
}

@Composable
fun StateChip(s: LineState, modifier: Modifier = Modifier) {
    Text(
        stateName(s),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(stateColor(s), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallScreen(state: CallUiState, onDone: () -> Unit, onViewLog: (String) -> Unit) {
    val context = LocalContext.current
    val send = { action: String, digits: String?, on: Boolean? -> HoldCallService.send(context, action, digits, on) }
    var keypad by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active) {
        while (state.active) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.size)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.name, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PhoneNumbers.pretty(state.number), style = MaterialTheme.typography.bodyMedium)
                val elapsed = ((now - state.startedAt) / 1000).coerceAtLeast(0)
                if (state.active) Text("%d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    CallStatus.CONNECTED -> StateChip(state.lineState)
                    else -> Text(
                        when (state.status) {
                            CallStatus.CONNECTING -> "Connecting…"
                            CallStatus.RINGING -> "Ringing…"
                            CallStatus.RECONNECTING -> "Reconnecting…"
                            CallStatus.ENDED -> "Call ended"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (state.active) {
                    Text(
                        if (state.listening) "You're on the line" else "Playing your audio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.alert?.let {
                Card(colors = CardDefaults.cardColors(containerColor = stateColor(state.lineState).copy(alpha = 0.18f))) {
                    Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            state.probeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Card(Modifier.weight(1f).fillMaxWidth()) {
                if (state.transcript.isEmpty() && state.interim.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "What the line says shows up here, even while you're listening to something else.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.transcript) { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StateChip(line.state)
                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (state.interim.isNotEmpty()) {
                            item { Text(state.interim, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }

            if (state.active) {
                if (state.menuOptions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.menuOptions.forEach { o ->
                            FilledTonalButton(onClick = { send(HoldCallService.ACTION_DIGITS, o.digit, null) }) { Text("${o.digit} · ${o.label}") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.listening) {
                        OutlinedButton(onClick = { send(HoldCallService.ACTION_HOLD, null, null) }, Modifier.weight(1f)) { Text("Back to hold") }
                    } else {
                        OutlinedButton(onClick = { send(HoldCallService.ACTION_LISTEN, null, null) }, Modifier.weight(1f)) { Text("Listen now") }
                    }
                    OutlinedButton(onClick = { keypad = true }, Modifier.weight(1f)) { Text("Keypad") }
                    OutlinedButton(onClick = { send(HoldCallService.ACTION_SPEAKER, null, !state.speakerOn) }, Modifier.weight(1f)) {
                        Text(if (state.speakerOn) "Earpiece" else "Speaker")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Verify with a keyword", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.probeEnabled, onCheckedChange = { send(HoldCallService.ACTION_PROBE, null, it) })
                }
                Button(
                    onClick = { send(HoldCallService.ACTION_HANGUP, null, null) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Hang up") }
            } else {
                val log = HoldApp.instance.callLogs.logs.value.firstOrNull { it.id == state.startedAt.toString() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (log != null) OutlinedButton(onClick = { onViewLog(log.id) }, Modifier.weight(1f)) { Text("Review transcript") }
                    Button(onClick = onDone, Modifier.weight(1f)) { Text("Done") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (keypad) {
        AlertDialog(
            onDismissRequest = { keypad = false },
            confirmButton = { TextButton(onClick = { keypad = false }) { Text("Close") } },
            title = { Text("Keypad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("123", "456", "789", "*0#").forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { d ->
                                FilledTonalButton(onClick = { send(HoldCallService.ACTION_DIGITS, d.toString(), null) }, Modifier.weight(1f)) {
                                    Text(d.toString(), style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

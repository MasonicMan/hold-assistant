package app.holdassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.holdassistant.HoldApp
import app.holdassistant.core.PhoneNumbers
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSetup: () -> Unit) {
    val s = HoldApp.instance.settings
    var probe by remember { mutableStateOf(s.probeByDefault) }
    var autoReturn by remember { mutableStateOf(s.autoReturnToHold) }
    var replay by remember { mutableFloatStateOf(s.replaySeconds.toFloat()) }

    fun save() {
        s.probeByDefault = probe
        s.autoReturnToHold = autoReturn
        s.replaySeconds = replay.roundToInt()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { save() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Accounts and keys", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                listOf(
                    if (s.twilioReady) "Calling: ready, caller ID ${PhoneNumbers.pretty(s.callerId)}" else "Calling: not set up",
                    when {
                        s.deepgramKey.isNotEmpty() -> "Transcription: Deepgram"
                        s.onDeviceTranscription -> "Transcription: on-device (free)"
                        else -> "Transcription: needs a Deepgram key"
                    },
                    if (s.anthropicKey.isNotEmpty()) "AI: Claude Haiku" else "AI: off (rules only)",
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onSetup) { Text("Twilio, AI and transcription setup") }

            HorizontalDivider()
            Text("Behavior", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ToggleRow(
                "Verify with a keyword",
                "When unsure, the app asks the line to say a word like \"pineapple\" (it says it's an automated assistant). Can confuse some phone menus.",
                probe,
            ) { probe = it }
            ToggleRow(
                "Return to my audio automatically",
                "If a menu or maybe-person turns out to be more hold, go back to your music without a tap.",
                autoReturn,
            ) { autoReturn = it }
            Text("Replay when alerted: ${replay.roundToInt()} seconds", style = MaterialTheme.typography.bodyLarge)
            Slider(value = replay, onValueChange = { replay = it }, valueRange = 0f..15f, steps = 14)

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

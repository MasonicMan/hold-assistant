package app.holdassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.holdassistant.HoldApp
import app.holdassistant.core.PhoneNumbers
import app.holdassistant.core.TwilioSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.holdassistant.data.Line

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit, onCall: (number: String, name: String) -> Unit) {
    val app = HoldApp.instance
    val lines by app.lines.lines.collectAsStateWithLifecycle()
    var dialing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Line?>(null) }
    var balance by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val st = app.settings
        if (st.twilioReady && st.authToken.isNotEmpty()) {
            balance = withContext(Dispatchers.IO) {
                runCatching { TwilioSetup(st.accountSid, st.authToken).balance().toString() }.getOrNull()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hold Assistant") },
                actions = {
                    TextButton(onClick = { onNavigate(Screen.History) }) { Text("History") }
                    IconButton(onClick = { onNavigate(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialing = true },
                icon = { Icon(Icons.Default.Call, null) },
                text = { Text("Dial a number") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!app.settings.callingReady) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        onClick = { onNavigate(Screen.Setup) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Set up calling", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Connect your Twilio account and add an AI key. Takes a few minutes, all from here.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            balance?.let { b ->
                item {
                    Text(
                        "Twilio balance: $b",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedButton(onClick = { onNavigate(Screen.Import) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add banks and companies from call history")
                }
            }
            if (lines.isEmpty()) {
                item {
                    Text(
                        "No saved lines yet. Add them from your call history, or dial a number.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(lines, key = { it.id }) { line ->
                LineCard(line, onCall = { onCall(line.number, line.name) }, onDelete = { deleting = line })
            }
        }
    }

    if (dialing) DialDialog(onDismiss = { dialing = false }, onCall = { number, name, save ->
        dialing = false
        if (save) app.lines.upsert(Line(name = name.ifBlank { PhoneNumbers.pretty(number) }, number = number))
        onCall(number, name.ifBlank { PhoneNumbers.pretty(number) })
    })

    deleting?.let { line ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${line.name}?") },
            text = { Text("This also forgets the hold messages learned for this number.") },
            confirmButton = { TextButton(onClick = { app.lines.delete(line.id); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LineCard(line: Line, onCall: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleMedium)
                Text(PhoneNumbers.pretty(line.number), style = MaterialTheme.typography.bodyMedium)
                val learned = line.fingerprint.hold.size + line.fingerprint.ivrMenu.size
                val meta = listOfNotNull(
                    line.category.takeIf { it.isNotBlank() },
                    if (line.calls > 0) "${line.calls} call${if (line.calls == 1) "" else "s"}" else null,
                    if (learned > 0) "knows $learned recordings" else null,
                )
                if (meta.isNotEmpty()) {
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            FilledIconButton(onClick = onCall) { Icon(Icons.Default.Call, "Call ${line.name}") }
        }
    }
}

@Composable
private fun DialDialog(onDismiss: () -> Unit, onCall: (number: String, name: String, save: Boolean) -> Unit) {
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val e164 = PhoneNumbers.toE164(number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dial a number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number, onValueChange = { number = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = number.isNotBlank() && e164 == null,
                    supportingText = { if (number.isNotBlank() && e164 == null) Text("Enter a full number with area code") },
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (to save it)") }, singleLine = true)
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = e164 != null && name.isNotBlank(), onClick = { onCall(e164!!, name, true) }) { Text("Save & call") }
                Button(enabled = e164 != null, onClick = { onCall(e164!!, name, false) }) { Text("Call") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

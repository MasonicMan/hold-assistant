package app.holdassistant.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.holdassistant.HoldApp
import app.holdassistant.core.LineState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val logs by HoldApp.instance.callLogs.logs.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call history") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (logs.isEmpty()) item { Text("Calls you make show up here with their transcripts.") }
            items(logs, key = { it.id }) { log ->
                Card(onClick = { onOpen(log.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(log.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(log.startedAt)) +
                                " · ${log.durationS / 60} min · ${log.segments.size} lines",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(log.outcome, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = HoldApp.instance.callLogs
    val logs by store.logs.collectAsStateWithLifecycle()
    val log = logs.firstOrNull { it.id == id }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(log?.name ?: "Call") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (log != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Hold Assistant scenario: ${log.name}")
                                .putExtra(Intent.EXTRA_TEXT, store.exportScenario(log))
                            context.startActivity(Intent.createChooser(send, "Share transcript as a test scenario"))
                        }) { Icon(Icons.Default.Share, "Share as test scenario") }
                        IconButton(onClick = { store.delete(log.id); onBack() }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        if (log == null) {
            Text("Call not found.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Tap a label to correct it (hold → menu → person → unsure). Corrections are included when you share the " +
                        "transcript, which turns it into a test case for the classifier.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(log.segments) { i, seg ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val next = LineState.entries[(seg.effectiveLabel.ordinal + 1) % LineState.entries.size]
                    StateChip(seg.effectiveLabel, Modifier.clickable { store.relabel(log.id, i, if (next == seg.state) null else next) })
                    Column(Modifier.weight(1f)) {
                        Text("%.0fs  %s".format(seg.t, seg.text), style = MaterialTheme.typography.bodyMedium)
                        val corrected = seg.label != null
                        Text(
                            (if (corrected) "corrected · app said ${stateName(seg.state)} · " else "") + "${seg.tier}: ${seg.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (corrected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

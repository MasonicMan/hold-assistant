package app.holdassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.holdassistant.HoldApp
import app.holdassistant.core.CallHistoryClassifier
import app.holdassistant.core.ClaudeContactClassifier
import app.holdassistant.core.ClaudeNumberLookup
import app.holdassistant.core.ContactInfo
import app.holdassistant.core.ContactKind
import app.holdassistant.core.HistoryVerdict
import app.holdassistant.core.PhoneNumbers
import app.holdassistant.core.claudeClient
import app.holdassistant.data.Line
import app.holdassistant.history.CallLogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Duration
import java.util.Date

private data class Candidate(
    val verdict: HistoryVerdict,
    val kind: ContactKind,
    val name: String, // saved, looked-up or typed; empty if none
    val why: String,
    val category: String,
    val checked: Boolean,
    val alreadySaved: Boolean,
) {
    val number get() = verdict.history.number
    val title get() = name.ifEmpty { PhoneNumbers.pretty(number) }
}

private fun signals(v: HistoryVerdict) =
    v.reasons.filter { it.startsWith("+") }.joinToString(", ") { it.substringAfter(' ') }.ifEmpty { "no company signals" }

private fun usage(v: HistoryVerdict): String {
    val h = v.history
    val parts = mutableListOf("called ${h.outgoing}×")
    if (h.longestOutgoingS >= 60) parts += "longest ${h.longestOutgoingS / 60} min"
    parts += "last " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(h.lastCallMs))
    return parts.joinToString(", ")
}

/** Finds bank and company lines in the call log and adds the chosen ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = HoldApp.instance
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val candidates = remember { mutableStateListOf<Candidate>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var showPeople by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Candidate?>(null) }
    val hasKey = app.settings.anthropicKey.isNotEmpty()

    LaunchedEffect(granted) {
        if (!granted) {
            request.launch(Manifest.permission.READ_CALL_LOG)
            return@LaunchedEffect
        }
        val saved = app.lines.lines.value.map { it.number }.toSet()
        val list = withContext(Dispatchers.IO) {
            CallHistoryClassifier.analyze(CallLogReader.read(context)).map { v ->
                val already = v.history.number in saved
                Candidate(v, v.kind, v.history.name.orEmpty(), signals(v), "", v.kind == ContactKind.INSTITUTION && !already, already)
            }
        }
        candidates.clear()
        candidates.addAll(list)
        loading = false
    }

    fun update(number: String, f: (Candidate) -> Candidate) {
        val i = candidates.indexOfFirst { it.number == number }
        if (i >= 0) candidates[i] = f(candidates[i])
    }

    // Numbers without a name: Claude searches the web for who they belong to.
    val unnamed = candidates.filter { it.name.isEmpty() && !it.alreadySaved && (it.checked || it.kind == ContactKind.UNCLEAR) }.take(24)
    fun lookUpNames() {
        busy = "Looking up ${unnamed.size} numbers…"
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                ClaudeNumberLookup(claudeClient(app.settings.anthropicKey, Duration.ofSeconds(120))).lookup(unnamed.map { it.number })
            }
            names.forEach { (number, name) ->
                update(number) { it.copy(name = name, kind = ContactKind.INSTITUTION, checked = true, why = "found online") }
            }
            note = if (names.isEmpty()) {
                "Couldn't identify these numbers. Check your Anthropic key, or tap a number to name it yourself."
            } else {
                "Named ${names.size} of ${unnamed.size}. Tap the pencil to fix any name."
            }
            busy = null
        }
    }

    // Saved names that are neither clearly a company nor a person: Claude judges the name.
    val unclearNamed = candidates.filter { it.kind == ContactKind.UNCLEAR && it.name.isNotEmpty() }
    fun sortUnclear() {
        busy = "Asking Claude…"
        scope.launch {
            val labels = withContext(Dispatchers.IO) {
                ClaudeContactClassifier(claudeClient(app.settings.anthropicKey, Duration.ofSeconds(90)))
                    .classify(unclearNamed.map { ContactInfo(it.number, it.name, phones = listOf(ContactInfo.Phone(it.number))) })
            }
            labels.forEach { l ->
                val inst = l.institution && l.confidence >= 0.6
                update(l.id) {
                    it.copy(
                        kind = if (inst) ContactKind.INSTITUTION else ContactKind.PERSONAL,
                        why = "Claude: ${l.category}", category = if (inst) l.category else "", checked = inst && !it.alreadySaved,
                    )
                }
            }
            note = if (labels.isEmpty()) "Claude didn't answer. Check your Anthropic key in setup." else null
            busy = null
        }
    }

    val chosen = candidates.filter { it.checked }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add lines from call history") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = chosen.isNotEmpty() && busy == null,
                    onClick = {
                        app.lines.addAll(chosen.map { Line(name = it.title, number = it.number, category = it.category) })
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text(if (chosen.isEmpty()) "Select lines to add" else "Add ${chosen.size} lines") }
            }
        },
    ) { padding ->
        when {
            !granted -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Call history access lets the app find the banks and companies you've called, especially the long calls " +
                            "that were probably time on hold. Your call history stays on the phone.",
                    )
                    Button(onClick = { request.launch(Manifest.permission.READ_CALL_LOG) }) { Text("Allow call history") }
                }
            }
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val institutions = candidates.filter { it.kind == ContactKind.INSTITUTION }
                val unclear = candidates.filter { it.kind == ContactKind.UNCLEAR }
                val people = candidates.filter { it.kind == ContactKind.PERSONAL }
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Column(Modifier.padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (unnamed.isNotEmpty()) {
                                if (hasKey) {
                                    FilledTonalButton(enabled = busy == null, onClick = { lookUpNames() }) {
                                        Text("Look up names for ${unnamed.size} unknown numbers")
                                    }
                                    Hint("Sends only those phone numbers to Claude, which searches the web for them (about 1¢ each).")
                                } else {
                                    Hint("Unknown numbers show as digits. Add an Anthropic key in setup to look up their names, or tap the pencil to name one.")
                                }
                            }
                            if (hasKey && unclearNamed.isNotEmpty()) {
                                TextButton(enabled = busy == null, onClick = { sortUnclear() }) { Text("Ask Claude about ${unclearNamed.size} unclear names") }
                            }
                            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text(it) } }
                            note?.let { Hint(it) }
                        }
                    }
                    item { SectionHeader("Looks like a company (${institutions.size})") }
                    if (institutions.isEmpty()) item { Hint("None found. Check the unclear list below.") }
                    items(institutions, key = { "i" + it.number }) { c -> CandidateRow(c, { update(c.number) { it.copy(checked = !it.checked) } }, { renaming = c }) }

                    item { SectionHeader("Unclear (${unclear.size})") }
                    items(unclear, key = { "u" + it.number }) { c -> CandidateRow(c, { update(c.number) { it.copy(checked = !it.checked) } }, { renaming = c }) }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader("Probably people (${people.size})")
                            TextButton(onClick = { showPeople = !showPeople }) { Text(if (showPeople) "Hide" else "Show") }
                        }
                    }
                    if (showPeople) {
                        items(people, key = { "p" + it.number }) { c -> CandidateRow(c, { update(c.number) { it.copy(checked = !it.checked) } }, { renaming = c }) }
                    }
                }
            }
        }
    }

    renaming?.let { c ->
        var text by remember(c.number) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name ${PhoneNumbers.pretty(c.number)}") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Company name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    update(c.number) { it.copy(name = text.trim(), checked = it.checked || (text.isNotBlank() && !it.alreadySaved)) }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

@Composable
private fun CandidateRow(c: Candidate, onToggle: () -> Unit, onRename: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !c.alreadySaved, onClick = onToggle).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = c.checked || c.alreadySaved, enabled = !c.alreadySaved, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge)
            val detail = listOfNotNull(
                if (c.name.isNotEmpty()) PhoneNumbers.pretty(c.number) else null,
                usage(c.verdict),
                if (c.alreadySaved) "already added" else c.why,
            ).joinToString(" · ")
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!c.alreadySaved) IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Name this number") }
    }
}

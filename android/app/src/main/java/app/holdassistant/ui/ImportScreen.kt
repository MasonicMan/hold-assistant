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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.holdassistant.contacts.ContactsReader
import app.holdassistant.core.ClaudeContactClassifier
import app.holdassistant.core.ContactInfo
import app.holdassistant.core.ContactKind
import app.holdassistant.core.ContactVerdict
import app.holdassistant.core.InstitutionClassifier
import app.holdassistant.core.PhoneNumbers
import app.holdassistant.core.claudeClient
import app.holdassistant.data.Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration

private data class Candidate(
    val contact: ContactInfo,
    val number: String, // E.164
    val kind: ContactKind,
    val why: String,
    val category: String,
    val checked: Boolean,
    val alreadySaved: Boolean,
)

private fun pickNumber(c: ContactInfo): String? {
    val ranked = c.phones.sortedBy { p ->
        val d = p.number.filter(Char::isDigit)
        when {
            InstitutionClassifier.isTollFree(d) -> 0
            p.type == ContactInfo.PhoneType.MAIN || p.type == ContactInfo.PhoneType.WORK -> 1
            else -> 2
        }
    }
    return ranked.firstNotNullOfOrNull { PhoneNumbers.toE164(it.number) }
}

private fun summarize(v: ContactVerdict) =
    v.reasons.filter { it.startsWith("+") }.joinToString(", ") { it.substringAfter(' ') }.ifEmpty { "no strong signals" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = HoldApp.instance
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val candidates = remember { mutableStateListOf<Candidate>() }
    var loading by remember { mutableStateOf(true) }
    var asking by remember { mutableStateOf(false) }
    var claudeNote by remember { mutableStateOf<String?>(null) }
    var showPeople by remember { mutableStateOf(false) }

    LaunchedEffect(granted) {
        if (!granted) {
            request.launch(Manifest.permission.READ_CONTACTS)
            return@LaunchedEffect
        }
        val saved = app.lines.lines.value.map { it.number }.toSet()
        val list = withContext(Dispatchers.IO) {
            ContactsReader.read(context).mapNotNull { c ->
                val number = pickNumber(c) ?: return@mapNotNull null
                val v = InstitutionClassifier.classify(c)
                val already = number in saved
                Candidate(c, number, v.kind, summarize(v), "", v.kind == ContactKind.INSTITUTION && !already, already)
            }.sortedBy { it.contact.displayName.lowercase() }
        }
        candidates.clear()
        candidates.addAll(list)
        loading = false
    }

    fun toggle(c: Candidate) {
        val i = candidates.indexOfFirst { it.contact.id == c.contact.id }
        if (i >= 0) candidates[i] = c.copy(checked = !c.checked)
    }

    fun askClaude() {
        val key = app.settings.anthropicKey
        val unclear = candidates.filter { it.kind == ContactKind.UNCLEAR }
        if (key.isEmpty() || unclear.isEmpty()) return
        asking = true
        scope.launch {
            val labels = withContext(Dispatchers.IO) {
                ClaudeContactClassifier(claudeClient(key, Duration.ofSeconds(90))).classify(unclear.map { it.contact })
            }.associateBy { it.id }
            for (i in candidates.indices) {
                val c = candidates[i]
                val l = labels[c.contact.id] ?: continue
                if (c.kind != ContactKind.UNCLEAR) continue
                val inst = l.institution && l.confidence >= 0.6
                candidates[i] = c.copy(
                    kind = if (inst) ContactKind.INSTITUTION else ContactKind.PERSONAL,
                    why = "Claude: ${l.category} (${(l.confidence * 100).toInt()}%)",
                    category = if (inst) l.category else "",
                    checked = inst && !c.alreadySaved,
                )
            }
            claudeNote = if (labels.isEmpty()) "Claude didn't answer. Check your Anthropic key in Settings." else "Claude sorted ${labels.size} contacts."
            asking = false
        }
    }

    val chosen = candidates.filter { it.checked }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from contacts") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        app.lines.addAll(chosen.map { Line(name = it.contact.displayName, number = it.number, category = it.category) })
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
                    Text("Contacts access is needed to find your banks and companies. Your contacts stay on the phone.")
                    Button(onClick = { request.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts") }
                }
            }
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val institutions = candidates.filter { it.kind == ContactKind.INSTITUTION }
                val unclear = candidates.filter { it.kind == ContactKind.UNCLEAR }
                val people = candidates.filter { it.kind == ContactKind.PERSONAL }
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { SectionHeader("Looks like an institution (${institutions.size})") }
                    if (institutions.isEmpty()) item { Hint("None found. Check the unclear list below.") }
                    items(institutions, key = { "i" + it.contact.id }) { CandidateRow(it) { toggle(it) } }

                    item { SectionHeader("Unclear (${unclear.size})") }
                    if (unclear.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                if (app.settings.anthropicKey.isNotEmpty()) {
                                    FilledTonalButton(enabled = !asking, onClick = { askClaude() }) {
                                        Text(if (asking) "Asking Claude…" else "Ask Claude to sort these")
                                    }
                                    Hint("Sends only names and company fields, never phone numbers.")
                                } else {
                                    Hint("Add an Anthropic key in Settings to have Claude sort these.")
                                }
                            }
                        }
                    }
                    claudeNote?.let { item { Hint(it) } }
                    items(unclear, key = { "u" + it.contact.id }) { CandidateRow(it) { toggle(it) } }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader("People (${people.size})")
                            TextButton(onClick = { showPeople = !showPeople }) { Text(if (showPeople) "Hide" else "Show") }
                        }
                    }
                    if (showPeople) items(people, key = { "p" + it.contact.id }) { CandidateRow(it) { toggle(it) } }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

@Composable
private fun CandidateRow(c: Candidate, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !c.alreadySaved, onClick = onToggle).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = c.checked || c.alreadySaved, enabled = !c.alreadySaved, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(c.contact.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                PhoneNumbers.pretty(c.number) + " · " + if (c.alreadySaved) "already added" else c.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

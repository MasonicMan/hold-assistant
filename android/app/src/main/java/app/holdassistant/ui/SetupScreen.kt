package app.holdassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.holdassistant.HoldApp
import app.holdassistant.core.PhoneNumbers
import app.holdassistant.core.TwilioAccount
import app.holdassistant.core.TwilioBalance
import app.holdassistant.core.TwilioSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun openUrl(context: Context, url: String) =
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/**
 * One screen that takes a Twilio Account SID + Auth Token and does the rest:
 * checks the account and balance, sets the caller ID (verifying the user's
 * cell by phone call), and creates the API key, call function and TwiML app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val s = HoldApp.instance.settings
    val scope = rememberCoroutineScope()

    var sid by remember { mutableStateOf(s.accountSid) }
    var token by remember { mutableStateOf(s.authToken) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<TwilioAccount?>(null) }
    var balance by remember { mutableStateOf<TwilioBalance?>(null) }
    var callerIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var callerId by remember { mutableStateOf(s.callerId) }
    var myNumber by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var callingDone by remember { mutableStateOf(s.twilioReady) }
    var anthropic by remember { mutableStateOf(s.anthropicKey) }
    var deepgram by remember { mutableStateOf(s.deepgramKey) }

    fun twilio() = TwilioSetup(sid.trim(), token.trim())

    fun task(label: String, block: suspend () -> Unit) {
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    fun connect() = task("Checking your Twilio account…") {
        val t = twilio()
        val (a, b, ids) = withContext(Dispatchers.IO) { Triple(t.account(), t.balance(), t.callerIds()) }
        s.accountSid = sid
        s.authToken = token
        account = a
        balance = b
        callerIds = ids
        if (callerId.isEmpty() && ids.size == 1) callerId = ids[0]
    }

    // Reconnect quietly when coming back to this screen.
    LaunchedEffect(Unit) {
        if (s.accountSid.isNotEmpty() && s.authToken.isNotEmpty()) connect()
    }

    fun verify() {
        val number = PhoneNumbers.toE164(myNumber)
        if (number == null) {
            error = "Enter your full number with area code"
            return
        }
        task("Twilio is calling $number…") {
            val t = twilio()
            code = withContext(Dispatchers.IO) { t.startCallerIdVerification(number) }
            repeat(60) {
                delay(3000)
                if (withContext(Dispatchers.IO) { t.isCallerId(number) }) {
                    callerIds = (callerIds + number).distinct()
                    callerId = number
                    code = null
                    return@task
                }
            }
            code = null
            error = "Didn't get the code in time. Try again, and type the code when Twilio calls."
        }
    }

    fun provision() = task("Setting up calling…") {
        s.callerId = callerId
        val cfg = withContext(Dispatchers.IO) { twilio().provision { step -> busy = "$step…" } }
        s.saveCallingConfig(cfg)
        callingDone = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up calling") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Twilio account
            Step(1, "Twilio account", done = account != null && account?.trial == false) {
                Text(
                    "Calls go through your own Twilio account, so you pay Twilio directly (about 2¢ a minute). " +
                        "Twilio doesn't let apps take payment for it, so creating the account and adding a card happen on twilio.com. " +
                        "This app never sees your card.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openUrl(context, "https://www.twilio.com/try-twilio") }) { Text("Create account") }
                    OutlinedButton(onClick = { openUrl(context, "https://console.twilio.com/") }) { Text("Open Twilio console") }
                }
                Text(
                    "On the console home page, copy the Account SID and Auth Token into the fields below.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(sid, { sid = it.trim() }, label = { Text("Account SID (starts with AC)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it.trim() }, label = { Text("Auth Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = busy == null && sid.startsWith("AC") && token.length >= 20, onClick = { connect() }) {
                    Text(if (account == null) "Connect" else "Refresh")
                }
                account?.let { a ->
                    Text("Connected: ${a.name}" + (balance?.let { " · balance $it" } ?: ""), fontWeight = FontWeight.SemiBold)
                    if (a.trial) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("This is a trial account", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Trial accounts can only call numbers you've verified, and they play a trial message first. " +
                                        "To call banks and companies, tap Upgrade in the Twilio console, add a card, and add funds " +
                                        "(\$20 lasts many hours of hold). Then come back and tap Refresh.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                FilledTonalButton(onClick = { openUrl(context, "https://console.twilio.com/") }) { Text("Upgrade on twilio.com") }
                            }
                        }
                    }
                }
            }

            // 2. Caller ID
            Step(2, "Caller ID", done = callerId.isNotEmpty() && callerId in callerIds) {
                Text(
                    "The number companies see when you call. Use your own cell so your bank recognizes you. Twilio confirms it with a quick call.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                callerIds.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { callerId = n }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = callerId == n, onClick = { callerId = n })
                        Text(PhoneNumbers.pretty(n))
                    }
                }
                if (account != null) {
                    OutlinedTextField(
                        myNumber, { myNumber = it }, label = { Text(if (callerIds.isEmpty()) "Your cell number" else "Add another number") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(enabled = busy == null && myNumber.isNotBlank(), onClick = { verify() }) { Text("Verify by phone call") }
                }
                code?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Answer Twilio's call and type this code:")
                            Text(it, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                        }
                    }
                }
            }

            // 3. Create everything
            Step(3, "Finish calling setup", done = callingDone) {
                Text(
                    "Creates an API key, a small call function and a TwiML app in your Twilio account. Takes about 30 seconds; safe to re-run.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = busy == null && account != null && callerId in callerIds,
                    onClick = { provision() },
                ) { Text(if (callingDone) "Run setup again" else "Set up calling") }
            }

            // 4. AI and transcription
            Step(4, "AI key (optional, recommended)", done = anthropic.isNotEmpty()) {
                Text(
                    "Claude Haiku judges lines the built-in rules can't place (fewer false alarms from hold ads) and helps sort contacts. " +
                        "Costs a fraction of a cent per call.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(anthropic, { anthropic = it.trim(); s.anthropicKey = it }, label = { Text("Anthropic API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { openUrl(context, "https://console.anthropic.com/settings/keys") }) { Text("Get a key") }
            }

            Step(5, "Transcription", done = s.transcriptionReady || deepgram.isNotEmpty()) {
                Text(
                    if (s.onDeviceTranscription) {
                        "Your phone can transcribe calls on its own for free. A Deepgram key is optional; it's more accurate on phone audio (under 1¢ a minute, with free starting credit)."
                    } else {
                        "This phone can't transcribe calls on its own, so a Deepgram key is needed (under 1¢ a minute, with free starting credit)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(deepgram, { deepgram = it.trim(); s.deepgramKey = it }, label = { Text("Deepgram API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { openUrl(context, "https://console.deepgram.com/") }) { Text("Get a key") }
            }

            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(it)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = onDone, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (callingDone && account?.trial == false && (s.transcriptionReady || deepgram.isNotEmpty())) "Done: start calling" else "Close")
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) Icon(Icons.Default.Check, "Done", tint = MaterialTheme.colorScheme.primary)
                Text("$n. $title", style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

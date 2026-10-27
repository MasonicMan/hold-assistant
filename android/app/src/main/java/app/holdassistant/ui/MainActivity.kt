package app.holdassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.holdassistant.call.CallRepository
import app.holdassistant.call.CallStatus
import app.holdassistant.call.HoldCallService

sealed interface Screen {
    data object Home : Screen
    data object Import : Screen
    data object Settings : Screen
    data object History : Screen
    data class CallDetail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HoldTheme { App() } }
    }
}

@Composable
fun HoldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF4DB6AC))
        else -> lightColorScheme(primary = Color(0xFF00695C))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val call by CallRepository.state.collectAsStateWithLifecycle()
    var pendingCall by remember { mutableStateOf<Pair<String, String>?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val p = pendingCall
        pendingCall = null
        if (p != null && granted[Manifest.permission.RECORD_AUDIO] != false &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            HoldCallService.start(context, p.first, p.second)
        } else if (p != null) {
            Toast.makeText(context, "The microphone permission is needed to place calls.", Toast.LENGTH_LONG).show()
        }
    }

    val placeCall: (String, String) -> Unit = { number, name ->
        pendingCall = number to name
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.launch(needed.toTypedArray())
    }

    if (call.status != CallStatus.IDLE) {
        CallScreen(
            state = call,
            onDone = { CallRepository.reset() },
            onViewLog = { id -> CallRepository.reset(); screen = Screen.CallDetail(id) },
        )
        return
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen is Screen.CallDetail) Screen.History else Screen.Home
    }
    when (val s = screen) {
        Screen.Home -> HomeScreen(onNavigate = { screen = it }, onCall = placeCall)
        Screen.Import -> ImportScreen(onDone = { screen = Screen.Home })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        Screen.History -> HistoryScreen(onBack = { screen = Screen.Home }, onOpen = { screen = Screen.CallDetail(it) })
        is Screen.CallDetail -> CallDetailScreen(s.id, onBack = { screen = Screen.History })
    }
}

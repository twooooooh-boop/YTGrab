@file:OptIn(ExperimentalMaterial3Api::class)

package com.ytgrab.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List as QueueIcon
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val REPO_URL = "github.com/twooooooh-boop/YTGrab"
const val APP_VERSION = "2.4"

/** Which bottom tab is open (shared so other screens can jump to the queue). */
object Nav {
    var tab by mutableIntStateOf(0)
}

/** Which result card is open, and whether its quality list is open (one at a time, so Back can close them). */
object UiState {
    var expandedUrl by mutableStateOf<String?>(null)
    var qOpenUrl by mutableStateOf<String?>(null)
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val tasks by DownloadCenter.tasks.collectAsStateWithLifecycle()
    val active = tasks.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }

    val focus = LocalFocusManager.current

    // Android's Back key: step back one level at a time, and only leave the app from the Search home.
    val canGoBack = Nav.tab != 0 || SearchState.focused || UiState.qOpenUrl != null ||
        UiState.expandedUrl != null || SearchState.results.isNotEmpty() || SearchState.error != null
    BackHandler(enabled = canGoBack) {
        when {
            Nav.tab != 0 -> Nav.tab = 0
            SearchState.focused -> focus.clearFocus()
            UiState.qOpenUrl != null -> UiState.qOpenUrl = null
            UiState.expandedUrl != null -> UiState.expandedUrl = null
            else -> SearchState.clearResults()
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = Nav.tab == 0, onClick = { Nav.tab = 0 },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = Nav.tab == 1, onClick = { Nav.tab = 1 },
                    icon = {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.AutoMirrored.Filled.QueueIcon, null)
                        }
                    },
                    label = { Text("Queue") },
                )
                NavigationBarItem(
                    selected = Nav.tab == 2, onClick = { Nav.tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") },
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (Nav.tab) {
                0 -> SearchScreen()
                1 -> QueueScreen(tasks)
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(emoji: String, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.displayMedium)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

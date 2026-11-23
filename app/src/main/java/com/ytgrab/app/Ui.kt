@file:OptIn(ExperimentalMaterial3Api::class)

package com.ytgrab.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List as QueueIcon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val REPO_URL = "github.com/twooooooh-boop/YTGrab"

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tasks by DownloadCenter.tasks.collectAsStateWithLifecycle()
    val active = tasks.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.AutoMirrored.Filled.QueueIcon, null)
                        }
                    },
                    label = { Text("Queue") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") },
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SearchScreen()
                1 -> QueueScreen(tasks)
                else -> SettingsScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- Search

@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val ready by Engine.ready.collectAsStateWithLifecycle()
    val status by Engine.status.collectAsStateWithLifecycle()
    val doSearch = { focus.clearFocus(); SearchState.search() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = SearchState.query,
            onValueChange = { SearchState.query = it },
            label = { Text("Search name or paste link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            trailingIcon = {
                Row {
                    if (SearchState.query.isNotEmpty()) {
                        IconButton(onClick = { SearchState.query = "" }) { Icon(Icons.Default.Close, "Clear") }
                    }
                    IconButton(onClick = { doSearch() }) { Icon(Icons.Default.Search, "Search") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        OptionsCard()
        if (!ready) Text(status, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)

        when {
            SearchState.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                SearchState.error?.let {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
                val results = SearchState.results
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (results.size > 1) {
                        item {
                            Button(
                                onClick = {
                                    DownloadCenter.enqueue(ctx, results, Prefs.options())
                                    Toast.makeText(ctx, "Added ${results.size} to queue", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download all (${results.size}) as ${if (Prefs.mode == Mode.VIDEO) "video" else "audio"}") }
                        }
                    }
                    items(results) { ResultRow(it) }
                }
            }
        }
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(label(opt)) })
        }
    }
}

@Composable
fun OptionsCard() {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipRow(
                listOf(Mode.VIDEO, Mode.AUDIO), Prefs.mode,
                { if (it == Mode.VIDEO) "Video" else "Audio" },
            ) { Prefs.mode = it; Prefs.save() }

            if (Prefs.mode == Mode.VIDEO) {
                ChipRow(
                    listOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144), Prefs.videoHeight,
                    { if (it == 0) "Best" else "${it}p" },
                ) { Prefs.videoHeight = it; Prefs.save() }
            } else {
                ChipRow(listOf("m4a", "mp3", "opus"), Prefs.audioFormat, { it }) {
                    Prefs.audioFormat = it; Prefs.save()
                }
                ChipRow(listOf("Best", "320K", "192K", "128K"), Prefs.audioBitrate, { it }) {
                    Prefs.audioBitrate = it; Prefs.save()
                }
            }

            Text("Threads: ${Prefs.threads}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = Prefs.threads.toFloat(),
                onValueChange = { Prefs.threads = it.roundToInt() },
                onValueChangeFinished = { Prefs.save() },
                valueRange = 1f..16f,
                steps = 14,
            )
        }
    }
}

@Composable
fun ResultRow(item: SearchItem) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable(item.url) { mutableStateOf(false) }
    var mode by rememberSaveable(item.url) { mutableStateOf(Prefs.mode) }
    var height by rememberSaveable(item.url) { mutableStateOf(Prefs.videoHeight) }
    var aFmt by rememberSaveable(item.url) { mutableStateOf(Prefs.audioFormat) }
    var aBr by rememberSaveable(item.url) { mutableStateOf(Prefs.audioBitrate) }
    var info by remember(item.url) { mutableStateOf(FormatCache.get(item.url)) }
    var loading by remember(item.url) { mutableStateOf(false) }
    var failed by remember(item.url) { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded && info == null && !loading) {
            loading = true
            failed = false
            try {
                val i = Engine.formats(item.url)
                FormatCache.put(item.url, i)
                info = i
                if (height != 0 && i.videoSizes.isNotEmpty() && i.videoSizes.none { it.first == height }) {
                    height = i.videoSizes.firstOrNull { it.first <= height }?.first ?: 0
                }
            } catch (e: Throwable) {
                failed = true
            }
            loading = false
        }
    }

    val sel = info?.let { if (mode == Mode.VIDEO) it.videoSize(height) else audioSize(it, aFmt, aBr) } ?: 0L

    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = item.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOf(item.channel, fmtDur(item.durationSec)).filter { it.isNotBlank() }.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (expanded) "Tap to close" else "Tap to choose format & size",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (expanded) {
                Column(
                    Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChipRow(
                        listOf(Mode.VIDEO, Mode.AUDIO), mode,
                        { if (it == Mode.VIDEO) "Video" else "Audio" },
                    ) { mode = it }

                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Getting sizes…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (failed) {
                        Text("Couldn't read sizes, you can still download.", style = MaterialTheme.typography.bodySmall)
                    }

                    if (mode == Mode.VIDEO) {
                        val opts = listOf(0) + (info?.videoSizes?.map { it.first } ?: listOf(2160, 1440, 1080, 720, 480, 360, 240, 144))
                        ChipRow(
                            opts, height,
                            { h ->
                                val s = info?.videoSize(h) ?: 0L
                                (if (h == 0) "Best" else "${h}p") + (if (s > 0) " · ${fmtSize(s)}" else "")
                            },
                        ) { height = it }
                    } else {
                        ChipRow(listOf("m4a", "mp3", "opus"), aFmt, { it }) { aFmt = it }
                        ChipRow(
                            listOf("Best", "320K", "192K", "128K"), aBr,
                            { b ->
                                val s = info?.let { audioSize(it, aFmt, b) } ?: 0L
                                b + (if (s > 0) " · ≈${fmtSize(s)}" else "")
                            },
                        ) { aBr = it }
                    }

                    Button(onClick = {
                        DownloadCenter.enqueue(
                            ctx, listOf(item),
                            Prefs.options().copy(mode = mode, videoHeight = height, audioFormat = aFmt, audioBitrate = aBr),
                        )
                        Toast.makeText(ctx, "Added to queue", Toast.LENGTH_SHORT).show()
                        expanded = false
                    }) {
                        Text(
                            (if (mode == Mode.VIDEO) "Download video" else "Download audio") +
                                (if (sel > 0) " · ≈ ${fmtSize(sel)}" else "")
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Queue

private fun optLabel(o: DownloadOptions): String =
    if (o.mode == Mode.VIDEO)
        "Video · " + (if (o.videoHeight == 0) "Best" else "${o.videoHeight}p") + " · ${o.threads} threads"
    else "Audio · ${o.audioFormat} · ${o.audioBitrate} · ${o.threads} threads"

@Composable
fun QueueScreen(tasks: List<DownloadTask>) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { DownloadCenter.clearFinished() }) { Text("Clear finished") }
        }
        if (tasks.isEmpty()) {
            Text("Nothing here yet. Search and tap Download.", Modifier.padding(12.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks.reversed(), key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t.item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(optLabel(t.options), style = MaterialTheme.typography.bodySmall)
                        if (t.state == TaskState.RUNNING || t.state == TaskState.QUEUED) {
                            LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        val status = when (t.state) {
                            TaskState.QUEUED -> "Queued"
                            TaskState.RUNNING ->
                                if (t.message.isNotEmpty()) t.message
                                else "${(t.progress * 100).toInt()}%" + if (t.etaSec > 0) " · ${t.etaSec}s left" else ""
                            TaskState.DONE -> t.message
                            TaskState.FAILED -> "Failed: ${t.message}"
                            TaskState.CANCELLED -> "Cancelled"
                        }
                        Text(
                            status, style = MaterialTheme.typography.bodySmall,
                            color = if (t.state == TaskState.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (t.state) {
                                TaskState.QUEUED, TaskState.RUNNING ->
                                    OutlinedButton(onClick = { DownloadCenter.cancel(t.id) }) { Text("Cancel") }
                                TaskState.FAILED, TaskState.CANCELLED -> {
                                    Button(onClick = { DownloadCenter.retry(ctx, t.id) }) { Text("Retry") }
                                    OutlinedButton(onClick = { DownloadCenter.remove(t.id) }) { Text("Remove") }
                                }
                                TaskState.DONE ->
                                    OutlinedButton(onClick = { DownloadCenter.remove(t.id) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Engine.status.collectAsStateWithLifecycle()
    var updateMsg by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Parallel downloads", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf(1, 2, 3, 4, 5), Prefs.maxParallel, { it.toString() }) {
                Prefs.maxParallel = it
                DownloadCenter.setMaxParallel(it)
                Prefs.save()
            }
        }

        SwitchRow(
            "Use aria2c downloader",
            "Splits each file into N connections (uses the Threads slider). Try it if downloads are slow.",
            Prefs.useAria2c,
        ) { Prefs.useAria2c = it; Prefs.save() }

        SwitchRow(
            "Square cover art for audio",
            "Crops the thumbnail to a square before embedding it in audio files.",
            Prefs.squareCover,
        ) { Prefs.squareCover = it; Prefs.save() }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("yt-dlp engine", style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !updating,
                onClick = {
                    updating = true
                    updateMsg = "Updating…"
                    scope.launch {
                        updateMsg = Engine.updateYtDlp(ctx)
                        updating = false
                    }
                },
            ) { Text("Update yt-dlp") }
            if (updateMsg.isNotEmpty()) Text(updateMsg, style = MaterialTheme.typography.bodySmall)
            Text(
                "If searches or downloads suddenly start failing, update yt-dlp first.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("Files are saved to Downloads/YTGrab", style = MaterialTheme.typography.bodySmall)
        Text(REPO_URL, style = MaterialTheme.typography.bodySmall)
    }
}

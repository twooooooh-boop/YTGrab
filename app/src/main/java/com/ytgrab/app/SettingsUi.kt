@file:OptIn(ExperimentalMaterial3Api::class)

package com.ytgrab.app

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import org.json.JSONObject

// ---------------------------------------------------------------- building blocks

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp),
        )
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { content() }
        }
    }
}

@Composable
private fun TitleBlock(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { TitleBlock(title, subtitle) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> DropdownRow(
    title: String, subtitle: String?, options: List<T>, selected: T,
    label: (T) -> String, onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { TitleBlock(title, subtitle) }
        Box {
            OutlinedButton(onClick = { open = true }) { Text(label(selected) + "  ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(label(o)) }, onClick = { onSelect(o); open = false })
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentRow(
    title: String, subtitle: String?, options: List<T>, selected: T,
    label: (T) -> String, onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitleBlock(title, subtitle)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label(o)) }
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, subtitle: String?, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        TitleBlock("$title: $value", subtitle)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun Swatch(name: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fill = if (color != null) Modifier.background(color, CircleShape)
        else Modifier.background(
            Brush.linearGradient(listOf(Color(0xFF6750A4), Color(0xFF00BFA5), Color(0xFFFF8A65))),
            CircleShape,
        )
        Box(
            Modifier.size(52.dp).then(fill)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

// ---------------------------------------------------------------- screen

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val status by Engine.status.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Prefs.toJson().toString(2).toByteArray()) }
            }.onSuccess { toast(ctx, "Settings exported") }
                .onFailure { toast(ctx, "Export failed: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                if (!Prefs.fromJson(JSONObject(text))) throw IllegalArgumentException("Not a YTGrab backup file")
                Prefs.save()
                DownloadCenter.setMaxParallel(Prefs.maxParallel)
            }.onSuccess { toast(ctx, "Settings restored") }
                .onFailure { toast(ctx, "Restore failed: ${it.message}") }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader("Settings", "Make it yours") }

        item { Section("Appearance") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TitleBlock("Theme", "Pick a colour style")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        item {
                            Swatch("Material You", null, Prefs.themeId == "dynamic") { Prefs.themeId = "dynamic"; Prefs.save() }
                        }
                    }
                    items(PALETTES) { p ->
                        Swatch(p.name, p.seed, Prefs.themeId == p.id) { Prefs.themeId = p.id; Prefs.save() }
                    }
                }
            }
            SegmentRow(
                "Mode", null, listOf("system", "light", "dark"), Prefs.appearance,
                { it.replaceFirstChar { c -> c.uppercase() } },
            ) { Prefs.appearance = it; Prefs.save() }
            SwitchRow("Pure black (AMOLED)", "Saves battery on OLED screens in dark mode", Prefs.amoled) {
                Prefs.amoled = it; Prefs.save()
            }
            SwitchRow("Show thumbnails", "Turn off for a compact, text-only list", Prefs.showThumbs) {
                Prefs.showThumbs = it; Prefs.save()
            }
        } }

        item { Section("Download defaults") {
            SegmentRow(
                "Default type", "What a result starts on when you open it", listOf(Mode.VIDEO, Mode.AUDIO), Prefs.mode,
                { if (it == Mode.VIDEO) "Video" else "Audio" },
            ) { Prefs.mode = it; Prefs.save() }
            DropdownRow(
                "Default video quality", null,
                listOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144), Prefs.videoHeight,
                { if (it == 0) "Best" else "${it}p" },
            ) { Prefs.videoHeight = it; Prefs.save() }
            DropdownRow(
                "Default audio quality", null,
                listOf("Best", "256", "128", "48"), Prefs.audioBitrate,
                { if (it == "Best") it else "$it kbps" },
            ) { Prefs.audioBitrate = it; Prefs.save() }
            SegmentRow(
                "If that quality isn't available", "Never an error: the app always picks something",
                listOf(true, false), Prefs.fallbackBest,
                { if (it) "Use Best" else "Closest lower" },
            ) { Prefs.fallbackBest = it; Prefs.save() }
        } }

        item { Section("Speed & network") {
            SliderRow("Threads per file", "More threads can be faster on a good connection", Prefs.threads, 1..16) {
                Prefs.threads = it
                Prefs.save()
            }
            SliderRow("Files at the same time", null, Prefs.maxParallel, 1..5) {
                Prefs.maxParallel = it
                DownloadCenter.setMaxParallel(it)
                Prefs.save()
            }
            DropdownRow(
                "Chunk size", "Size of each parallel piece", listOf(2, 4, 8), Prefs.chunkMb, { "$it MB" },
            ) { Prefs.chunkMb = it; Prefs.save() }
            SwitchRow("Wi-Fi only", "Don't download on mobile data", Prefs.wifiOnly) { Prefs.wifiOnly = it; Prefs.save() }
        } }

        item { Section("Artwork & tags") {
            SwitchRow("Artwork in audio files", "Embed the thumbnail as cover art in .m4a", Prefs.artAudio) {
                Prefs.artAudio = it; Prefs.save()
            }
            SwitchRow("Artwork in video files", "Embed the thumbnail as cover art in .mp4", Prefs.artVideo) {
                Prefs.artVideo = it; Prefs.save()
            }
            SwitchRow("Square covers for audio", "Crops the thumbnail to a square, like music apps expect", Prefs.squareCover) {
                Prefs.squareCover = it; Prefs.save()
            }
            DropdownRow(
                "Cover quality", "Higher looks better but makes files a bit bigger",
                listOf(75, 85, 92, 100), Prefs.coverQuality,
                {
                    when (it) {
                        75 -> "Low"
                        85 -> "Medium"
                        92 -> "High"
                        else -> "Maximum"
                    }
                },
            ) { Prefs.coverQuality = it; Prefs.save() }
            SwitchRow("Title & artist tags", "Writes the video title and channel into the file", Prefs.embedTags) {
                Prefs.embedTags = it; Prefs.save()
            }
        } }

        item { Section("Files") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleBlock("Folder name", "Saved to Downloads/${Prefs.folder.ifBlank { "YTGrab" }}")
                OutlinedTextField(
                    value = Prefs.folder,
                    onValueChange = { Prefs.folder = Prefs.cleanFolder(it); Prefs.save() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DropdownRow(
                "File names", null, listOf("title", "artist_title", "title_quality"), Prefs.nameTemplate,
                {
                    when (it) {
                        "artist_title" -> "Artist - Title"
                        "title_quality" -> "Title [quality]"
                        else -> "Title"
                    }
                },
            ) { Prefs.nameTemplate = it; Prefs.save() }
        } }

        item { Section("Behaviour") {
            SwitchRow("Close the panel after adding", "Collapses the result once it is in the queue", Prefs.closeAfterAdd) {
                Prefs.closeAfterAdd = it; Prefs.save()
            }
            SwitchRow("Open the queue after adding", "Jump to the Queue tab automatically", Prefs.openQueueAfterAdd) {
                Prefs.openQueueAfterAdd = it; Prefs.save()
            }
            SwitchRow("Remember searches", "Shows your recent searches on the Search tab", Prefs.saveHistory) {
                Prefs.saveHistory = it
                if (!it) Prefs.history = emptyList()
                Prefs.save()
            }
        } }

        item { Section("Backup & restore") {
            Text(
                "Saves all of the settings above to a file you can keep, share, or restore on another phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { exportLauncher.launch("ytgrab-settings.json") }, modifier = Modifier.weight(1f)) { Text("Backup") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            }
            TextButton(onClick = { confirmReset = true }) { Text("Reset all settings") }
        } }

        item { Section("About") {
            TitleBlock("YTGrab $APP_VERSION", "Uses the NewPipe extractor (GPL-3.0)")
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                StreamCache.clear()
                Engine.clearTemp(ctx)
                toast(ctx, "Cache cleared")
            }) { Text("Clear cached stream info") }
            Text(REPO_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }

        item { Box(Modifier.padding(bottom = 24.dp)) }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all settings?") },
            text = { Text("Themes, defaults, artwork options and history go back to the way they were when you installed the app. Your downloaded files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.reset()
                    DownloadCenter.setMaxParallel(Prefs.maxParallel)
                    confirmReset = false
                    toast(ctx, "Settings reset")
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

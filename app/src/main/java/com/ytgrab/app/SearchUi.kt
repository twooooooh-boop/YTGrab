@file:OptIn(ExperimentalMaterial3Api::class)

package com.ytgrab.app

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private fun defaultsLabel(): String =
    if (Prefs.mode == Mode.VIDEO) "video · " + (if (Prefs.videoHeight == 0) "Best" else "${Prefs.videoHeight}p")
    else "audio · " + (if (Prefs.audioBitrate == "Best") "Best" else "${Prefs.audioBitrate} kbps")

@Composable
private fun SuggestionRow(icon: String, text: String, onFill: () -> Unit, onRemove: (() -> Unit)?, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, Modifier.width(34.dp))
        Text(
            text, Modifier.weight(1f).padding(vertical = 12.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove") }
        }
        IconButton(onClick = onFill) { Text("↖", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Shown while the search box is focused: your recent searches when it is empty, YouTube's suggestions while typing. */
@Composable
private fun SuggestionPanel(onPick: () -> Unit) {
    val q = SearchState.query.trim()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (q.isEmpty()) {
            if (Prefs.saveHistory && Prefs.history.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent searches", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { Prefs.clearHistory() }) { Text("Clear all") }
                    }
                }
                items(Prefs.history) { h ->
                    SuggestionRow(
                        "🕘", h,
                        onFill = { SearchState.onQueryChanged(h) },
                        onRemove = { Prefs.removeHistory(h) },
                    ) { SearchState.onQueryChanged(h); onPick() }
                }
            } else {
                item { EmptyState("⌨️", "Start typing", "YouTube suggestions will show up here as you type.") }
            }
        } else {
            item {
                SuggestionRow("🔍", q, onFill = {}, onRemove = null) { onPick() }
            }
            items(SearchState.suggestions.filterNot { it.equals(q, ignoreCase = true) }) { s ->
                SuggestionRow("🔍", s, onFill = { SearchState.onQueryChanged(s) }, onRemove = null) {
                    SearchState.onQueryChanged(s); onPick()
                }
            }
        }
    }
}

@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val ready by Engine.ready.collectAsStateWithLifecycle()
    val status by Engine.status.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    val go = { focus.clearFocus(); SearchState.search() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("YTGrab", "Search or paste a YouTube link")

        OutlinedTextField(
            value = SearchState.query,
            onValueChange = { SearchState.onQueryChanged(it) },
            placeholder = { Text("Song, video, or link") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged { focused = it.isFocused },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (SearchState.query.isNotEmpty()) {
                    IconButton(onClick = { SearchState.onQueryChanged("") }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
        )

        if (!ready) {
            Text(
                status,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        val results = SearchState.results
        when {
            focused -> SuggestionPanel { go() }
            SearchState.loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            results.isNotEmpty() -> {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (results.size > 1) {
                        item {
                            FilledTonalButton(
                                onClick = {
                                    DownloadCenter.enqueue(ctx, results, Prefs.options())
                                    Toast.makeText(ctx, "Added ${results.size} to queue", Toast.LENGTH_SHORT).show()
                                    if (Prefs.openQueueAfterAdd) Nav.tab = 1
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download all ${results.size}  ·  ${defaultsLabel()}") }
                        }
                    }
                    items(results) { ResultRow(it) }
                }
            }
            SearchState.error != null -> {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        SearchState.error ?: "",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            else -> EmptyState("🔎", "Find something to download", "Type a name, or paste a video or playlist link, then tap a result to choose video or audio and a quality.")
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
fun ResultRow(item: SearchItem) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable(item.url) { mutableStateOf(false) }
    var mode by rememberSaveable(item.url) { mutableStateOf(Prefs.mode) }
    var height by rememberSaveable(item.url) { mutableStateOf(Prefs.videoHeight) }
    var aBr by rememberSaveable(item.url) { mutableStateOf(Prefs.audioBitrate) }
    var qOpen by rememberSaveable(item.url) { mutableStateOf(false) }
    var attempt by remember(item.url) { mutableIntStateOf(0) }
    var opts by remember(item.url) { mutableStateOf(StreamCache.get(item.url)?.let { buildOptions(it) }) }
    var loading by remember(item.url) { mutableStateOf(false) }
    var error by remember(item.url) { mutableStateOf<String?>(null) }

    LaunchedEffect(expanded, attempt) {
        if (expanded && opts == null && !loading) {
            loading = true
            error = null
            try {
                val o = buildOptions(Engine.streamInfo(item.url))
                opts = o
                if (height != 0) height = pickVideo(o, height)?.height ?: 0
                if (aBr != "Best") aBr = pickAudio(o, aBr)?.kbps?.toString() ?: "Best"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message?.lines()?.firstOrNull { it.isNotBlank() } ?: "Could not load formats"
            } finally {
                loading = false
            }
        }
    }

    val o = opts
    val selVideo = o?.let { pickVideo(it, height) }
    val selAudio = o?.let { pickAudio(it, aBr) }
    val selBytes = (if (mode == Mode.VIDEO) selVideo?.bytes else selAudio?.bytes) ?: 0L
    val selTitle = if (mode == Mode.VIDEO) {
        (if (height == 0) "Best" + (selVideo?.let { " · ${it.height}p" } ?: "") else "${height}p") +
            (if (selVideo?.webm == true) " · WebM" else "")
    } else {
        if (aBr == "Best") "Best" + (selAudio?.let { " · ${it.kbps} kbps" } ?: "") else "$aBr kbps"
    }
    val choices = if (mode == Mode.VIDEO) videoChoices(o) else audioChoices(o)
    val currentKey = if (mode == Mode.VIDEO) height.toString() else aBr

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (Prefs.showThumbs) {
                    Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(14.dp))) {
                        AsyncImage(
                            model = item.thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.durationSec > 0) {
                            Text(
                                fmtDur(item.durationSec),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(5.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xCC000000))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOf(item.channel, if (!Prefs.showThumbs) fmtDur(item.durationSec) else "").filter { it.isNotBlank() }.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton("🎬  Video", mode == Mode.VIDEO, Modifier.weight(1f)) { mode = Mode.VIDEO; qOpen = false }
                        ModeButton("🎵  Audio", mode == Mode.AUDIO, Modifier.weight(1f)) { mode = Mode.AUDIO; qOpen = false }
                    }

                    // quality "button" that opens a vertical list
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable { qOpen = !qOpen }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Quality", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(selTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when {
                                            loading -> "Reading sizes…"
                                            selBytes > 0 -> fmtSize(selBytes)
                                            else -> "Tap to see sizes"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (loading) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(if (qOpen) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (qOpen) {
                                if (choices.isEmpty()) {
                                    Text(
                                        error?.let { "Could not load formats: $it" } ?: "Loading formats…",
                                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (error != null) {
                                        TextButton(
                                            onClick = { opts = null; attempt++ },
                                            modifier = Modifier.padding(start = 6.dp),
                                        ) { Text("Try again") }
                                    }
                                }
                                choices.forEach { c ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                if (mode == Mode.VIDEO) height = c.key.toIntOrNull() ?: 0 else aBr = c.key
                                                qOpen = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = c.key == currentKey, onClick = null, modifier = Modifier.padding(8.dp))
                                        Column(Modifier.padding(vertical = 6.dp)) {
                                            Text(c.title, style = MaterialTheme.typography.bodyLarge)
                                            Text(c.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }

                    if (error != null && !qOpen) {
                        Text(
                            "Could not read sizes. You can still download; it will use the best match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = {
                            DownloadCenter.enqueue(
                                ctx, listOf(item),
                                Prefs.options().copy(mode = mode, videoHeight = height, audioBitrate = aBr),
                            )
                            Toast.makeText(ctx, "Added to queue", Toast.LENGTH_SHORT).show()
                            if (Prefs.closeAfterAdd) { expanded = false; qOpen = false }
                            if (Prefs.openQueueAfterAdd) Nav.tab = 1
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text(
                            (if (mode == Mode.VIDEO) "⬇  Download video" else "⬇  Download audio") +
                                (if (selBytes > 0) "  ·  ${fmtSize(selBytes)}" else "")
                        )
                    }
                }
            }
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.ytgrab.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private fun optLabel(o: DownloadOptions): String =
    if (o.mode == Mode.VIDEO)
        "Video · " + (if (o.videoHeight == 0) "Best" else "${o.videoHeight}p") + " · ${o.threads} threads"
    else "Audio · m4a · " + (if (o.audioBitrate == "Best") "Best" else "${o.audioBitrate} kbps") + " · ${o.threads} threads"

@Composable
fun QueueScreen(tasks: List<DownloadTask>) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val active = tasks.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
    val done = tasks.count { it.state == TaskState.DONE }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Downloads",
            if (tasks.isEmpty()) "Nothing yet" else "$active active · $done finished",
        ) {
            if (tasks.any { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }) {
                TextButton(onClick = { DownloadCenter.clearFinished() }) { Text("Clear finished") }
            }
        }

        if (tasks.isEmpty()) {
            EmptyState("📥", "Your queue is empty", "Tap a result on the Search tab, choose a quality, and it will show up here.")
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks.reversed(), key = { it.id }) { t ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (Prefs.showThumbs) {
                                AsyncImage(
                                    model = t.item.thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(10.dp)),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    t.item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    optLabel(t.options), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (t.state == TaskState.RUNNING || t.state == TaskState.QUEUED) {
                            LinearProgressIndicator(
                                progress = { t.progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                        }

                        val status = when (t.state) {
                            TaskState.QUEUED -> "Queued"
                            TaskState.RUNNING ->
                                if (t.message.isNotEmpty()) t.message
                                else "${(t.progress * 100).toInt()}%" + if (t.etaSec > 0) " · ${t.etaSec}s left" else ""
                            TaskState.DONE -> "✓  " + t.message
                            TaskState.FAILED -> "Failed: ${t.message}"
                            TaskState.CANCELLED -> "Cancelled"
                        }
                        Text(
                            status, style = MaterialTheme.typography.bodySmall,
                            color = when (t.state) {
                                TaskState.FAILED -> MaterialTheme.colorScheme.error
                                TaskState.DONE -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (t.state) {
                                TaskState.QUEUED, TaskState.RUNNING ->
                                    OutlinedButton(onClick = { DownloadCenter.cancel(t.id) }) { Text("Cancel") }
                                TaskState.FAILED, TaskState.CANCELLED -> {
                                    Button(onClick = { DownloadCenter.retry(ctx, t.id) }) { Text("Retry") }
                                    if (t.state == TaskState.FAILED && t.details.isNotBlank()) {
                                        OutlinedButton(onClick = {
                                            clip.setText(AnnotatedString(t.details + "\nResult: " + t.message))
                                            Toast.makeText(ctx, "Details copied", Toast.LENGTH_SHORT).show()
                                        }) { Text("Copy details") }
                                    }
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

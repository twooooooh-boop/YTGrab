package com.ytgrab.app

enum class Mode { VIDEO, AUDIO }
enum class TaskState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class SearchItem(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val url: String,
    val thumb: String,
)

data class DownloadOptions(
    val mode: Mode,
    val videoHeight: Int,        // 0 = best available
    val audioBitrate: String,    // "Best" or a number in kbps, e.g. "128"
    val threads: Int,
)

data class DownloadTask(
    val id: String,
    val item: SearchItem,
    val options: DownloadOptions,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val message: String = "",
    val details: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
)

class CancelToken {
    @Volatile var cancelled = false
    fun check() {
        if (cancelled) throw java.util.concurrent.CancellationException("cancelled")
    }
}

fun fmtDur(s: Long): String = when {
    s <= 0 -> ""
    s >= 3600 -> "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else -> "%d:%02d".format(s / 60, s % 60)
}

data class QOpt(val key: String, val title: String, val sub: String)

fun fmtEta(s: Long): String = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b >= 1_000_000_000L -> "%.2f GB".format(b / 1_000_000_000.0)
    else -> "%.1f MB".format(b / 1_000_000.0)
}

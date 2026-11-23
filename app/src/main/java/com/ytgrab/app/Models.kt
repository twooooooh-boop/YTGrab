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
    val audioFormat: String,     // m4a / mp3 / opus
    val audioBitrate: String,    // Best / 320K / 192K / 128K
    val threads: Int,
    val useAria2c: Boolean,
    val squareCover: Boolean,
)

data class DownloadTask(
    val id: String,
    val item: SearchItem,
    val options: DownloadOptions,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val message: String = "",
)

fun fmtDur(s: Long): String = when {
    s <= 0 -> ""
    s >= 3600 -> "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else -> "%d:%02d".format(s / 60, s % 60)
}

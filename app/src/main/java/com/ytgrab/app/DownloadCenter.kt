package com.ytgrab.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Download queue: runs up to N downloads in parallel, each with its own thread count. */
object DownloadCenter {
    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val tokens = ConcurrentHashMap<String, CancelToken>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var permits = Semaphore(Prefs.maxParallel.coerceAtLeast(1))

    fun setMaxParallel(n: Int) {
        permits = Semaphore(n.coerceAtLeast(1))
    }

    private fun update(id: String, f: (DownloadTask) -> DownloadTask) {
        tasks.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun startService(app: Context) {
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    fun enqueue(ctx: Context, items: List<SearchItem>, opts: DownloadOptions) {
        val app = ctx.applicationContext
        val fresh = items.map { DownloadTask(UUID.randomUUID().toString(), it, opts) }
        tasks.update { it + fresh }
        startService(app)
        fresh.forEach { launchTask(app, it) }
    }

    private fun launchTask(app: Context, t: DownloadTask) {
        jobs[t.id] = scope.launch {
            permits.withPermit { runTask(app, t) }
        }
    }

    private fun runTask(app: Context, t: DownloadTask) {
        if (t.id in cancelled) {
            update(t.id) { it.copy(state = TaskState.CANCELLED) }
            return
        }
        val token = CancelToken()
        tokens[t.id] = token
        update(t.id) { it.copy(state = TaskState.RUNNING, message = "Starting…", progress = 0f) }
        try {
            val msg = Engine.download(app, t, token) { p, eta, done, total ->
                update(t.id) {
                    it.copy(
                        progress = p.coerceIn(0f, 1f),
                        etaSec = eta,
                        doneBytes = done,
                        totalBytes = total,
                        message = if (p >= 0.94f) "Merging / adding artwork…" else "",
                    )
                }
            }
            update(t.id) { it.copy(state = TaskState.DONE, progress = 1f, message = msg) }
        } catch (e: Throwable) {
            if (t.id in cancelled || token.cancelled) {
                update(t.id) { it.copy(state = TaskState.CANCELLED, message = "Cancelled") }
            } else {
                val m = e.message?.lines()?.firstOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
                val report = (e as? DownloadFailed)?.details
                    ?: ("YTGrab $APP_VERSION | ${e.javaClass.simpleName}: ${e.message}\nVideo: ${t.item.url}")
                update(t.id) { it.copy(state = TaskState.FAILED, message = m, details = report) }
            }
        } finally {
            tokens.remove(t.id)
        }
    }

    fun cancel(id: String) {
        cancelled.add(id)
        tokens[id]?.cancelled = true
        val t = tasks.value.firstOrNull { it.id == id } ?: return
        if (t.state == TaskState.QUEUED) {
            jobs[id]?.cancel()
            update(id) { it.copy(state = TaskState.CANCELLED, message = "Cancelled") }
        }
    }

    fun retry(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val t = tasks.value.firstOrNull { it.id == id } ?: return
        cancelled.remove(id)
        update(id) { it.copy(state = TaskState.QUEUED, progress = 0f, message = "") }
        startService(app)
        launchTask(app, t)
    }

    fun remove(id: String) {
        tasks.update { l -> l.filterNot { it.id == id } }
    }

    fun clearFinished() {
        tasks.update { l ->
            l.filter { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
        }
    }
}

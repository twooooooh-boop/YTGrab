package com.ytgrab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-level search state so results survive tab switches. */
object SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var suggestions by mutableStateOf<List<String>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var suggestJob: Job? = null

    /** Called on every keystroke: updates the text and asks YouTube for suggestions (after a short pause). */
    fun onQueryChanged(q: String) {
        query = q
        suggestJob?.cancel()
        val t = q.trim()
        if (t.isEmpty() || t.startsWith("http")) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(150)
            val list = withContext(Dispatchers.IO) { runCatching { Engine.suggest(t) }.getOrDefault(emptyList()) }
            if (query.trim() == t) suggestions = list
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        suggestJob?.cancel()
        Prefs.addHistory(q)
        loading = true
        error = null
        scope.launch {
            try {
                results = Engine.search(q)
                if (results.isEmpty()) error = "No results."
            } catch (e: Throwable) {
                results = emptyList()
                error = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Search failed"
            }
            loading = false
        }
    }
}

package com.ytgrab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-level search state so results survive tab switches. */
object SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
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

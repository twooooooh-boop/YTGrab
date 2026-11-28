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
    var focused by mutableStateOf(false)
    var corrected by mutableStateOf<String?>(null)
    var didYouMean by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)

    private var moreToken: MoreToken? = null
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

    /** Back from the results to the empty search page (the typed text stays so it can be edited). */
    fun clearResults() {
        results = emptyList()
        error = null
        corrected = null
        didYouMean = null
        hasMore = false
        moreToken = null
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
                val page = Engine.search(q)
                results = page.items
                corrected = page.corrected
                didYouMean = page.didYouMean
                moreToken = page.more
                hasMore = page.more != null
                if (results.isEmpty()) error = "No results. Try different words."
            } catch (e: Throwable) {
                results = emptyList()
                corrected = null
                didYouMean = null
                hasMore = false
                moreToken = null
                error = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Search failed"
            }
            loading = false
        }
    }

    /** Loads the next page and adds only videos that are not already in the list. */
    fun loadMore() {
        val tok = moreToken ?: return
        if (loadingMore || loading) return
        loadingMore = true
        scope.launch {
            var again = false
            try {
                val p = Engine.searchMore(tok)
                val known = results.map { it.url }.toHashSet()
                val fresh = p.items.filter { it.url !in known }
                results = results + fresh
                moreToken = p.more
                hasMore = p.more != null
                again = fresh.isEmpty() && p.more != null
            } catch (e: Throwable) {
                moreToken = null
                hasMore = false
            }
            loadingMore = false
            if (again) loadMore()
        }
    }
}

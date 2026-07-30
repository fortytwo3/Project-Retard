package com.paladex.ex

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paladex.ex.core.Card
import com.paladex.ex.core.CardCandidate
import com.paladex.ex.core.Config
import com.paladex.ex.core.PriceReport
import com.paladex.ex.core.PriceService
import com.paladex.ex.core.ScanParse
import com.paladex.ex.data.HistoryStore
import com.paladex.ex.data.SettingsStore
import com.paladex.ex.ocr.CardRecogniser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the scan flow currently is. */
enum class Stage { SCAN, READING, CANDIDATES, REPORT }

data class ScanUiState(
    val stage: Stage = Stage.SCAN,
    val status: String = "",
    val error: String? = null,
    val preview: Bitmap? = null,
    val scan: ScanParse? = null,
    val candidates: List<CardCandidate> = emptyList(),
    val report: PriceReport? = null,
    val refreshing: Boolean = false,
    val searchResults: List<Card> = emptyList(),
    val searching: Boolean = false,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val history = HistoryStore(application)
    private val recogniser = CardRecogniser()

    /** Rebuilt whenever settings change, so new keys take effect immediately. */
    private var service = PriceService(settings.load())

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    val config: Config get() = service.config

    fun updateConfig(config: Config) {
        settings.save(config)
        service = PriceService(config)
    }

    fun historyEntries() = history.all()

    fun removeHistory(id: String) = history.remove(id)

    fun clearHistory() = history.clear()

    fun reset() {
        _state.value = ScanUiState()
    }

    /**
     * Full scan: OCR the captured card, then look it up.
     *
     * A single high-confidence hit goes straight to prices — reprints are the
     * reason candidates exist, and when there is exactly one strong match there
     * is nothing to disambiguate.
     */
    fun scanCard(card: Bitmap) {
        viewModelScope.launch {
            _state.update {
                it.copy(stage = Stage.READING, error = null, preview = card, status = "Reading the card…")
            }

            try {
                val parsed = withContext(Dispatchers.Default) {
                    recogniser.scan(card) { message ->
                        _state.update { it.copy(status = message) }
                    }
                }

                _state.update { it.copy(scan = parsed, status = "Looking up the card…") }

                if (parsed.isEmpty) {
                    _state.update {
                        it.copy(
                            stage = Stage.CANDIDATES,
                            error = "Could not read a name or number. Try again with more light, or search by name.",
                        )
                    }
                    return@launch
                }

                val candidates = service.identify(parsed)

                if (candidates.size == 1 && candidates.first().score > 0.85) {
                    loadPrices(candidates.first().card)
                    return@launch
                }

                _state.update {
                    it.copy(
                        stage = Stage.CANDIDATES,
                        candidates = candidates,
                        error = if (candidates.isEmpty()) "No matching cards found." else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(stage = Stage.CANDIDATES, error = e.message ?: "Scan failed.")
                }
            }
        }
    }

    fun loadPrices(card: Card, refresh: Boolean = false) {
        viewModelScope.launch {
            if (refresh) {
                _state.update { it.copy(refreshing = true) }
                service.clearCache()
            } else {
                _state.update {
                    it.copy(stage = Stage.READING, error = null, status = "Checking prices for ${card.name}…")
                }
            }

            try {
                val report = service.buildReport(card)
                history.add(card, report)
                _state.update {
                    it.copy(stage = Stage.REPORT, report = report, refreshing = false, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        stage = if (refresh) Stage.REPORT else Stage.SCAN,
                        refreshing = false,
                        error = e.message ?: "Could not load prices.",
                    )
                }
            }
        }
    }

    /** Debounced name search backing the manual-entry fallback. */
    fun search(term: String) {
        searchJob?.cancel()

        if (term.trim().length < 2) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            kotlinx.coroutines.delay(350)
            val results = runCatching { service.search(term) }.getOrDefault(emptyList())
            _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    override fun onCleared() {
        recogniser.close()
        super.onCleared()
    }
}

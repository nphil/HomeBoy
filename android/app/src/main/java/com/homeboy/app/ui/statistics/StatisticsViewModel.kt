package com.homeboy.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBGroupStatistics
import com.homeboy.app.api.HBTotalsByOrganizer
import com.homeboy.app.api.HBValueOverTime
import com.homeboy.app.data.HomeboxRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class StatisticsViewModel(private val repo: HomeboxRepository) : ViewModel() {

    private val _stats = MutableStateFlow<HBGroupStatistics?>(null)
    val stats = _stats.asStateFlow()

    private val _byLocation = MutableStateFlow<List<HBTotalsByOrganizer>>(emptyList())
    val byLocation = _byLocation.asStateFlow()

    private val _byTag = MutableStateFlow<List<HBTotalsByOrganizer>>(emptyList())
    val byTag = _byTag.asStateFlow()

    private val _valueOverTime = MutableStateFlow<HBValueOverTime?>(null)
    val valueOverTime = _valueOverTime.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val end = Instant.now()
                val start = end.minus(365, ChronoUnit.DAYS)
                val fmt = DateTimeFormatter.ISO_INSTANT

                val statsD = async { runCatching { repo.getStatistics() }.getOrNull() }
                val locD = async { runCatching { repo.getStatsByLocation() }.getOrNull() }
                val tagD = async { runCatching { repo.getStatsByTag() }.getOrNull() }
                val votD = async {
                    runCatching { repo.getPurchasePriceOverTime(fmt.format(start), fmt.format(end)) }.getOrNull()
                }

                _stats.value = statsD.await() ?: HBGroupStatistics()
                _byLocation.value = locD.await().orEmpty().filter { it.total > 0 }.sortedByDescending { it.total }
                _byTag.value = tagD.await().orEmpty().filter { it.total > 0 }.sortedByDescending { it.total }
                _valueOverTime.value = votD.await()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                StatisticsViewModel(app.repository) as T
        }
    }
}

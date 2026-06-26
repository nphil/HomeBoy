package com.homeboy.app.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBMaintenanceCreate
import com.homeboy.app.api.HBMaintenanceWithDetails
import com.homeboy.app.data.HomeboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MaintenanceViewModel(private val repo: HomeboxRepository) : ViewModel() {

    private val _entries = MutableStateFlow<List<HBMaintenanceWithDetails>>(emptyList())
    val entries = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _entries.value = repo.listAllMaintenance("both")
            } catch (e: Exception) {
                _snackbar.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    /** Mark a scheduled task complete: stamp today's completion date, clear the schedule. */
    fun markComplete(entry: HBMaintenanceWithDetails) {
        viewModelScope.launch {
            try {
                val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                repo.updateMaintenance(
                    entry.id,
                    HBMaintenanceCreate(
                        name = entry.name,
                        description = entry.description ?: "",
                        date = today,
                        scheduledDate = "",
                        cost = entry.cost ?: "0"
                    )
                )
                _snackbar.value = "Marked complete"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteMaintenance(id)
                _entries.value = _entries.value.filter { it.id != id }
                _snackbar.value = "Entry deleted"
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearSnackbar() { _snackbar.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                MaintenanceViewModel(app.repository) as T
        }
    }
}

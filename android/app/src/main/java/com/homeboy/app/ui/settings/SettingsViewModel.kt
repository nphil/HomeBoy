package com.homeboy.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBGroup
import com.homeboy.app.api.HBUserInfo
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

    val serverUrl = prefs.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val tenantName = prefs.tenantName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val themeIndex = prefs.themeIndex.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _userInfo = MutableStateFlow<HBUserInfo?>(null)
    val userInfo = _userInfo.asStateFlow()

    private val _groups = MutableStateFlow<List<HBGroup>>(emptyList())
    val groups = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _userInfo.value = repo.getMe()
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            try {
                _groups.value = repo.listGroups()
            } catch (_: Exception) {}
        }
    }

    fun switchGroup(group: HBGroup) {
        viewModelScope.launch {
            repo.setActiveGroup(group.id, group.name)
            repo.invalidate()
            _snackbar.value = "Switched to ${group.name}"
            try { _userInfo.value = repo.getMe() } catch (_: Exception) {}
        }
    }

    fun setTheme(index: Int) {
        viewModelScope.launch { prefs.setThemeIndex(index) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            repo.logout()
            onLoggedOut()
        }
    }

    fun clearSnackbar() { _snackbar.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                SettingsViewModel(app.repository, app.prefs) as T
        }
    }
}

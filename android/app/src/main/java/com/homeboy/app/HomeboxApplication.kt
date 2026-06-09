package com.homeboy.app

import android.app.Application
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository

class HomeboxApplication : Application() {
    val prefs: PreferencesRepository by lazy { PreferencesRepository(this) }
    val repository: HomeboxRepository by lazy { HomeboxRepository(prefs) }
}

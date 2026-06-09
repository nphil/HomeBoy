package com.homeboy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeboy.app.ui.AppRoot
import com.homeboy.app.ui.theme.HomeBoyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HomeboxApplication
        setContent {
            val themeIndex by app.prefs.themeIndex.collectAsStateWithLifecycle(initialValue = 0)
            HomeBoyTheme(themeIndex = themeIndex) {
                AppRoot()
            }
        }
    }
}

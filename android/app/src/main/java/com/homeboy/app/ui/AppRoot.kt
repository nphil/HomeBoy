package com.homeboy.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ui.items.ItemsTab
import com.homeboy.app.ui.locations.LocationsTab
import com.homeboy.app.ui.settings.SettingsTab
import com.homeboy.app.ui.tags.TagsTab

private enum class NavTab(
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector
) {
    ITEMS(    "Items",     Icons.Outlined.Inventory2, Icons.Filled.Inventory2),
    LOCATIONS("Locations", Icons.Outlined.Place,      Icons.Filled.Place),
    TAGS(     "Tags",      Icons.Outlined.Label,      Icons.Filled.Label),
    SETTINGS( "Settings",  Icons.Outlined.Settings,   Icons.Filled.Settings)
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val token by app.prefs.token.collectAsStateWithLifecycle(initialValue = null)

    // null = loading, "" = not logged in, else logged in
    when {
        token == null -> Box {} // splash / loading
        token!!.isBlank() -> LoginScreen()
        else -> MainScaffold(app)
    }
}

@Composable
private fun MainScaffold(app: HomeboxApplication) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = NavTab.entries

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEachIndexed { i, tab ->
                item(
                    icon = { Icon(if (i == selected) tab.activeIcon else tab.icon, null) },
                    label = { Text(tab.label) },
                    selected = i == selected,
                    onClick = { selected = i }
                )
            }
        }
    ) {
        when (selected) {
            0 -> ItemsTab()
            1 -> LocationsTab()
            2 -> TagsTab()
            3 -> SettingsTab(onLogout = { selected = 0 })
        }
    }
}

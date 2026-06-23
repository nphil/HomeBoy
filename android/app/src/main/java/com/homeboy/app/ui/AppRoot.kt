package com.homeboy.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
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
    ITEMS(    "Items",     Icons.Outlined.Inventory2,            Icons.Filled.Inventory2),
    LOCATIONS("Locations", Icons.Outlined.Place,                 Icons.Filled.Place),
    TAGS(     "Tags",      Icons.AutoMirrored.Outlined.Label,    Icons.AutoMirrored.Filled.Label),
    SETTINGS( "Settings",  Icons.Outlined.Settings,              Icons.Filled.Settings)
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val token by app.prefs.token.collectAsStateWithLifecycle(initialValue = null)

    when {
        token == null -> Box(Modifier.fillMaxSize()) {}    // initial load
        token!!.isBlank() -> LoginScreen()
        else -> MainScaffold()
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MainScaffold() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = NavTab.entries

    val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val useRail = widthClass != WindowWidthSizeClass.COMPACT

    if (useRail) {
        var railExpanded by rememberSaveable { mutableStateOf(true) }
        Row(Modifier.fillMaxSize()) {
            SideRail(
                expanded = railExpanded,
                selected = selected,
                tabs = tabs,
                onToggle = { railExpanded = !railExpanded },
                onSelect = { selected = it }
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                TabContent(selected) { selected = it }
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = i == selected,
                            onClick = { selected = i },
                            icon = { Icon(if (i == selected) tab.activeIcon else tab.icon, tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                TabContent(selected) { selected = it }
            }
        }
    }
}

@Composable
private fun TabContent(selected: Int, onSelect: (Int) -> Unit) {
    when (selected) {
        0 -> ItemsTab()
        1 -> LocationsTab()
        2 -> TagsTab()
        3 -> SettingsTab(onLogout = { onSelect(0) })
    }
}

@Composable
private fun SideRail(
    expanded: Boolean,
    selected: Int,
    tabs: List<NavTab>,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxHeight()
            .width(if (expanded) 220.dp else 80.dp)
            .animateContentSize()
    ) {
        Column(
            Modifier.fillMaxHeight()
                .statusBarsPadding()
                .padding(vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: collapse/expand toggle (+ app name when expanded)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggle) {
                    Icon(Icons.Default.Menu, if (expanded) "Collapse" else "Expand")
                }
                if (expanded) {
                    Spacer(Modifier.width(4.dp))
                    Text("HomeBoy", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            tabs.forEachIndexed { i, tab ->
                val isSel = i == selected
                if (expanded) {
                    Surface(
                        color = if (isSel) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                                .clickableNoRipple { onSelect(i) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (isSel) tab.activeIcon else tab.icon, null,
                                tint = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text(tab.label,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                } else {
                    NavigationRailItem(
                        selected = isSel,
                        onClick = { onSelect(i) },
                        icon = { Icon(if (isSel) tab.activeIcon else tab.icon, tab.label) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick
    )
}

package com.homeboy.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ui.theme.APP_THEMES
import com.homeboy.app.ui.theme.THEME_MATERIAL_YOU

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))

    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val tenantName by vm.tenantName.collectAsStateWithLifecycle()
    val themeIndex by vm.themeIndex.collectAsStateWithLifecycle()
    val userInfo by vm.userInfo.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); vm.clearSnackbar() }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?") },
            text = { Text("You'll need to reconnect to your Homebox server.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    vm.logout { onLogout() }
                }) { Text("Log Out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") } }
        )
    }

    if (showThemePicker) {
        ThemePickerSheet(
            currentIndex = themeIndex,
            onSelect = { vm.setTheme(it) },
            onDismiss = { showThemePicker = false }
        )
    }

    if (showGroupPicker) {
        ModalBottomSheet(onDismissRequest = { showGroupPicker = false }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text("Switch Group", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp))
                groups.forEach { group ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Group, null) },
                        headlineContent = { Text(group.name) },
                        supportingContent = group.description?.takeIf { it.isNotBlank() }?.let {
                            { Text(it) }
                        },
                        trailingContent = if (group.name == tenantName) {
                            { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        modifier = Modifier.clickable {
                            vm.switchGroup(group)
                            showGroupPicker = false
                        }
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {

            // Account section
            item {
                SettingsSectionHeader("Account")
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Person, null) },
                    headlineContent = { Text(userInfo?.name ?: "User") },
                    supportingContent = { Text(userInfo?.email ?: "") }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Dns, null) },
                    headlineContent = { Text("Server") },
                    supportingContent = { Text(serverUrl.ifBlank { "Not configured" }) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            if (groups.isNotEmpty() || tenantName.isNotBlank()) {
                item {
                    val canSwitch = groups.size > 1
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Group, null) },
                        headlineContent = { Text("Group") },
                        supportingContent = {
                            Text(tenantName.ifBlank { groups.firstOrNull()?.name ?: "Default" })
                        },
                        trailingContent = if (canSwitch) {
                            { Icon(Icons.Default.SwapHoriz, "Switch group") }
                        } else null,
                        modifier = if (canSwitch) Modifier.clickable { showGroupPicker = true } else Modifier
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }

            // Appearance section
            item { SettingsSectionHeader("Appearance") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Palette, null) },
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(APP_THEMES.getOrNull(themeIndex)?.name ?: "Indigo") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(20.dp).clip(CircleShape)
                                    .background(APP_THEMES.getOrNull(themeIndex)?.seed ?: Color(0xFF4F46E5))
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    },
                    modifier = Modifier.clickable { showThemePicker = true }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }

            // About section
            item { SettingsSectionHeader("About") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    headlineContent = { Text("HomeBoy") },
                    supportingContent = {
                        Text("v${com.homeboy.app.BuildConfig.VERSION_NAME} (${com.homeboy.app.BuildConfig.VERSION_CODE}) · Homebox catalog client")
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }

            // Logout
            item { Spacer(Modifier.height(16.dp)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = {
                        Text("Log Out", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    },
                    modifier = Modifier.clickable { showLogoutDialog = true }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemePickerSheet(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            Text("Choose Theme", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                APP_THEMES.forEachIndexed { index, theme ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onSelect(index) }.width(72.dp)
                    ) {
                        val swatchBg = if (index == THEME_MATERIAL_YOU) {
                            // Wallpaper-derived theme — show a multicolor swatch
                            Modifier.background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF4F46E5), Color(0xFF0D9488),
                                        Color(0xFFD97706), Color(0xFFDB2777),
                                        Color(0xFF4F46E5)
                                    )
                                )
                            )
                        } else {
                            Modifier.background(theme.seed)
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .then(swatchBg)
                                .then(
                                    if (index == currentIndex) Modifier.border(
                                        3.dp, MaterialTheme.colorScheme.onBackground, CircleShape
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (index == currentIndex) {
                                Icon(Icons.Default.Check, null, tint = Color.White,
                                    modifier = Modifier.size(20.dp))
                            } else if (index == THEME_MATERIAL_YOU) {
                                Icon(Icons.Default.Wallpaper, null, tint = Color.White,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(theme.name, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

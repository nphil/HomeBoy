package com.homeboy.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val aiSearchEnabled by vm.aiSearchEnabled.collectAsStateWithLifecycle()
    val modelStates by vm.modelStates.collectAsStateWithLifecycle()
    val npuActive by vm.npuActive.collectAsStateWithLifecycle()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showAiModels by remember { mutableStateOf(false) }
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

    if (showAiModels) {
        val customModels by vm.customModels.collectAsStateWithLifecycle()
        val embedModelId by vm.embedModelId.collectAsStateWithLifecycle()
        AiModelsSheet(
            states = modelStates,
            customModels = customModels,
            embedModelId = embedModelId,
            aiSearchEnabled = aiSearchEnabled,
            npuActive = npuActive,
            onToggleAiSearch = { vm.setAiSearchEnabled(it) },
            onSetDefault = { vm.setDefaultEmbedModel(it) },
            onDownload = { vm.downloadModel(it) },
            onCancel = { vm.cancelModelDownload(it) },
            onDelete = { vm.deleteModel(it) },
            onAddCustom = { name, modelUrl, vocabUrl -> vm.addCustomModel(name, modelUrl, vocabUrl) },
            onDismiss = { showAiModels = false }
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
                    headlineContent = { Text(userInfo?.name?.takeIf { it.isNotBlank() } ?: "User") },
                    supportingContent = {
                        Text(userInfo?.email?.takeIf { it.isNotBlank() }
                            ?: if (userInfo == null) "Loading…" else serverUrl.takeIf { it.isNotBlank() } ?: "")
                    }
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

            // AI section
            item { SettingsSectionHeader("AI") }
            item {
                val embedReady = modelStates["minilm-l6-v2"] is com.homeboy.app.ai.ModelRepository.State.Ready
                ListItem(
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                    headlineContent = { Text("AI Models") },
                    supportingContent = {
                        Text(
                            if (embedReady) "Semantic search ${if (aiSearchEnabled) "on" else "off"} · manage models"
                            else "Download on-device models for smarter search"
                        )
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { showAiModels = true }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelsSheet(
    states: Map<String, com.homeboy.app.ai.ModelRepository.State>,
    customModels: List<com.homeboy.app.ai.ModelRepository.ModelSpec>,
    embedModelId: String,
    aiSearchEnabled: Boolean,
    npuActive: Boolean?,
    onToggleAiSearch: (Boolean) -> Unit,
    onSetDefault: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddCustom: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val repo = com.homeboy.app.ai.ModelRepository
    val allModels = repo.CATALOG + customModels
    val embedReady = states[embedModelId] is com.homeboy.app.ai.ModelRepository.State.Ready
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddCustomModelDialog(
            onAdd = { name, modelUrl, vocabUrl -> onAddCustom(name, modelUrl, vocabUrl); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "AI Models", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp)
            )
            Text(
                "Models run entirely on your device. Nothing is sent to a server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // Acceleration status — shows whether inference engaged the NPU or fell back to CPU.
            if (aiSearchEnabled && npuActive != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (npuActive) Icons.Default.Bolt else Icons.Default.Memory,
                        null, Modifier.size(16.dp),
                        tint = if (npuActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (npuActive) "Acceleration: NPU (Hexagon)" else "Acceleration: CPU",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (npuActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Semantic search toggle — only meaningful once the active model is present.
            ListItem(
                leadingContent = { Icon(Icons.Default.Search, null) },
                headlineContent = { Text("Semantic search") },
                supportingContent = {
                    Text(
                        if (embedReady) "Find items by meaning, not just keywords"
                        else "Download a model below to enable"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = aiSearchEnabled && embedReady,
                        enabled = embedReady,
                        onCheckedChange = onToggleAiSearch
                    )
                }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            Text(
                "Models  ·  tap a downloaded model to make it the default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )

            allModels.forEach { spec ->
                val state = states[spec.id] ?: com.homeboy.app.ai.ModelRepository.State.NotDownloaded
                val isReady = state is com.homeboy.app.ai.ModelRepository.State.Ready
                val isDefault = spec.id == embedModelId
                ListItem(
                    modifier = if (isReady) Modifier.clickable { onSetDefault(spec.id) } else Modifier,
                    leadingContent = {
                        if (isReady) {
                            RadioButton(selected = isDefault, onClick = { onSetDefault(spec.id) })
                        } else {
                            Icon(Icons.Default.Search, null)
                        }
                    },
                    headlineContent = { Text(spec.displayName) },
                    supportingContent = {
                        val sub = when (state) {
                            is com.homeboy.app.ai.ModelRepository.State.Downloading ->
                                if (state.progress >= 0f) "Downloading ${(state.progress * 100).toInt()}%"
                                else "Downloading…"
                            is com.homeboy.app.ai.ModelRepository.State.Ready ->
                                if (isDefault) "Default · ready" else "Ready"
                            is com.homeboy.app.ai.ModelRepository.State.Failed -> "Failed: ${state.message}"
                            else -> spec.description
                        }
                        Text(sub)
                    },
                    trailingContent = {
                        when (state) {
                            is com.homeboy.app.ai.ModelRepository.State.Downloading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { if (state.progress >= 0f) state.progress else 0f },
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                    IconButton(onClick = { onCancel(spec.id) }) {
                                        Icon(Icons.Default.Close, "Cancel")
                                    }
                                }
                            }
                            is com.homeboy.app.ai.ModelRepository.State.Ready -> {
                                IconButton(onClick = { onDelete(spec.id) }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            else -> {
                                IconButton(onClick = { onDownload(spec.id) }) {
                                    Icon(Icons.Default.Download, "Download")
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }

            // Add a custom HuggingFace ONNX model.
            ListItem(
                leadingContent = { Icon(Icons.Default.Add, null) },
                headlineContent = { Text("Add custom model") },
                supportingContent = { Text("Paste a HuggingFace ONNX model + vocab URL") },
                modifier = Modifier.clickable { showAddDialog = true }
            )
        }
    }
}

@Composable
private fun AddCustomModelDialog(
    onAdd: (name: String, modelUrl: String, vocabUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var modelUrl by remember { mutableStateOf("") }
    var vocabUrl by remember { mutableStateOf("") }
    val valid = modelUrl.startsWith("http") && vocabUrl.startsWith("http")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Must be a quantized (QDQ) ONNX BERT-style embedding model to run on the NPU; " +
                        "other ONNX models fall back to CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelUrl, onValueChange = { modelUrl = it },
                    label = { Text("model.onnx URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vocabUrl, onValueChange = { vocabUrl = it },
                    label = { Text("vocab.txt URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(name, modelUrl, vocabUrl) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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

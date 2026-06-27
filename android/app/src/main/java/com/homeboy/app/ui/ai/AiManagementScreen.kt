package com.homeboy.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeboy.app.ai.AiBackend
import com.homeboy.app.ai.LlmEngineManager
import com.homeboy.app.ai.ModelRepository
import com.homeboy.app.ui.settings.SettingsViewModel

/**
 * Full-screen on-device AI manager. Two clearly separated capabilities — semantic search
 * (small embedding models) and tag suggestions (larger language models) — each with its own
 * explanation, enable switch, model list, and "add model" entry point. A HuggingFace section
 * at the bottom holds the optional access token used for in-app model discovery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiManagementScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onBrowse: (ModelRepository.Purpose) -> Unit
) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()
    val embedModelId by vm.embedModelId.collectAsStateWithLifecycle()
    val genModelId by vm.genModelId.collectAsStateWithLifecycle()
    val aiSearchEnabled by vm.aiSearchEnabled.collectAsStateWithLifecycle()
    val aiTagsEnabled by vm.aiTagsEnabled.collectAsStateWithLifecycle()
    val embedBackend by vm.embedBackend.collectAsStateWithLifecycle()
    val llmState by vm.llmState.collectAsStateWithLifecycle()
    val hfToken by vm.hfToken.collectAsStateWithLifecycle()
    val unloadMinutes by vm.unloadMinutes.collectAsStateWithLifecycle()

    val allSpecs = ModelRepository.CATALOG + customModels
    val embedModels = allSpecs.filter { it.purpose == ModelRepository.Purpose.EMBEDDING }
    val genModels = allSpecs.filter { it.purpose == ModelRepository.Purpose.GENERATION }

    val embedReady = states[embedModelId] is ModelRepository.State.Ready
    val genId = genModelId
    val genReady = genId != null && states[genId] is ModelRepository.State.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI & Models", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item {
                ExplainerCard(
                    icon = Icons.Default.Lock,
                    title = "Runs on your device",
                    body = "Every model runs entirely on this device using the Snapdragon NPU when " +
                        "possible. Nothing you search or type is ever sent to a server."
                )
            }

            // ---- Semantic search (embedding models) ---------------------------
            item { SectionHeader("Semantic search", Icons.Default.Search) }
            item {
                Text(
                    "Small models that understand meaning, so search finds related items even when " +
                        "the words differ — \"charger\" finds \"power adapter\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Enable semantic search") },
                    supportingContent = {
                        Text(if (embedReady) "Rank results by meaning, not just keywords"
                             else "Download a model below to enable")
                    },
                    trailingContent = {
                        Switch(
                            checked = aiSearchEnabled && embedReady,
                            enabled = embedReady,
                            onCheckedChange = { vm.setAiSearchEnabled(it) }
                        )
                    }
                )
            }
            if (aiSearchEnabled && embedReady && embedBackend != null) {
                item { AccelerationRow(embedBackend!!) }
            }
            items(embedModels, key = { it.id }) { spec ->
                ModelRow(
                    spec = spec,
                    state = states[spec.id] ?: ModelRepository.State.NotDownloaded,
                    isDefault = spec.id == embedModelId,
                    onSetDefault = { vm.setDefaultEmbedModel(spec.id) },
                    onDownload = { vm.downloadModel(spec.id) },
                    onCancel = { vm.cancelModelDownload(spec.id) },
                    onDelete = { vm.deleteModel(spec.id) }
                )
            }
            item {
                AddModelButton("Browse embedding models") { onBrowse(ModelRepository.Purpose.EMBEDDING) }
            }

            // ---- Tag suggestions (language models) ----------------------------
            item { SectionHeader("Tag suggestions", Icons.Default.AutoAwesome) }
            item {
                Text(
                    "Larger language models that read an item's name and description and suggest " +
                        "fitting tags. They load only when you're adding an item and unload " +
                        "automatically to free memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Enable AI tag suggestions") },
                    supportingContent = {
                        Text(if (genReady) "Suggest tags while adding or editing items"
                             else "Download a language model below to enable")
                    },
                    trailingContent = {
                        Switch(
                            checked = aiTagsEnabled && genReady,
                            enabled = genReady,
                            onCheckedChange = { vm.setAiTagsEnabled(it) }
                        )
                    }
                )
            }
            if (genReady) {
                item { LlmStatusRow(llmState) }
            }
            item {
                MemoryTimeoutRow(
                    minutes = unloadMinutes,
                    enabled = genReady,
                    onSelect = { vm.setUnloadMinutes(it) }
                )
            }
            if (genModels.isEmpty()) {
                item {
                    Text(
                        "No language models added yet. Browse HuggingFace to find one that fits " +
                            "your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            items(genModels, key = { it.id }) { spec ->
                ModelRow(
                    spec = spec,
                    state = states[spec.id] ?: ModelRepository.State.NotDownloaded,
                    isDefault = spec.id == genModelId,
                    onSetDefault = { vm.setDefaultGenModel(spec.id) },
                    onDownload = { vm.downloadModel(spec.id) },
                    onCancel = { vm.cancelModelDownload(spec.id) },
                    onDelete = { vm.deleteModel(spec.id) }
                )
            }
            item {
                AddModelButton("Browse language models") { onBrowse(ModelRepository.Purpose.GENERATION) }
            }

            // ---- HuggingFace account ------------------------------------------
            item { SectionHeader("HuggingFace", Icons.Default.Key) }
            item {
                Text(
                    "Add a free HuggingFace access token to raise download rate limits and reach " +
                        "gated models. Generate one at huggingface.co/settings/tokens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item { HfTokenField(token = hfToken, onSave = { vm.setHfToken(it) }) }
        }
    }
}

@Composable
private fun AccelerationRow(backend: AiBackend) {
    val (tint, icon) = when (backend) {
        AiBackend.NPU -> MaterialTheme.colorScheme.primary to Icons.Default.Bolt
        AiBackend.GPU -> MaterialTheme.colorScheme.tertiary to Icons.Default.Memory
        AiBackend.CPU -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Default.Memory
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(
            "Running on ${backend.label}",
            style = MaterialTheme.typography.labelMedium,
            color = tint
        )
    }
}

@Composable
private fun LlmStatusRow(state: LlmEngineManager.State) {
    val (text, tint) = when (state) {
        is LlmEngineManager.State.Loading -> "Loading model…" to MaterialTheme.colorScheme.onSurfaceVariant
        is LlmEngineManager.State.Generating -> "Generating…" to MaterialTheme.colorScheme.primary
        is LlmEngineManager.State.Ready -> "Loaded in memory · ${state.backend.label}" to MaterialTheme.colorScheme.primary
        is LlmEngineManager.State.Error -> state.message to MaterialTheme.colorScheme.error
        LlmEngineManager.State.Unloaded -> "Not loaded — loads on demand, frees memory when idle" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val busy = state is LlmEngineManager.State.Loading || state is LlmEngineManager.State.Generating
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Memory, null, Modifier.size(16.dp), tint = tint)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun MemoryTimeoutRow(minutes: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val label = when (minutes) {
        0 -> "Keep loaded"
        else -> "After $minutes min idle"
    }
    ListItem(
        leadingContent = { Icon(Icons.Default.Memory, null) },
        headlineContent = { Text("Unload from memory") },
        supportingContent = { Text("Free RAM when the model isn't in use") },
        trailingContent = {
            Box {
                TextButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Text(label)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(2 to "After 2 min idle", 5 to "After 5 min idle", 0 to "Keep loaded")
                        .forEach { (m, text) ->
                            DropdownMenuItem(
                                text = { Text(text) },
                                onClick = { onSelect(m); menuOpen = false },
                                trailingIcon = if (m == minutes) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                }
            }
        }
    )
}

@Composable
private fun ModelRow(
    spec: ModelRepository.ModelSpec,
    state: ModelRepository.State,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val isReady = state is ModelRepository.State.Ready
    ListItem(
        modifier = if (isReady) Modifier.clickable { onSetDefault() } else Modifier,
        leadingContent = {
            if (isReady) RadioButton(selected = isDefault, onClick = onSetDefault)
            else Icon(
                if (spec.purpose == ModelRepository.Purpose.GENERATION) Icons.Default.AutoAwesome
                else Icons.Default.Search,
                null
            )
        },
        headlineContent = { Text(spec.displayName) },
        supportingContent = {
            val sub = when (state) {
                is ModelRepository.State.Downloading ->
                    if (state.progress >= 0f) "Downloading ${(state.progress * 100).toInt()}%"
                    else "Downloading…"
                is ModelRepository.State.Ready -> if (isDefault) "Default · ready" else "Ready"
                is ModelRepository.State.Failed -> "Failed: ${state.message}"
                else -> spec.description
            }
            Text(sub)
        },
        trailingContent = {
            when (state) {
                is ModelRepository.State.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { if (state.progress >= 0f) state.progress else 0f },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                }
                is ModelRepository.State.Ready -> IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
                else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download") }
            }
        }
    )
}

@Composable
private fun AddModelButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedButton(onClick = onClick) {
            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
private fun HfTokenField(token: String, onSave: (String) -> Unit) {
    var draft by remember(token) { mutableStateOf(token) }
    var reveal by remember { mutableStateOf(false) }
    val dirty = draft != token
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Access token (optional)") },
            placeholder = { Text("hf_…") },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (reveal) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (dirty) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { draft = token }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(draft) }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ExplainerCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

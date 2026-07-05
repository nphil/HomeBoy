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
import androidx.compose.ui.graphics.vector.ImageVector
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
 * On-device AI manager. Two capabilities — semantic search (embedding models) and tag suggestions
 * (language models). Each downloaded model shows where it runs (NPU/GPU/CPU), lets the user override
 * that backend, eject it from memory, or delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiManagementScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onBrowse: (ModelRepository.Purpose) -> Unit,
    onBenchmark: () -> Unit
) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()
    val embedModelId by vm.embedModelId.collectAsStateWithLifecycle()
    val genModelId by vm.genModelId.collectAsStateWithLifecycle()
    val aiSearchEnabled by vm.aiSearchEnabled.collectAsStateWithLifecycle()
    val aiTagsEnabled by vm.aiTagsEnabled.collectAsStateWithLifecycle()
    val embedBackend by vm.embedBackend.collectAsStateWithLifecycle()
    val llmState by vm.llmState.collectAsStateWithLifecycle()
    val llmLastBackend by vm.llmLastBackend.collectAsStateWithLifecycle()
    val llmLastModelId by vm.llmLastModelId.collectAsStateWithLifecycle()
    val hfToken by vm.hfToken.collectAsStateWithLifecycle()
    val unloadMinutes by vm.unloadMinutes.collectAsStateWithLifecycle()
    val backends by vm.modelBackends.collectAsStateWithLifecycle()

    val allSpecs = ModelRepository.CATALOG + customModels
    val embedModels = allSpecs.filter { it.purpose == ModelRepository.Purpose.EMBEDDING }
    val genModels = allSpecs.filter { it.purpose == ModelRepository.Purpose.GENERATION }

    val embedReady = states[embedModelId] is ModelRepository.State.Ready
    val genId = genModelId
    val genReady = genId != null && states[genId] is ModelRepository.State.Ready

    // Which gen model is resident, and on which tier — used to badge the right row "live".
    val genLoadedId = (llmState as? LlmEngineManager.State.Ready)?.modelId
        ?: (llmState as? LlmEngineManager.State.Loading)?.modelId
    val genLiveBackend = (llmState as? LlmEngineManager.State.Ready)?.backend

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
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)
        ) {
            // ---- Semantic search (embedding models) ---------------------------
            item { SectionHeader("Semantic search", Icons.Default.Search) }
            item { SectionBlurb("Finds related items even when the words differ — “charger” finds “power adapter.”") }
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
            items(embedModels, key = { it.id }) { spec ->
                ModelRow(
                    spec = spec,
                    state = states[spec.id] ?: ModelRepository.State.NotDownloaded,
                    isDefault = spec.id == embedModelId,
                    override = AiBackend.fromToken(backends[spec.id]),
                    // Show the actual engaged backend whenever the engine is built (not gated on
                    // aiSearchEnabled) so the chip always reflects truth, not the requested tier.
                    liveBackend = if (spec.id == embedModelId) embedBackend else null,
                    lastUsedBackend = null, // embedBackend already persists across idle; no extra tracking needed
                    deviceBackends = vm.deviceBackends,
                    onSetDefault = { vm.setDefaultEmbedModel(spec.id) },
                    onSetBackend = { vm.setModelBackend(spec.id, it) },
                    onUnload = { vm.unloadEmbed() },
                    onDownload = { vm.downloadModel(spec.id) },
                    onCancel = { vm.cancelModelDownload(spec.id) },
                    onDelete = { vm.deleteModel(spec.id) }
                )
            }
            item { AddModelButton("Browse embedding models") { onBrowse(ModelRepository.Purpose.EMBEDDING) } }

            // ---- Tag suggestions (language models) ----------------------------
            item { SectionHeader("Tag suggestions", Icons.Default.AutoAwesome) }
            item { SectionBlurb("Reads an item's name and description and suggests fitting tags. Loads on demand and unloads when idle.") }
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
            item { MemoryTimeoutRow(minutes = unloadMinutes, enabled = genReady, onSelect = { vm.setUnloadMinutes(it) }) }
            if (genModels.isEmpty()) {
                item { SectionBlurb("No language models yet. Browse HuggingFace to find one that fits your device.") }
            }
            items(genModels, key = { it.id }) { spec ->
                ModelRow(
                    spec = spec,
                    state = states[spec.id] ?: ModelRepository.State.NotDownloaded,
                    isDefault = spec.id == genModelId,
                    override = AiBackend.fromToken(backends[spec.id]),
                    liveBackend = if (spec.id == genLoadedId) genLiveBackend else null,
                    lastUsedBackend = if (spec.id == llmLastModelId) llmLastBackend else null,
                    deviceBackends = vm.deviceBackends,
                    onSetDefault = { vm.setDefaultGenModel(spec.id) },
                    onSetBackend = { vm.setModelBackend(spec.id, it) },
                    onUnload = { vm.unloadLlm() },
                    onDownload = { vm.downloadModel(spec.id) },
                    onCancel = { vm.cancelModelDownload(spec.id) },
                    onDelete = { vm.deleteModel(spec.id) }
                )
            }
            item { AddModelButton("Browse language models") { onBrowse(ModelRepository.Purpose.GENERATION) } }

            // ---- Benchmarking -------------------------------------------------
            item { SectionHeader("Benchmarking", Icons.Default.Speed) }
            item { SectionBlurb("Compare your downloaded models head-to-head — quality and speed on NPU, GPU or CPU.") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Speed, null) },
                    headlineContent = { Text("Benchmark models") },
                    supportingContent = { Text("Run embedders or tag generators side by side") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { onBenchmark() }
                )
            }

            // ---- HuggingFace account ------------------------------------------
            item { SectionHeader("HuggingFace", Icons.Default.Key) }
            item { SectionBlurb("Add a free access token to raise download limits and reach gated models. Generate one at huggingface.co/settings/tokens.") }
            item { HfTokenField(token = hfToken, onSave = { vm.setHfToken(it) }) }
        }
    }
}

// ---- Backend (NPU/GPU/CPU) helpers -----------------------------------------

private fun AiBackend.icon(): ImageVector = when (this) {
    AiBackend.NPU -> Icons.Default.Bolt
    AiBackend.GPU -> Icons.Default.DeveloperBoard
    AiBackend.CPU -> Icons.Default.Memory
}

/** The smart default tier for a purpose when the user hasn't overridden it. */
private fun defaultBackend(purpose: ModelRepository.Purpose): AiBackend =
    if (purpose == ModelRepository.Purpose.EMBEDDING) AiBackend.NPU else AiBackend.CPU

/** Whether [tier] can run [purpose] models on this device, and why not when it can't. */
private fun availability(
    tier: AiBackend,
    purpose: ModelRepository.Purpose,
    device: Set<AiBackend>
): Pair<Boolean, String?> = when (tier) {
    AiBackend.CPU -> true to null
    AiBackend.NPU ->
        if (AiBackend.NPU in device) true to null else false to "No NPU on this device"
    AiBackend.GPU -> when {
        purpose == ModelRepository.Purpose.EMBEDDING -> false to "Not supported for embedding models"
        AiBackend.GPU in device -> true to null
        else -> false to "No GPU on this device"
    }
}

/**
 * Tappable chip showing where a model actually ran, with a dropdown to override it.
 *
 * Three visual states:
 * - **Active** ([liveBackend] != null) — model loaded in memory right now → primaryContainer,
 *   bold label, live indicator dot.
 * - **Last used** ([lastUsedBackend] != null, not live) — model unloaded but we know what tier
 *   it engaged → secondaryContainer, "(last)" suffix so it's honest, not a guess.
 * - **Target** (no runtime data) — model never run or engine invalidated → dim, shows the
 *   requested/auto tier as a hint only.
 */
@Composable
private fun BackendChip(
    purpose: ModelRepository.Purpose,
    override: AiBackend?,
    liveBackend: AiBackend?,
    lastUsedBackend: AiBackend?,
    device: Set<AiBackend>,
    onSetBackend: (AiBackend?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val target = override ?: defaultBackend(purpose)

    val isLive = liveBackend != null
    val isLastUsed = !isLive && lastUsedBackend != null
    val shown = liveBackend ?: lastUsedBackend ?: target

    val chipColor = when {
        isLive -> MaterialTheme.colorScheme.primaryContainer
        isLastUsed -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        isLive -> MaterialTheme.colorScheme.onPrimaryContainer
        isLastUsed -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        isLive -> shown.shortLabel
        isLastUsed -> "${shown.shortLabel} (last used)"
        else -> shown.shortLabel
    }

    // In the Auto dropdown item, tell the user what the smart default actually is and, when we
    // know what tier engaged last, call that out so "Auto" isn't a mystery.
    val autoSuffix = when {
        lastUsedBackend != null || liveBackend != null -> {
            val known = liveBackend ?: lastUsedBackend!!
            if (known == defaultBackend(purpose)) " · ${known.shortLabel}"
            else " · ${defaultBackend(purpose).shortLabel} (last: ${known.shortLabel})"
        }
        else -> " · ${defaultBackend(purpose).shortLabel}"
    }

    Box {
        Surface(
            color = chipColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable { open = true }
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(shown.icon(), null, Modifier.size(14.dp), tint = contentColor)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isLive) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor
                )
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Run on", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            // Auto resets to the smart default; shows what tier that is + the last-used hint.
            DropdownMenuItem(
                text = { Text("Auto$autoSuffix") },
                onClick = { onSetBackend(null); open = false },
                leadingIcon = { Icon(Icons.Default.AutoMode, null, Modifier.size(18.dp)) },
                trailingIcon = if (override == null) {
                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                } else null
            )
            HorizontalDivider()
            listOf(AiBackend.NPU, AiBackend.GPU, AiBackend.CPU).forEach { tier ->
                val (avail, reason) = availability(tier, purpose, device)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(tier.label)
                            if (!avail && reason != null) Text(reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    enabled = avail,
                    onClick = { onSetBackend(tier); open = false },
                    leadingIcon = { Icon(tier.icon(), null, Modifier.size(18.dp)) },
                    trailingIcon = if (override == tier) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelRepository.ModelSpec,
    state: ModelRepository.State,
    isDefault: Boolean,
    override: AiBackend?,
    liveBackend: AiBackend?,
    lastUsedBackend: AiBackend?,
    deviceBackends: Set<AiBackend>,
    onSetDefault: () -> Unit,
    onSetBackend: (AiBackend?) -> Unit,
    onUnload: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val isReady = state is ModelRepository.State.Ready
    Column {
        ListItem(
            modifier = if (isReady) Modifier.clickable { onSetDefault() } else Modifier,
            leadingContent = {
                if (isReady) RadioButton(selected = isDefault, onClick = onSetDefault)
                else Icon(
                    if (spec.purpose == ModelRepository.Purpose.GENERATION) Icons.Default.AutoAwesome
                    else Icons.Default.Search, null
                )
            },
            headlineContent = { Text(spec.displayName) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val status = when (state) {
                        is ModelRepository.State.Downloading ->
                            if (state.progress >= 0f) "Downloading ${(state.progress * 100).toInt()}%" else "Downloading…"
                        is ModelRepository.State.Ready -> if (isDefault) "Default" else "Ready"
                        is ModelRepository.State.Failed -> "Failed: ${state.message}"
                        else -> spec.description
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (isReady) {
                        BackendChip(
                            purpose = spec.purpose,
                            override = override,
                            liveBackend = liveBackend,
                            lastUsedBackend = lastUsedBackend,
                            device = deviceBackends,
                            onSetBackend = onSetBackend
                        )
                    }
                }
            },
            trailingContent = {
                when (state) {
                    is ModelRepository.State.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            progress = { if (state.progress >= 0f) state.progress else 0f },
                            modifier = Modifier.size(22.dp), strokeWidth = 2.dp
                        )
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                    }
                    is ModelRepository.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (liveBackend != null) {
                            IconButton(onClick = onUnload) {
                                Icon(Icons.Default.Eject, "Unload from memory",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.DeleteOutline, "Delete",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download") }
                }
            }
        )
        if (state is ModelRepository.State.Downloading) {
            LinearProgressIndicator(
                progress = { if (state.progress >= 0f) state.progress else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun MemoryTimeoutRow(minutes: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val label = if (minutes == 0) "Keep loaded" else "After $minutes min idle"
    ListItem(
        leadingContent = { Icon(Icons.Default.Timer, null) },
        headlineContent = { Text("Unload from memory") },
        supportingContent = { Text("Free RAM when the model isn't in use") },
        trailingContent = {
            Box {
                TextButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Text(label); Icon(Icons.Default.ArrowDropDown, null)
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
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { draft = token }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(draft) }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionBlurb(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

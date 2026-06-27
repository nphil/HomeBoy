package com.homeboy.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeboy.app.ai.AiBackend
import com.homeboy.app.ai.HuggingFaceRepository
import com.homeboy.app.ai.ModelRepository
import com.homeboy.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * In-app HuggingFace model browser. Searches ONNX models for the given [purpose], lets the user
 * filter by the hardware tier a model supports (NPU / GPU / CPU), and shows — per result — how it
 * would actually run on this device before downloading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceSearchScreen(
    vm: SettingsViewModel,
    purpose: ModelRepository.Purpose,
    onBack: () -> Unit
) {
    val results by vm.hfResults.collectAsStateWithLifecycle()
    val loading by vm.hfLoading.collectAsStateWithLifecycle()
    val sort by vm.hfSort.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<HuggingFaceRepository.HfModel?>(null) }

    // (Re)search when the screen opens and whenever the sort order changes. Typing only
    // searches on submit, so the query is read but isn't a trigger here.
    LaunchedEffect(purpose, sort) {
        vm.searchHuggingFace(query, purpose)
    }

    val title = when (purpose) {
        ModelRepository.Purpose.EMBEDDING -> "Embedding models"
        ModelRepository.Purpose.GENERATION -> "Language models"
    }
    val emptyMsg = when (purpose) {
        ModelRepository.Purpose.EMBEDDING -> "No matching ONNX models found."
        ModelRepository.Purpose.GENERATION -> "No matching language models found."
    }

    /** The download state of a result, looked up via the custom model it was added as. */
    fun stateOf(model: HuggingFaceRepository.HfModel): ModelRepository.State? {
        val id = customModels.firstOrNull { it.displayName == model.name }?.id ?: return null
        return states[id]
    }

    detail?.let { model ->
        ModelDetailSheet(
            vm = vm,
            model = model,
            purpose = purpose,
            alreadyAdded = customModels.any { it.displayName == model.name },
            onAdd = { plan ->
                if (purpose == ModelRepository.Purpose.GENERATION) {
                    plan.firstOrNull()?.let { vm.addHfGenModel(model, it.remotePath) }
                } else {
                    val onnx = plan.firstOrNull { it.localName == "model.onnx" }?.remotePath
                    val vocab = plan.firstOrNull { it.localName == "vocab.txt" }?.remotePath
                    if (onnx != null && vocab != null) vm.addHfEmbeddingModel(model, onnx, vocab)
                }
                detail = null
            },
            onDismiss = { detail = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search HuggingFace") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchHuggingFace(query, purpose) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Sort order (server-side).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HuggingFaceRepository.Sort.entries.forEach { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { vm.setHfSort(s) },
                        label = { Text(s.label) }
                    )
                }
            }

            Text(
                "Tap a model to see how it would run on your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyMsg, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(results, key = { it.id }) { model ->
                        HfResultCard(model = model, state = stateOf(model), onClick = { detail = model })
                    }
                }
            }
        }
    }
}

@Composable
private fun HfResultCard(
    model: HuggingFaceRepository.HfModel,
    state: ModelRepository.State?,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                model.author.ifBlank { "huggingface" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaStat(Icons.Default.Download, formatCount(model.downloads))
                MetaStat(Icons.Default.Favorite, formatCount(model.likes))
            }
            CompatBadges(model.compat)
            // Download status for a model that's already been added.
            when (state) {
                is ModelRepository.State.Downloading -> {
                    val p = state.progress
                    LinearProgressIndicator(
                        progress = { if (p >= 0f) p else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    )
                    Text(if (p >= 0f) "Downloading ${(p * 100).toInt()}%" else "Downloading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                is ModelRepository.State.Ready -> Text("Downloaded",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                is ModelRepository.State.Failed -> Text("Failed: ${state.message}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
        }
    }
}

@Composable
private fun MetaStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small chips for every tier the model can run on, highlighting its best (fastest) one. */
@Composable
private fun CompatBadges(compat: HuggingFaceRepository.Compatibility) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compat.quantizedOnnx.isNotEmpty()) TierBadge(AiBackend.NPU, highlight = compat.best == AiBackend.NPU)
        if (compat.floatOnnx.isNotEmpty() || compat.hasMediaPipe) TierBadge(AiBackend.GPU, highlight = compat.best == AiBackend.GPU)
        if (compat.isRunnable) TierBadge(AiBackend.CPU, highlight = false)
    }
}

@Composable
private fun TierBadge(backend: AiBackend, highlight: Boolean) {
    val container = if (highlight) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (highlight) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            backend.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailSheet(
    vm: SettingsViewModel,
    model: HuggingFaceRepository.HfModel,
    purpose: ModelRepository.Purpose,
    alreadyAdded: Boolean,
    onAdd: (List<HuggingFaceRepository.PlannedFile>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var files by remember(model.id) { mutableStateOf<List<HuggingFaceRepository.HfFile>?>(null) }
    LaunchedEffect(model.id) { scope.launch { files = vm.hfFiles(model.id) } }

    // Only the file(s) we'll actually download — not the whole repo — so size + count are real.
    val plan = files?.let { HuggingFaceRepository.planDownload(model, purpose, it) } ?: emptyList()
    val planBytes = plan.sumOf { it.size }
    val tooLarge = planBytes > 8L * 1024 * 1024 * 1024 // 8 GB safe ceiling on a 12 GB device

    val isEmbedding = purpose == ModelRepository.Purpose.EMBEDDING
    val formatOk = if (isEmbedding)
        plan.any { it.localName == "model.onnx" } && plan.any { it.localName == "vocab.txt" }
    else plan.any { it.localName == "model.task" }
    val canAdd = formatOk && !tooLarge && !alreadyAdded

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(model.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(model.id, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            Text("How it would run", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            RunsOnLine(model.compat)

            HorizontalDivider()

            Text("Download", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when {
                files == null -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Checking size…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(
                    "${plan.size} model file(s) · ≈ ${formatBytes(planBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Honest gating with the reason.
            val warning = when {
                files == null -> null
                tooLarge -> "Too large for this device (≈ ${formatBytes(planBytes)}; ~8 GB safe max)."
                isEmbedding && !formatOk ->
                    "Missing a quantized ONNX + vocab.txt — can't run as an embedding model here."
                !isEmbedding && !formatOk ->
                    "No MediaPipe (.task) file — this model can't run with the on-device LLM engine."
                alreadyAdded -> "Already added."
                else -> null
            }
            warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { onAdd(plan) },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (alreadyAdded) "Added" else "Download & add")
            }
        }
    }
}

@Composable
private fun RunsOnLine(compat: HuggingFaceRepository.Compatibility) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compat.quantizedOnnx.isNotEmpty()) {
            CompatExplain(AiBackend.NPU, "Quantized export — fastest, most power-efficient.")
        }
        if (compat.hasMediaPipe) {
            CompatExplain(AiBackend.GPU, "MediaPipe bundle — runs on the Adreno GPU.")
        } else if (compat.floatOnnx.isNotEmpty()) {
            CompatExplain(AiBackend.GPU, "Float export — runs on the Adreno GPU.")
        }
        if (compat.isRunnable) {
            CompatExplain(AiBackend.CPU, "Always works as a fallback.")
        } else {
            Text("No usable export — can't run on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CompatExplain(backend: AiBackend, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TierBadge(backend, highlight = true)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}

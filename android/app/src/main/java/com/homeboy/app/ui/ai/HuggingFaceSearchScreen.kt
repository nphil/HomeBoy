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
    val filter by vm.hfFilter.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<HuggingFaceRepository.HfModel?>(null) }

    // Run an initial popular-models search when the screen opens.
    LaunchedEffect(purpose) {
        vm.clearHfResults()
        vm.searchHuggingFace("", purpose)
    }

    val title = when (purpose) {
        ModelRepository.Purpose.EMBEDDING -> "Embedding models"
        ModelRepository.Purpose.GENERATION -> "Language models"
    }

    val filtered = results.filter { m ->
        when (filter) {
            AiBackend.NPU -> m.compat.quantizedOnnx.isNotEmpty()
            AiBackend.GPU -> m.compat.floatOnnx.isNotEmpty() || m.compat.hasMediaPipe
            AiBackend.CPU -> m.compat.isRunnable
            null -> true
        }
    }

    detail?.let { model ->
        ModelDetailSheet(
            vm = vm,
            model = model,
            purpose = purpose,
            alreadyAdded = customModels.any { it.displayName == model.name },
            onAdd = {
                if (purpose == ModelRepository.Purpose.GENERATION) vm.addHfGenModel(model)
                else vm.addHfEmbeddingModel(model)
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

            // Hardware-tier filter. MediaPipe (generation) never uses the NPU, so that chip
            // only appears for embedding models.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TierChip("All", filter == null) { vm.setHfFilter(null) }
                if (purpose == ModelRepository.Purpose.EMBEDDING) {
                    TierChip("NPU", filter == AiBackend.NPU) { vm.setHfFilter(AiBackend.NPU) }
                }
                TierChip("GPU", filter == AiBackend.GPU) { vm.setHfFilter(AiBackend.GPU) }
                TierChip("CPU", filter == AiBackend.CPU) { vm.setHfFilter(AiBackend.CPU) }
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
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No matching ONNX models found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        HfResultCard(model = model, onClick = { detail = model })
                    }
                }
            }
        }
    }
}

@Composable
private fun TierChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun HfResultCard(model: HuggingFaceRepository.HfModel, onClick: () -> Unit) {
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
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var files by remember(model.id) { mutableStateOf<List<HuggingFaceRepository.HfFile>?>(null) }
    LaunchedEffect(model.id) { scope.launch { files = vm.hfFiles(model.id) } }

    val totalBytes = files?.sumOf { it.size } ?: 0L
    val tooLarge = totalBytes > 8L * 1024 * 1024 * 1024 // 8 GB safe ceiling on a 12 GB device

    // Embedding models need a vocab.txt for the WordPiece tokenizer; generation models need a
    // MediaPipe .task/.litertlm bundle.
    val isEmbedding = purpose == ModelRepository.Purpose.EMBEDDING
    val formatOk = if (isEmbedding) model.compat.hasVocab else model.compat.hasMediaPipe
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
                    "${model.files.count { f ->
                        f.endsWith(".onnx", true) || f.endsWith(".task", true) || f.endsWith(".litertlm", true)
                    }} model file(s) · ≈ ${formatBytes(totalBytes)} total",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Honest gating with the reason.
            val warning = when {
                tooLarge -> "Too large for this device (≈ ${formatBytes(totalBytes)}; ~8 GB safe max)."
                isEmbedding && !model.compat.hasVocab ->
                    "Missing vocab.txt — this embedding model can't be tokenized here."
                !isEmbedding && !model.compat.hasMediaPipe ->
                    "No MediaPipe (.task) file — this model can't run with the on-device LLM engine."
                alreadyAdded -> "Already added."
                else -> null
            }
            warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = onAdd,
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

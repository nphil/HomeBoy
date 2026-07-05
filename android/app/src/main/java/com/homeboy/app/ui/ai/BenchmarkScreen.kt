package com.homeboy.app.ui.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.BenchmarkRunner
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Head-to-head model benchmarking. Pick the downloaded models to compare, choose a backend
 * (NPU / GPU / CPU / All), run them on your real items (embedder) or a sample item (tag
 * generator), and see quality + performance side by side. Android analogue of the iOS
 * `AIBenchmarkView`, with an extra NPU option the iPhone doesn't have.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: BenchmarkViewModel = viewModel(factory = BenchmarkViewModel.factory(app))

    val mode by vm.mode.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val useMyItems by vm.useMyItems.collectAsStateWithLifecycle()
    val customText by vm.customText.collectAsStateWithLifecycle()
    val tagName by vm.tagName.collectAsStateWithLifecycle()
    val tagDesc by vm.tagDesc.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val embedRows by vm.embedRows.collectAsStateWithLifecycle()
    val llmRows by vm.llmRows.collectAsStateWithLifecycle()
    val savedRuns by vm.savedRuns.collectAsStateWithLifecycle()
    val available by vm.availableModels.collectAsStateWithLifecycle()

    val isEmbedder = mode == BenchmarkViewModel.Mode.EMBEDDER
    val inputValid = if (isEmbedder) query.isNotBlank() else tagName.trim().length >= 3
    val hasResults = if (isEmbedder) embedRows.isNotEmpty() else llmRows.isNotEmpty()
    val myItemCount = remember { vm.myItemCount }
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Benchmarking", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = BenchmarkViewModel.Mode.entries
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { vm.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                    ) { Text(m.label) }
                }
            }

            // Models to compare — wrapping chips (no horizontal scroll), with a select-all toggle.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("MODELS TO COMPARE")
                if (available.isNotEmpty()) {
                    val allSelected = selected.size == available.size
                    TextButton(
                        onClick = { if (allSelected) vm.clearSelection() else vm.selectAll() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text(if (allSelected) "Clear" else "Select all", style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (available.isEmpty()) {
                Text(
                    "No downloaded ${if (isEmbedder) "embedders" else "language models"}. Add one in AI & Models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    available.forEach { spec ->
                        val on = spec.id in selected
                        FilterChip(
                            selected = on,
                            onClick = { vm.toggleModel(spec.id) },
                            label = { Text(spec.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (on) {
                                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            // Backend
            SectionLabel("BACKEND")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val backends = BenchmarkViewModel.BenchBackend.entries
                backends.forEachIndexed { i, b ->
                    SegmentedButton(
                        selected = backend == b,
                        onClick = { vm.setBackend(b) },
                        shape = SegmentedButtonDefaults.itemShape(i, backends.size)
                    ) { Text(b.label) }
                }
            }

            // Input
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == BenchmarkViewModel.Mode.EMBEDDER) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            label = { Text("Query (e.g. lubricant)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Use my items ($myItemCount)", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = useMyItems, onCheckedChange = vm::setUseMyItems)
                        }
                        if (!useMyItems) {
                            OutlinedTextField(
                                value = customText,
                                onValueChange = vm::setCustomText,
                                label = { Text("Candidates (one per line)") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = tagName,
                            onValueChange = vm::setTagName,
                            label = { Text("Item name (e.g. cordless drill)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = tagDesc,
                            onValueChange = vm::setTagDesc,
                            label = { Text("Description (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Run
            Button(
                onClick = { vm.run() },
                enabled = !running && selected.isNotEmpty() && inputValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (running) "Running…" else "Run benchmark", fontWeight = FontWeight.SemiBold)
            }

            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }

            // Results — ranked leaderboard so the fastest is obvious at a glance.
            if (hasResults) {
                SectionLabel("RESULTS")
                ResultsLeaderboard(isEmbedder, embedRows, llmRows)
            }

            if (hasResults) {
                OutlinedButton(
                    onClick = { vm.saveCurrentRun(dateFmt.format(Date())) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save this run") }
            }

            if (savedRuns.isNotEmpty()) {
                SectionLabel("SAVED RUNS")
                savedRuns.forEach { run -> SavedRunCard(run, onDelete = { vm.deleteRun(run.id) }) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

// ---- Results leaderboard ---------------------------------------------------

private enum class SortKey(val label: String) { THROUGHPUT("Throughput"), LOAD("Load time") }

/** Unified per-(model × backend) view used to rank either result type on one shared scale. */
private data class BenchView(
    val id: String,
    val name: String,
    val backend: String,
    val failed: Boolean,
    val error: String?,
    val throughput: Double,   // emb/s or tok/s — higher is better
    val loadMs: Double,
    val throughputEstimated: Boolean,
)

private fun fmtThroughput(x: Double, unit: String): String =
    if (unit == "tok/s") "%.1f".format(x) else "%.0f".format(x)

/**
 * Ranked leaderboard: every model×backend result on one shared scale, sorted so the fastest is
 * row #1 with a full-width bar. The bar always means "longer = better" — when sorting by load time
 * the fraction is inverted so the quickest-loading model still gets the longest bar. Failures are
 * pinned, unranked, at the bottom. (Design per graphical-perception + Material 3 guidance.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsLeaderboard(
    isEmbedder: Boolean,
    embedRows: List<BenchmarkRunner.EmbedRow>,
    llmRows: List<BenchmarkRunner.LLMRow>,
) {
    var sortKey by remember { mutableStateOf(SortKey.THROUGHPUT) }
    val unit = if (isEmbedder) "emb/s" else "tok/s"

    val views = if (isEmbedder) {
        embedRows.map { BenchView(it.id, it.modelName, it.backend, it.failed, it.error, it.embedsPerSec, it.loadMs, false) }
    } else {
        llmRows.map { BenchView(it.id, it.modelName, it.backend, it.failed, it.error, it.tokensPerSec, it.loadMs, it.genTokensEstimated) }
    }
    val ok = views.filter { !it.failed }
    val failed = views.filter { it.failed }
    val ranked = when (sortKey) {
        SortKey.THROUGHPUT -> ok.sortedByDescending { it.throughput }
        SortKey.LOAD -> ok.sortedBy { it.loadMs }
    }
    val maxTp = ok.maxOfOrNull { it.throughput }?.takeIf { it > 0 } ?: 1.0
    val minLoad = ok.filter { it.loadMs > 0 }.minOfOrNull { it.loadMs } ?: 1.0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SortKey.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = sortKey == k,
                    onClick = { sortKey = k },
                    shape = SegmentedButtonDefaults.itemShape(i, SortKey.entries.size)
                ) { Text("By ${k.label}") }
            }
        }
        Text(
            if (sortKey == SortKey.THROUGHPUT) "Higher is better — longer bar = faster"
            else "Lower is better — longer bar = loads faster",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ranked.forEachIndexed { i, v ->
            val fraction = if (sortKey == SortKey.THROUGHPUT) v.throughput / maxTp
            else if (v.loadMs > 0) minLoad / v.loadMs else 0.0
            RankedResultRow(
                rank = i + 1, v = v, unit = unit, sortKey = sortKey,
                fraction = fraction.toFloat(), winner = i == 0
            ) {
                if (isEmbedder) embedRows.firstOrNull { it.id == v.id }?.let { EmbedDetail(it) }
                else llmRows.firstOrNull { it.id == v.id }?.let { LlmDetail(it) }
            }
        }

        if (failed.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 2.dp))
            Text("Didn't run", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            failed.forEach { FailedResultRow(it) }
        }

        val estNote = !isEmbedder && llmRows.any { !it.failed && it.genTokensEstimated }
        Text(
            (if (isEmbedder) "Emb/s = embeddings/second" else "Tok/s = generated tokens/second") +
                " · Load = ms to load" + if (estNote) " · ~ = estimated" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RankedResultRow(
    rank: Int,
    v: BenchView,
    unit: String,
    sortKey: SortKey,
    fraction: Float,
    winner: Boolean,
    detail: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val container = if (winner) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow
    val est = if (v.throughputEstimated) "~" else ""
    val byThroughput = sortKey == SortKey.THROUGHPUT
    val primaryValue = if (byThroughput) est + fmtThroughput(v.throughput, unit) else "${v.loadMs.toInt()}"
    val primaryUnit = if (byThroughput) unit else "ms load"
    val secondary = if (byThroughput) "load ${v.loadMs.toInt()} ms"
    else "$est${fmtThroughput(v.throughput, unit)} $unit"

    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RankBadge(rank, winner)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (winner) FastestPill()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackendTag(v.backend)
                        Text(secondary, style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MetricBar(fraction)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 60.dp)) {
                    Text(primaryValue, style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    Text(primaryUnit, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) "Hide details" else "Show details")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                detail()
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int, winner: Boolean) {
    val bg = when {
        winner -> MaterialTheme.colorScheme.primary
        rank <= 3 -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        winner -> MaterialTheme.colorScheme.onPrimary
        rank <= 3 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text("$rank", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun MetricBar(fraction: Float) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "bar")
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier.fillMaxWidth(anim).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun BackendTag(backend: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Text(backend, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun FastestPill() {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
        Text("Fastest", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun FailedResultRow(v: BenchView) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PriorityHigh, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    BackendTag(v.backend)
                }
                Text(v.error ?: "Failed to run", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmbedDetail(r: BenchmarkRunner.EmbedRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Top matches · dim ${r.dim}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val best = r.top.firstOrNull()?.score ?: 0f
        r.top.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                Text("%.3f".format(s.score), style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    color = scoreColor(s.score, best))
            }
        }
    }
}

@Composable
private fun LlmDetail(r: BenchmarkRunner.LLMRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(r.output.ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodySmall)
        val est = if (r.genTokensEstimated) "~" else ""
        Text("$est${r.genTokens} tokens generated", style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun scoreColor(s: Float, best: Float) = when {
    s >= best - 0.03f -> MaterialTheme.colorScheme.primary
    s >= best - 0.08f -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---- Saved runs ------------------------------------------------------------

@Composable
private fun SavedRunCard(run: BenchmarkViewModel.SavedRun, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${run.mode} · ${run.backendMode}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("${run.date} · ${run.input}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) "Collapse" else "Expand")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text("Source: ${run.source}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                run.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}


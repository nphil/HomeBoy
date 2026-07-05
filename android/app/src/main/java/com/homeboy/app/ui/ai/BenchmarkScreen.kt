package com.homeboy.app.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
@OptIn(ExperimentalMaterial3Api::class)
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

    val available = vm.availableModels()
    val inputValid = vm.inputValid()
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

            // Models to compare
            SectionLabel("MODELS TO COMPARE")
            if (available.isEmpty()) {
                Text(
                    "No downloaded ${if (mode == BenchmarkViewModel.Mode.EMBEDDER) "embedders" else "language models"}. " +
                        "Add one in AI & Models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            Text("Use my items (${vm.myItemCount})", Modifier.weight(1f),
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

            // Results
            if (mode == BenchmarkViewModel.Mode.EMBEDDER && embedRows.isNotEmpty()) {
                EmbedTable(embedRows)
                embedRows.forEach { EmbedOutput(it) }
            } else if (mode == BenchmarkViewModel.Mode.TAGS && llmRows.isNotEmpty()) {
                LlmTable(llmRows)
                llmRows.forEach { LlmOutput(it) }
            }

            if (vm.hasResults) {
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

// ---- Embedder results ------------------------------------------------------

@Composable
private fun EmbedTable(rows: List<BenchmarkRunner.EmbedRow>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                HeaderCell("Model", 2.4f); HeaderCell("On", 1f); HeaderCell("Load", 1f); HeaderCell("Emb/s", 1f)
            }
            HorizontalDivider()
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BodyCell(r.modelName, 2.4f, fontWeight = FontWeight.Medium)
                    BodyCell(if (r.failed) "—" else r.backend, 1f)
                    BodyCell(if (r.failed) "—" else "${r.loadMs.toInt()}", 1f, mono = true)
                    BodyCell(if (r.failed) "fail" else "%.0f".format(r.embedsPerSec), 1f, mono = true,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Load = ms to load · Emb/s = embeddings/second",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmbedOutput(r: BenchmarkRunner.EmbedRow) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.modelName} · ${r.backend}", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (!r.failed) Text("dim ${r.dim}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.failed) {
                Text(r.error ?: "Failed to load", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            } else {
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
    }
}

@Composable
private fun scoreColor(s: Float, best: Float) = when {
    s >= best - 0.03f -> MaterialTheme.colorScheme.primary
    s >= best - 0.08f -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---- LLM results -----------------------------------------------------------

@Composable
private fun LlmTable(rows: List<BenchmarkRunner.LLMRow>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                HeaderCell("Model", 2.4f); HeaderCell("On", 1f); HeaderCell("Load", 1f); HeaderCell("Tok/s", 1f)
            }
            HorizontalDivider()
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BodyCell(r.modelName, 2.4f, fontWeight = FontWeight.Medium)
                    BodyCell(if (r.failed) "—" else r.backend, 1f)
                    BodyCell(if (r.failed) "—" else "${r.loadMs.toInt()}", 1f, mono = true)
                    val tps = if (r.genTokensEstimated) "~%.1f".format(r.tokensPerSec) else "%.1f".format(r.tokensPerSec)
                    BodyCell(if (r.failed) "fail" else tps, 1f, mono = true, fontWeight = FontWeight.SemiBold)
                }
            }
            val anyEstimated = rows.any { !it.failed && it.genTokensEstimated }
            Text(
                "Load = ms to load · Tok/s = generated tokens/second" +
                    if (anyEstimated) " (~ = estimated from output length)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LlmOutput(r: BenchmarkRunner.LLMRow) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${r.modelName} · ${r.backend}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (r.failed) {
                Text(r.error ?: "Failed to load or generate", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(r.output.ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodySmall)
                val approx = if (r.genTokensEstimated) "~" else ""
                Text("$approx%.1f tok/s · $approx%d tokens · load %dms".format(r.tokensPerSec, r.genTokens, r.loadMs.toInt()),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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

// ---- Small table cells -----------------------------------------------------

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RowScope.BodyCell(
    text: String,
    weight: Float,
    mono: Boolean = false,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(text, Modifier.weight(weight), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = fontWeight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
}

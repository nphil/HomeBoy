package com.homeboy.app.ui.statistics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBValueOverTime
import com.homeboy.app.ui.formatMoney
import com.homeboy.app.ui.formatMoneyCompact
import com.homeboy.app.ui.parseHbDate
import com.homeboy.app.ui.short
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsTab() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: StatisticsViewModel = viewModel(factory = StatisticsViewModel.factory(app))

    val stats by vm.stats.collectAsStateWithLifecycle()
    val byLocation by vm.byLocation.collectAsStateWithLifecycle()
    val byTag by vm.byTag.collectAsStateWithLifecycle()
    val valueOverTime by vm.valueOverTime.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Statistics", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Refresh") } },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        if (loading && stats == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val s = stats
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            error?.let {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text("Couldn't load some stats: $it",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // Summary tiles (2-column grid)
            if (s != null) {
                val tiles = listOf(
                    StatSpec("Total value", s.totalItemPrice, true, Icons.Default.Payments, MaterialTheme.colorScheme.primary),
                    StatSpec("Items", s.totalItems.toDouble(), false, Icons.Outlined.Inventory2, MaterialTheme.colorScheme.secondary),
                    StatSpec("Locations", s.totalLocations.toDouble(), false, Icons.Default.Place, MaterialTheme.colorScheme.tertiary),
                    StatSpec("Tags", s.totalTags.toDouble(), false, Icons.AutoMirrored.Filled.Label, MaterialTheme.colorScheme.primary),
                    StatSpec("With warranty", s.totalWithWarranty.toDouble(), false, Icons.Default.VerifiedUser, MaterialTheme.colorScheme.secondary),
                    StatSpec("Users", s.totalUsers.toDouble(), false, Icons.Default.Group, MaterialTheme.colorScheme.tertiary)
                )
                items(tiles.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { spec -> StatTile(Modifier.weight(1f), spec) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Value over time
            item {
                ChartCard("Inventory value", "Cumulative value over the past year") {
                    val points = remember(valueOverTime) { buildValuePoints(valueOverTime) }
                    ValueAreaChart(
                        points = points,
                        lineColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
            }

            // Value by location
            if (byLocation.isNotEmpty()) {
                item {
                    ChartCard("Value by location", "Top locations by total value") {
                        val top = byLocation.take(6)
                        val colors = chartPalette(top.size)
                        HorizontalBars(
                            data = top.mapIndexed { i, t -> BarDatum(t.name.ifBlank { "Unnamed" }, t.total.toFloat(), colors[i]) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Value by tag (donut + legend)
            if (byTag.isNotEmpty()) {
                item {
                    ChartCard("Value by tag", "Tap a slice to inspect") {
                        val donutData = remember(byTag) { buildDonutData(byTag) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(
                                data = donutData,
                                modifier = Modifier.size(150.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                donutData.forEach { d ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(d.color))
                                        Spacer(Modifier.width(8.dp))
                                        Text(d.label, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, modifier = Modifier.weight(1f))
                                        Text(formatMoneyCompact(d.value.toDouble()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

private data class StatSpec(
    val label: String,
    val target: Double,
    val money: Boolean,
    val icon: ImageVector,
    val accent: androidx.compose.ui.graphics.Color
)

@Composable
private fun StatTile(modifier: Modifier, spec: StatSpec) {
    var play by remember { mutableStateOf(false) }
    LaunchedEffect(spec.target) { play = true }
    val anim by animateFloatAsState(
        targetValue = if (play) spec.target.toFloat() else 0f,
        animationSpec = tween(800), label = spec.label
    )
    val text = if (spec.money) formatMoneyCompact(anim.toDouble()) else anim.roundToInt().toString()

    Card(modifier) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(spec.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(spec.icon, null, Modifier.size(20.dp), tint = spec.accent) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(spec.label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/** Cumulative running total across the value-over-time entries. */
private fun buildValuePoints(vot: HBValueOverTime?): List<ChartPoint> {
    if (vot == null) return emptyList()
    val sorted = vot.entries
        .mapNotNull { e -> parseHbDate(e.date)?.let { d -> d to e } }
        .sortedBy { it.first }
    if (sorted.isEmpty()) return emptyList()
    var acc = vot.valueAtStart
    return sorted.map { (date, e) ->
        acc += e.value
        ChartPoint(date.short(), acc.toFloat())
    }
}

/** Top tags by value; remainder folded into "Other". */
private fun buildDonutData(byTag: List<com.homeboy.app.api.HBTotalsByOrganizer>): List<DonutDatum> {
    val sorted = byTag.sortedByDescending { it.total }
    val top = sorted.take(7)
    val rest = sorted.drop(7)
    val colors = chartPalette(top.size + if (rest.isNotEmpty()) 1 else 0)
    val out = top.mapIndexed { i, t -> DonutDatum(t.name.ifBlank { "Unnamed" }, t.total.toFloat(), colors[i]) }
        .toMutableList()
    if (rest.isNotEmpty()) {
        out.add(DonutDatum("Other", rest.sumOf { it.total }.toFloat(), colors.last()))
    }
    return out
}

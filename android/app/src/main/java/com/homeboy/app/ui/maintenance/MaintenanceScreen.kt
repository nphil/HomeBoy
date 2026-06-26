package com.homeboy.app.ui.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBMaintenanceWithDetails
import com.homeboy.app.ui.formatMoney
import com.homeboy.app.ui.items.MaintenanceSheet
import com.homeboy.app.ui.parseHbDate
import com.homeboy.app.ui.relativeWhen
import java.time.LocalDate

private enum class MaintFilter(val label: String) { ALL("All"), UPCOMING("Upcoming"), DONE("Done") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceTab() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: MaintenanceViewModel = viewModel(factory = MaintenanceViewModel.factory(app))

    val entries by vm.entries.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf(MaintFilter.ALL) }
    var editing by remember { mutableStateOf<HBMaintenanceWithDetails?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); vm.clearSnackbar() }
    }

    editing?.let { entry ->
        val itemId = entry.itemId
        if (itemId != null) {
            MaintenanceSheet(
                itemId = itemId,
                existing = entry.toEntry(),
                onDismiss = { editing = null },
                onSaved = { editing = null; vm.load() }
            )
        }
    }

    val today = LocalDate.now()
    // Partition once per data change.
    val overdue = remember(entries) {
        entries.filter { !it.isCompleted }
            .mapNotNull { e -> parseHbDate(e.scheduledDate)?.let { d -> e to d } }
            .filter { it.second.isBefore(today) }
            .sortedBy { it.second }.map { it.first }
    }
    val upcoming = remember(entries) {
        entries.filter { !it.isCompleted }
            .filter { e -> parseHbDate(e.scheduledDate)?.isBefore(today) != true }
            .sortedBy { parseHbDate(it.scheduledDate) ?: LocalDate.MAX }
    }
    val completed = remember(entries) {
        entries.filter { it.isCompleted }
            .sortedByDescending { parseHbDate(it.date) ?: LocalDate.MIN }
    }
    val dueSoonCount = remember(upcoming) {
        upcoming.count { e -> parseHbDate(e.scheduledDate)?.let { !it.isAfter(today.plusDays(30)) } == true }
    }
    val totalSpent = remember(completed) { completed.sumOf { it.costDouble } }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Maintenance", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Refresh") }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            loading && entries.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            entries.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Handyman, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("No maintenance tasks", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Add maintenance from an item's detail screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Summary metrics
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(
                                Modifier.weight(1f), overdue.size.toString(), "Overdue",
                                Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error
                            )
                            MetricCard(
                                Modifier.weight(1f), dueSoonCount.toString(), "Due soon",
                                Icons.Default.Schedule, MaterialTheme.colorScheme.tertiary
                            )
                            MetricCard(
                                Modifier.weight(1f), completed.size.toString(), "Done",
                                Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Filter
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            MaintFilter.entries.forEachIndexed { i, f ->
                                SegmentedButton(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    shape = SegmentedButtonDefaults.itemShape(i, MaintFilter.entries.size)
                                ) { Text(f.label) }
                            }
                        }
                    }

                    if (filter == MaintFilter.ALL || filter == MaintFilter.UPCOMING) {
                        if (overdue.isNotEmpty()) {
                            item { SectionHeader("Overdue", overdue.size, MaterialTheme.colorScheme.error) }
                            items(overdue, key = { it.id }) { entry ->
                                MaintenanceRow(entry, onMarkDone = { vm.markComplete(entry) },
                                    onEdit = { editing = entry }, onDelete = { vm.deleteEntry(entry.id) })
                            }
                        }
                        if (upcoming.isNotEmpty()) {
                            item { SectionHeader("Upcoming", upcoming.size, MaterialTheme.colorScheme.tertiary) }
                            items(upcoming, key = { it.id }) { entry ->
                                MaintenanceRow(entry, onMarkDone = { vm.markComplete(entry) },
                                    onEdit = { editing = entry }, onDelete = { vm.deleteEntry(entry.id) })
                            }
                        }
                    }

                    if (filter == MaintFilter.ALL || filter == MaintFilter.DONE) {
                        if (completed.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SectionHeader("Completed", completed.size, MaterialTheme.colorScheme.primary,
                                        Modifier.weight(1f))
                                    if (totalSpent > 0) {
                                        Text("${formatMoney(totalSpent)} spent",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            items(completed, key = { it.id }) { entry ->
                                MaintenanceRow(entry, onMarkDone = null,
                                    onEdit = { editing = entry }, onDelete = { vm.deleteEntry(entry.id) })
                            }
                        }
                    }

                    if (filter == MaintFilter.UPCOMING && overdue.isEmpty() && upcoming.isEmpty()) {
                        item { EmptyHint("Nothing scheduled — you're all caught up.") }
                    }
                    if (filter == MaintFilter.DONE && completed.isEmpty()) {
                        item { EmptyHint("No completed tasks yet.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = accent)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("$count", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun MaintenanceRow(
    entry: HBMaintenanceWithDetails,
    onMarkDone: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var confirmDone by remember { mutableStateOf(false) }

    val scheduled = parseHbDate(entry.scheduledDate)
    val completedDate = parseHbDate(entry.date)
    val isOverdue = onMarkDone != null && scheduled != null && scheduled.isBefore(LocalDate.now())
    val accent = when {
        entry.isCompleted -> MaterialTheme.colorScheme.primary
        isOverdue -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete entry?") },
            text = { Text("\"${entry.name}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
    if (confirmDone && onMarkDone != null) {
        AlertDialog(
            onDismissRequest = { confirmDone = false },
            icon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Mark complete?") },
            text = { Text("\"${entry.name}\" will be logged as done today.") },
            confirmButton = { TextButton(onClick = { confirmDone = false; onMarkDone() }) { Text("Mark done") } },
            dismissButton = { TextButton(onClick = { confirmDone = false }) { Text("Cancel") } }
        )
    }

    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status rail
            Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1)
                entry.itemName?.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Outlined.Inventory2, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                val whenText = when {
                    entry.isCompleted && completedDate != null -> relativeWhen(completedDate, true)
                    scheduled != null -> relativeWhen(scheduled, false)
                    else -> "No date set"
                }
                Text(whenText, style = MaterialTheme.typography.labelMedium, color = accent,
                    fontWeight = FontWeight.Medium)
            }
            if (entry.costDouble > 0) {
                Text(formatMoney(entry.costDouble), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
            }
            if (onMarkDone != null) {
                IconButton(onClick = { confirmDone = true }) {
                    Icon(Icons.Outlined.CheckCircle, "Mark done", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDeleteDialog = true })
                }
            }
        }
    }
}

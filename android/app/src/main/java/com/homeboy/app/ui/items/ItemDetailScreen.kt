package com.homeboy.app.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBItem
import com.homeboy.app.api.HBMaintenanceEntry
import com.homeboy.app.data.SessionHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    onItemUpdated: () -> Unit,
    onAddSubItem: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: ItemDetailViewModel = viewModel(
        key = "detail_$itemId",
        factory = ItemDetailViewModel.factory(app)
    )

    LaunchedEffect(itemId) { vm.load(itemId) }

    val item by vm.item.collectAsStateWithLifecycle()
    val children by vm.children.collectAsStateWithLifecycle()
    val maintenance by vm.maintenance.collectAsStateWithLifecycle()
    val locationIds by vm.locationIds.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showAddMaintenance by remember { mutableStateOf(false) }
    var editingMaintenance by remember { mutableStateOf<HBMaintenanceEntry?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    // String URL for server photos, java.io.File for offline-pending ones.
    var fullscreenPhotoUrl by remember { mutableStateOf<Any?>(null) }

    fullscreenPhotoUrl?.let { url ->
        FullScreenPhotoViewer(model = url, onDismiss = { fullscreenPhotoUrl = null })
    }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); vm.clearSnackbar() }
    }

    if (showDeleteDialog && item != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete item?") },
            text = { Text("\"${item!!.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteItem { onBack(); onItemUpdated() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showEditSheet && item != null) {
        ModalBottomSheet(onDismissRequest = { showEditSheet = false }) {
            AddEditItemScreen(
                itemId = item!!.id,
                parentId = null, parentName = null,
                onBack = { showEditSheet = false },
                onSaved = { showEditSheet = false; vm.reload(); onItemUpdated() }
            )
        }
    }

    if (showAddMaintenance || editingMaintenance != null) {
        MaintenanceSheet(
            itemId = itemId,
            existing = editingMaintenance,
            onDismiss = { showAddMaintenance = false; editingMaintenance = null },
            onSaved = { showAddMaintenance = false; editingMaintenance = null; vm.reload() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name ?: "Item", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menuExpanded = false; showEditSheet = true }
                            )
                            DropdownMenuItem(
                                text = { Text(if (item?.archived == true) "Unarchive" else "Archive") },
                                leadingIcon = { Icon(if (item?.archived == true) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                                onClick = { menuExpanded = false; vm.toggleArchive(); onItemUpdated() }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (loading && item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val detail = item ?: return@Scaffold

        // Readable column on tablets: cap content width and center it
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Photos
            val photos = detail.attachments.orEmpty().filter {
                (it.type ?: "photo").equals("photo", ignoreCase = true)
            }
            if (photos.isNotEmpty() && SessionHolder.apiBase.isNotBlank()) {
                item {
                    // photoModel resolves "pending-" ids (offline-queued photos) to their local file
                    val heroUrl = app.repository.photoModel(detail.id, photos.first().id)
                    Box(
                        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { fullscreenPhotoUrl = heroUrl }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(heroUrl).crossfade(true).build(),
                            contentDescription = photos.first().fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                .padding(4.dp)
                        ) {
                            Icon(Icons.Default.ZoomIn, "View full screen",
                                Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                    if (photos.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(photos.drop(1), key = { it.id }) { att ->
                                val url = app.repository.photoModel(detail.id, att.id)
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(url).crossfade(true).build(),
                                    contentDescription = att.fileName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { fullscreenPhotoUrl = url }
                                )
                            }
                        }
                    }
                }
            }

            // Header card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(detail.name, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (detail.quantity > 1) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape
                                ) {
                                    Text("×${detail.quantity}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (detail.archived) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Archive, null, Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Archived", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // `parent` may be a location (placement) or another item (sub-item parent).
                        val parentIsLocation = detail.parent != null && locationIds.contains(detail.parent.id)
                        if (detail.parent != null && !parentIsLocation) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.AccountTree, null, Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text("Part of: ${detail.parent.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        val placement = detail.effectiveLocation
                        if (placement != null && (detail.location != null || parentIsLocation)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Place, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(placement.name, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (detail.effectiveLabels.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail.effectiveLabels.forEach { label ->
                                    TagChipSmall(label.name, label.color)
                                }
                            }
                        }

                        if (!detail.description.isNullOrBlank()) {
                            Text(detail.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Details card
            if (!detail.serialNumber.isNullOrBlank() || !detail.modelNumber.isNullOrBlank() ||
                !detail.manufacturer.isNullOrBlank() || !detail.notes.isNullOrBlank()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Details", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            detail.manufacturer?.takeIf { it.isNotBlank() }?.let {
                                DetailRow("Manufacturer", it)
                            }
                            detail.modelNumber?.takeIf { it.isNotBlank() }?.let {
                                DetailRow("Model", it)
                            }
                            detail.serialNumber?.takeIf { it.isNotBlank() }?.let {
                                DetailRow("Serial", it)
                            }
                            detail.notes?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("Notes", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // Purchase info
            if ((detail.purchasePrice ?: 0.0) > 0 || !detail.purchaseFrom.isNullOrBlank()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Purchase", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            detail.purchaseFrom?.takeIf { it.isNotBlank() }?.let { DetailRow("From", it) }
                            detail.purchasePrice?.takeIf { it > 0 }?.let { DetailRow("Price", "$${"%.2f".format(it)}") }
                            detail.purchaseDate?.takeIf { it.isNotBlank() }?.let { DetailRow("Date", it.take(10)) }
                        }
                    }
                }
            }

            // Custom fields
            val customFields = detail.fields.orEmpty().filter { it.name.isNotBlank() }
            if (customFields.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Custom Fields", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            customFields.forEach { f -> DetailRow(f.name, f.displayValue) }
                        }
                    }
                }
            }

            // Sub-items / components
            if (children.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Components", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onAddSubItem(detail.id, detail.name) }) {
                                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add")
                                }
                            }
                            children.forEach { child ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.SubdirectoryArrowRight, null,
                                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    Text(child.name, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f))
                                    if (child.quantity > 1) {
                                        Text("×${child.quantity}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Maintenance
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Maintenance", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editingMaintenance = null; showAddMaintenance = true }) {
                                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        if (maintenance.isEmpty()) {
                            Text("No maintenance records", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            maintenance.sortedByDescending { it.date ?: it.scheduledDate ?: "" }
                                .forEach { entry ->
                                    MaintenanceEntryRow(
                                        entry = entry,
                                        onTap = { editingMaintenance = entry },
                                        onDelete = { vm.deleteMaintenance(entry.id) }
                                    )
                                }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaintenanceEntryRow(
    entry: HBMaintenanceEntry,
    onTap: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

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

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                if (entry.isCompleted) Color(0xFF22C55E) else Color(0xFFF59E0B)
            )
        )
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val dateStr = when {
                entry.isCompleted -> "Done: ${entry.date?.take(10)}"
                entry.isScheduled -> "Scheduled: ${entry.scheduledDate?.take(10)}"
                else -> "No date"
            }
            Text(dateStr, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.costDouble > 0) {
            Text("$${"%.2f".format(entry.costDouble)}", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium)
        }
        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceSheet(
    itemId: String,
    existing: HBMaintenanceEntry?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val repo = app.repository

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var costText by remember { mutableStateOf(if ((existing?.costDouble ?: 0.0) > 0) "%.2f".format(existing!!.costDouble) else "") }
    var hasDate by remember { mutableStateOf(!existing?.date.isNullOrBlank()) }
    var dateText by remember { mutableStateOf(existing?.date?.take(10) ?: "") }
    var hasScheduled by remember { mutableStateOf(!existing?.scheduledDate.isNullOrBlank()) }
    var scheduledText by remember { mutableStateOf(existing?.scheduledDate?.take(10) ?: "") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing != null) "Edit Maintenance" else "Add Maintenance",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            OutlinedTextField(value = description, onValueChange = { description = it },
                label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)

            OutlinedTextField(value = costText, onValueChange = { costText = it },
                label = { Text("Cost") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                prefix = { Text("$") })

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hasDate, onCheckedChange = { hasDate = it; if (!it) dateText = "" })
                Spacer(Modifier.width(8.dp))
                Text("Date completed")
            }
            if (hasDate) {
                var showCompletedPicker by remember { mutableStateOf(false) }
                val completedDpState = rememberDatePickerState(
                    initialSelectedDateMillis = parseDateToMillis(dateText)
                )
                if (showCompletedPicker) {
                    DatePickerDialog(
                        onDismissRequest = { showCompletedPicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                completedDpState.selectedDateMillis?.let { millis ->
                                    dateText = millisToDateString(millis)
                                }
                                showCompletedPicker = false
                            }) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCompletedPicker = false }) { Text("Cancel") }
                        }
                    ) { DatePicker(state = completedDpState) }
                }
                OutlinedButton(
                    onClick = { showCompletedPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(dateText.ifBlank { "Select completion date" })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hasScheduled, onCheckedChange = { hasScheduled = it; if (!it) scheduledText = "" })
                Spacer(Modifier.width(8.dp))
                Text("Scheduled date")
            }
            if (hasScheduled) {
                var showScheduledPicker by remember { mutableStateOf(false) }
                val scheduledDpState = rememberDatePickerState(
                    initialSelectedDateMillis = parseDateToMillis(scheduledText)
                )
                if (showScheduledPicker) {
                    DatePickerDialog(
                        onDismissRequest = { showScheduledPicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                scheduledDpState.selectedDateMillis?.let { millis ->
                                    scheduledText = millisToDateString(millis)
                                }
                                showScheduledPicker = false
                            }) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showScheduledPicker = false }) { Text("Cancel") }
                        }
                    ) { DatePicker(state = scheduledDpState) }
                }
                OutlinedButton(
                    onClick = { showScheduledPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(scheduledText.ifBlank { "Select scheduled date" })
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = name.isNotBlank() && !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                val entry = com.homeboy.app.api.HBMaintenanceCreate(
                                    name = name.trim(),
                                    description = description.trim(),
                                    date = if (hasDate) dateText.trim() else "",
                                    scheduledDate = if (hasScheduled) scheduledText.trim() else "",
                                    cost = costText.toDoubleOrNull()?.let { "%.2f".format(it) } ?: "0"
                                )
                                if (existing != null) {
                                    repo.updateMaintenance(existing.id, entry)
                                } else {
                                    repo.createMaintenance(itemId, entry)
                                }
                                onSaved()
                            } catch (e: Exception) {
                                error = e.message
                                loading = false
                            }
                        }
                    }
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Save")
                }
            }
        }
    }
}

private fun parseDateToMillis(dateStr: String): Long? = try {
    if (dateStr.isBlank()) null
    else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.time
} catch (_: Exception) { null }

private fun millisToDateString(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

@Composable
private fun FullScreenPhotoViewer(model: Any, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                scale = (scale * zoomChange).coerceIn(0.5f, 6f)
                offsetX += panChange.x
                offsetY += panChange.y
            }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = transformState)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

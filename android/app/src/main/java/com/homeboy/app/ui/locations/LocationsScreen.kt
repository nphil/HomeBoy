package com.homeboy.app.ui.locations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBLocationTreeItem

// Spring animations: expand with bounce, collapse crisp
private val expandEnter =
    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
    expandVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
private val collapseExit =
    fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
    shrinkVertically(spring(stiffness = Spring.StiffnessMedium))

// Flat node used by the grid view
private data class FlatNode(val node: HBLocationTreeItem, val depth: Int)

private fun buildFlatNodes(
    nodes: List<HBLocationTreeItem>,
    expanded: Set<String>,
    depth: Int = 0
): List<FlatNode> = buildList {
    nodes.forEach { node ->
        add(FlatNode(node, depth))
        if (node.id in expanded && node.children.isNotEmpty()) {
            addAll(buildFlatNodes(node.children, expanded, depth + 1))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsTab() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: LocationsViewModel = viewModel(factory = LocationsViewModel.factory(app))

    val tree by vm.tree.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    var showAddSheet by remember { mutableStateOf(false) }
    var editingLocation by remember { mutableStateOf<HBLocationTreeItem?>(null) }
    var addParentId by remember { mutableStateOf<String?>(null) }
    var orgChartMode by remember { mutableStateOf(false) }
    // Start fully collapsed
    var expandedNodeIds by remember { mutableStateOf(setOf<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); vm.clearSnackbar() }
    }

    val onToggle: (String) -> Unit = { id ->
        expandedNodeIds = if (id in expandedNodeIds) expandedNodeIds - id else expandedNodeIds + id
    }

    if (showAddSheet || editingLocation != null) {
        LocationSheet(
            existing = editingLocation,
            parentId = addParentId,
            onDismiss = { showAddSheet = false; editingLocation = null; addParentId = null },
            onSave = { name, desc ->
                if (editingLocation != null) vm.updateLocation(editingLocation!!.id, name, desc)
                else vm.createLocation(name, desc, addParentId)
                showAddSheet = false; editingLocation = null; addParentId = null
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Locations", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { orgChartMode = !orgChartMode }) {
                        Icon(
                            if (orgChartMode) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = if (orgChartMode) "Tree list view" else "Grid view"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addParentId = null; showAddSheet = true }) {
                Icon(Icons.Default.Add, "Add location")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            loading && tree.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            tree.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Place, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("No locations", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            orgChartMode -> {
                // Compact grid: flat list of nodes, children inserted after parent when expanded.
                // Each card is ~160dp wide so 4-5 fit per row on a tablet.
                val flatNodes = remember(tree, expandedNodeIds) { buildFlatNodes(tree, expandedNodeIds) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 158.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = 92.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    items(flatNodes, key = { it.node.id }) { flat ->
                        GridLocationCard(
                            flat = flat,
                            isExpanded = flat.node.id in expandedNodeIds,
                            onToggle = { onToggle(flat.node.id) },
                            onEdit = { editingLocation = flat.node },
                            onDelete = { vm.deleteLocation(flat.node.id) },
                            onAddChild = { addParentId = flat.node.id; showAddSheet = true }
                        )
                    }
                }
            }
            else -> {
                // Tree list: full-width collapsible cards with vertical connecting lines
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .padding(bottom = 80.dp)
                ) {
                    tree.forEach { node ->
                        TreeListNode(
                            node = node,
                            depth = 0,
                            expandedNodeIds = expandedNodeIds,
                            onToggle = onToggle,
                            onEdit = { editingLocation = it },
                            onDelete = { vm.deleteLocation(it.id) },
                            onAddChild = { addParentId = it.id; showAddSheet = true }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Grid view: compact card, depth indicated by left accent bar
// ---------------------------------------------------------------------------

@Composable
private fun GridLocationCard(
    flat: FlatNode,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit
) {
    val node = flat.node
    val hasChildren = node.children.isNotEmpty()
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.primary

    if (showDeleteDialog) {
        DeleteLocationDialog(node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (hasChildren) onToggle else ({})
    ) {
        Row(Modifier.fillMaxWidth()) {
            // Left accent bar shows hierarchy depth (none for root, colored for children)
            if (flat.depth > 0) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(
                            accentColor.copy(alpha = (0.4f + flat.depth * 0.2f).coerceAtMost(0.9f)),
                            RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                        )
                )
            }
            Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place, null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    if (hasChildren) {
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, null, Modifier.size(14.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Add sub-location") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menuExpanded = false; onAddChild() }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menuExpanded = false; onEdit() }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                val meta = buildList {
                    if (node.itemCount > 0) add("${node.itemCount} item${if (node.itemCount != 1) "s" else ""}")
                    if (hasChildren) add("${node.children.size} sub")
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tree list: full-width cards with vertical connecting lines
// ---------------------------------------------------------------------------

@Composable
private fun TreeListNode(
    node: HBLocationTreeItem,
    depth: Int,
    expandedNodeIds: Set<String>,
    onToggle: (String) -> Unit,
    onEdit: (HBLocationTreeItem) -> Unit,
    onDelete: (HBLocationTreeItem) -> Unit,
    onAddChild: (HBLocationTreeItem) -> Unit
) {
    val isExpanded = node.id in expandedNodeIds
    val hasChildren = node.children.isNotEmpty()
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val connectorIndent = if (depth > 0) (depth - 1) * 32 + 12 else 0

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (depth > 0) connectorIndent.dp else 0.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (depth > 0) {
                Canvas(Modifier.size(width = 20.dp, height = 40.dp)) {
                    drawLine(lineColor, Offset(0f, 20.dp.toPx()), Offset(size.width, 20.dp.toPx()), 2.dp.toPx())
                }
            }
            TreeListCard(
                node = node,
                isExpanded = isExpanded,
                hasChildren = hasChildren,
                modifier = Modifier.weight(1f),
                onToggle = { onToggle(node.id) },
                onEdit = { onEdit(node) },
                onDelete = { onDelete(node) },
                onAddChild = { onAddChild(node) }
            )
        }

        AnimatedVisibility(
            visible = isExpanded && hasChildren,
            enter = expandEnter,
            exit = collapseExit
        ) {
            val vertLineX = (connectorIndent + 12).dp
            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.fillMaxWidth().matchParentSize()) {
                    drawLine(
                        color = lineColor,
                        start = Offset(vertLineX.toPx(), 0f),
                        end = Offset(vertLineX.toPx(), size.height - 20.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    node.children.forEach { child ->
                        TreeListNode(
                            node = child, depth = depth + 1,
                            expandedNodeIds = expandedNodeIds,
                            onToggle = onToggle, onEdit = onEdit,
                            onDelete = onDelete, onAddChild = onAddChild
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeListCard(
    node: HBLocationTreeItem,
    isExpanded: Boolean,
    hasChildren: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        DeleteLocationDialog(node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    ElevatedCard(modifier = modifier, onClick = if (hasChildren) onToggle else ({})) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Place, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (!node.description.isNullOrBlank()) {
                    Text(node.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val meta = buildList {
                    if (node.itemCount > 0) add("${node.itemCount} item${if (node.itemCount != 1) "s" else ""}")
                    if (hasChildren) add("${node.children.size} location${if (node.children.size != 1) "s" else ""}")
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (hasChildren) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Add sub-location") },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { menuExpanded = false; onAddChild() }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDeleteDialog = true }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

@Composable
private fun DeleteLocationDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete location?") },
        text = { Text("\"$name\" will be permanently deleted.") },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSheet(
    existing: HBLocationTreeItem?,
    parentId: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (existing != null) "Edit Location" else if (parentId != null) "Add Sub-Location" else "Add Location",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = description, onValueChange = { description = it },
                label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) {
                    Text("Save")
                }
            }
        }
    }
}

package com.homeboy.app.ui.locations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBLocationTreeItem

// Fixed card geometry for the top-down org chart. Cells are slightly wider than
// the cards so centering each card in its cell yields uniform gutters, while the
// connector lines (which fill the full cell width) still join edge-to-edge.
private val ORG_CARD_WIDTH = 150.dp
private val ORG_CELL_WIDTH = 162.dp
private val ORG_STUB_HEIGHT = 14.dp
private val ORG_CONNECTOR_HEIGHT = 14.dp

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
    var expandedNodeIds by remember { mutableStateOf(setOf<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Expand every node by default the first time a tree arrives.
    var didSeedExpansion by remember { mutableStateOf(false) }
    LaunchedEffect(tree) {
        if (!didSeedExpansion && tree.isNotEmpty()) {
            expandedNodeIds = collectAllIds(tree)
            didSeedExpansion = true
        }
    }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); vm.clearSnackbar() }
    }

    if (showAddSheet || editingLocation != null) {
        LocationSheet(
            existing = editingLocation,
            parentId = addParentId,
            onDismiss = { showAddSheet = false; editingLocation = null; addParentId = null },
            onSave = { name, desc ->
                if (editingLocation != null) {
                    vm.updateLocation(editingLocation!!.id, name, desc)
                } else {
                    vm.createLocation(name, desc, addParentId)
                }
                showAddSheet = false; editingLocation = null; addParentId = null
            }
        )
    }

    val onToggleExpand: (String) -> Unit = { id ->
        expandedNodeIds = if (id in expandedNodeIds) expandedNodeIds - id else expandedNodeIds + id
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
                            if (orgChartMode) Icons.Default.ViewList else Icons.Default.AccountTree,
                            contentDescription = if (orgChartMode) "Tree list view" else "Org chart view"
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
        if (loading && tree.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (tree.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Place, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("No locations", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (orgChartMode) {
            // Top-down org chart: rounded cards joined by connector lines.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                tree.forEach { root ->
                    OrgTreeNode(
                        node = root,
                        expandedNodeIds = expandedNodeIds,
                        onToggleExpand = onToggleExpand,
                        onEdit = { editingLocation = it },
                        onDelete = { vm.deleteLocation(it.id) },
                        onAddChild = { addParentId = it.id; showAddSheet = true }
                    )
                }
            }
        } else {
            // Tree list view: left-aligned cards with vertical connecting lines.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 80.dp)
            ) {
                tree.forEach { node ->
                    OrgChartNode(
                        node = node,
                        depth = 0,
                        expandedNodeIds = expandedNodeIds,
                        onToggleExpand = onToggleExpand,
                        onEdit = { editingLocation = it },
                        onDelete = { vm.deleteLocation(it.id) },
                        onAddChild = { addParentId = it.id; showAddSheet = true }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

private fun collectAllIds(nodes: List<HBLocationTreeItem>): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(n: HBLocationTreeItem) {
        out += n.id
        n.children.forEach { walk(it) }
    }
    nodes.forEach { walk(it) }
    return out
}

/** Width a subtree occupies in the top-down chart, accounting for collapsed nodes. */
private fun subtreeWidth(node: HBLocationTreeItem, expanded: Set<String>): Dp {
    val isExp = node.id in expanded
    if (!isExp || node.children.isEmpty()) return ORG_CELL_WIDTH
    var total = 0.dp
    node.children.forEach { total += subtreeWidth(it, expanded) }
    return total
}

// ---------------------------------------------------------------------------
// Top-down org chart
// ---------------------------------------------------------------------------

@Composable
private fun OrgTreeNode(
    node: HBLocationTreeItem,
    expandedNodeIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    onEdit: (HBLocationTreeItem) -> Unit,
    onDelete: (HBLocationTreeItem) -> Unit,
    onAddChild: (HBLocationTreeItem) -> Unit
) {
    val isExpanded = node.id in expandedNodeIds
    val hasChildren = node.children.isNotEmpty()
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OrgNodeCard(
            node = node,
            isExpanded = isExpanded,
            hasChildren = hasChildren,
            onToggleExpand = { onToggleExpand(node.id) },
            onEdit = { onEdit(node) },
            onDelete = { onDelete(node) },
            onAddChild = { onAddChild(node) }
        )

        if (isExpanded && hasChildren) {
            // Vertical stub dropping from the card to the connector bus.
            Canvas(Modifier.width(2.dp).height(ORG_STUB_HEIGHT)) {
                drawLine(
                    color = lineColor,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                val last = node.children.lastIndex
                node.children.forEachIndexed { i, child ->
                    val cellWidth = subtreeWidth(child, expandedNodeIds)
                    Column(
                        modifier = Modifier.width(cellWidth),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Per-child connector: horizontal bus segment + vertical drop.
                        Canvas(Modifier.fillMaxWidth().height(ORG_CONNECTOR_HEIGHT)) {
                            val cx = size.width / 2f
                            val sw = 2.dp.toPx()
                            drawLine(lineColor, Offset(cx, 0f), Offset(cx, size.height), sw)
                            if (node.children.size > 1) {
                                val startX = if (i == 0) cx else 0f
                                val endX = if (i == last) cx else size.width
                                drawLine(lineColor, Offset(startX, 0f), Offset(endX, 0f), sw)
                            }
                        }
                        OrgTreeNode(
                            node = child,
                            expandedNodeIds = expandedNodeIds,
                            onToggleExpand = onToggleExpand,
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onAddChild = onAddChild
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrgNodeCard(
    node: HBLocationTreeItem,
    isExpanded: Boolean,
    hasChildren: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        DeleteLocationDialog(name = node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    Card(
        modifier = Modifier.width(ORG_CARD_WIDTH),
        onClick = if (hasChildren) onToggleExpand else ({})
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (hasChildren) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(24.dp)) {
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
            Text(node.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            val subtitle = buildList {
                if (node.itemCount > 0) add("${node.itemCount} item${if (node.itemCount != 1) "s" else ""}")
                if (hasChildren) add("${node.children.size} sub")
            }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tree list view (left-aligned, vertical connecting lines, collapsible)
// ---------------------------------------------------------------------------

@Composable
private fun OrgChartNode(
    node: HBLocationTreeItem,
    depth: Int,
    expandedNodeIds: Set<String>,
    onToggleExpand: (String) -> Unit,
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
                    val y = 20.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            OrgChartCard(
                node = node,
                isExpanded = isExpanded,
                hasChildren = hasChildren,
                onToggleExpand = { onToggleExpand(node.id) },
                onEdit = { onEdit(node) },
                onDelete = { onDelete(node) },
                onAddChild = { onAddChild(node) },
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(
            visible = isExpanded && hasChildren,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val vertLineX = (connectorIndent + 12).dp

            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.fillMaxWidth().matchParentSize()) {
                    val x = vertLineX.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height - 20.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    node.children.forEach { child ->
                        OrgChartNode(
                            node = child,
                            depth = depth + 1,
                            expandedNodeIds = expandedNodeIds,
                            onToggleExpand = onToggleExpand,
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onAddChild = onAddChild
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OrgChartCard(
    node: HBLocationTreeItem,
    isExpanded: Boolean,
    hasChildren: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        DeleteLocationDialog(name = node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    ElevatedCard(
        modifier = modifier,
        onClick = if (hasChildren) onToggleExpand else ({})
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (!node.description.isNullOrBlank()) {
                    Text(node.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (node.itemCount > 0 || hasChildren) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (node.itemCount > 0) {
                            Text("${node.itemCount} item${if (node.itemCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (hasChildren) {
                            Text("${node.children.size} location${if (node.children.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (hasChildren) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text(if (existing != null) "Edit Location" else if (parentId != null) "Add Sub-Location" else "Add Location",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

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

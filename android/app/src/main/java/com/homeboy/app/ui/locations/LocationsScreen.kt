package com.homeboy.app.ui.locations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBLocationTreeItem

// Spring animations: expand smoothly without bounce, collapse crisp
private val expandEnter =
    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
    expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val orgChartMode = viewMode == "grid"
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

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Locations", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { vm.toggleViewMode() }) {
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
                val scrollStateY = rememberScrollState()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    val maxWidth = maxWidth
                    val spacing = 16.dp
                    val paddingStart = 24.dp
                    val availableWidth = maxWidth - paddingStart - 24.dp
                    val cols = ((availableWidth + spacing) / (140.dp + spacing)).toInt().coerceAtLeast(1)

                    val initialGridItems = remember(tree) {
                        tree.map { GridCardItem(it, isChild = false, parentId = null) }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollStateY)
                            .padding(bottom = 92.dp)
                            .animateContentSize()
                    ) {
                        RenderFlatList(
                            itemsList = initialGridItems,
                            expandedNodeIds = expandedNodeIds,
                            cols = cols,
                            paddingStart = paddingStart,
                            cardWidth = 140.dp,
                            spacing = spacing,
                            availableWidth = availableWidth,
                            onToggle = onToggle,
                            onEdit = { editingLocation = it },
                            onDelete = { vm.deleteLocation(it) },
                            onAddChild = { addParentId = it; showAddSheet = true }
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
private fun ConnectorLines(
    parentCenterX: Dp,
    childrenCenterXs: List<Dp>,
    lineColor: Color
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        val parentX = parentCenterX.toPx()
        val midY = 12.dp.toPx()

        // 1. Vertical line from parent bottom down to middle
        drawLine(
            color = lineColor,
            start = Offset(parentX, 0f),
            end = Offset(parentX, midY),
            strokeWidth = 2.dp.toPx()
        )

        if (childrenCenterXs.isNotEmpty()) {
            val firstChildX = childrenCenterXs.first().toPx()
            val lastChildX = childrenCenterXs.last().toPx()

            // 2. Horizontal connection bar spanning all children
            val minX = minOf(parentX, firstChildX)
            val maxX = maxOf(parentX, lastChildX)
            drawLine(
                color = lineColor,
                start = Offset(minX, midY),
                end = Offset(maxX, midY),
                strokeWidth = 2.dp.toPx()
            )

            // 3. Vertical line down to each child
            childrenCenterXs.forEach { childCenterX ->
                val childX = childCenterX.toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(childX, midY),
                    end = Offset(childX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

private data class GridCardItem(
    val node: HBLocationTreeItem,
    val isChild: Boolean,
    val parentId: String?,
    val isShrunkBlock: Boolean = false,
    val shrunkBlockSize: Int = 0
)

@Composable
private fun RenderFlatList(
    itemsList: List<GridCardItem>,
    expandedNodeIds: Set<String>,
    cols: Int,
    paddingStart: Dp,
    cardWidth: Dp,
    spacing: Dp,
    availableWidth: Dp,
    onToggle: (String) -> Unit,
    onEdit: (HBLocationTreeItem) -> Unit,
    onDelete: (String) -> Unit,
    onAddChild: (String) -> Unit
) {
    if (itemsList.isEmpty()) return

    // 1. Determine the next row content and card size
    val firstItem = itemsList.first()
    val (rowItems, remainingItems, currentRowCardWidth) = if (firstItem.isShrunkBlock) {
        val count = firstItem.shrunkBlockSize
        val shrunkSize = minOf(
            cardWidth,
            (availableWidth - spacing * (count - 1)) / count
        )
        Triple(itemsList.take(count), itemsList.drop(count), shrunkSize)
    } else {
        Triple(itemsList.take(cols), itemsList.drop(cols), cardWidth)
    }

    // 2. Render the row of cards
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = paddingStart, end = 24.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        rowItems.forEach { item ->
            GridLocationCard(
                node = item.node,
                isExpanded = item.node.id in expandedNodeIds,
                cardSize = currentRowCardWidth,
                onToggle = { onToggle(item.node.id) },
                onEdit = { onEdit(item.node) },
                onDelete = { onDelete(item.node.id) },
                onAddChild = { onAddChild(item.node.id) }
            )
        }
    }

    // 3. Find if any item in the current row is expanded
    val expandedItems = rowItems.filter { it.node.id in expandedNodeIds && it.node.children.isNotEmpty() }

    if (expandedItems.isNotEmpty()) {
        val parentItem = expandedItems.first()
        val parentIndex = rowItems.indexOf(parentItem)
        val parentCenterX = paddingStart + (currentRowCardWidth + spacing) * parentIndex + (currentRowCardWidth / 2)

        val children = parentItem.node.children
        val childrenCount = children.size

        // Determine if children should be a shrunk block
        val shouldShrink = childrenCount > cols
        val childCardWidth = if (shouldShrink) {
            minOf(cardWidth, (availableWidth - spacing * (childrenCount - 1)) / childrenCount)
        } else {
            cardWidth
        }

        val childrenCenterXs = children.indices.map { j ->
            paddingStart + (childCardWidth + spacing) * j + (childCardWidth / 2)
        }

        val isExpanded = parentItem.node.id in expandedNodeIds
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandEnter,
            exit = collapseExit
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ConnectorLines(
                    parentCenterX = parentCenterX,
                    childrenCenterXs = childrenCenterXs,
                    lineColor = MaterialTheme.colorScheme.outlineVariant
                )

                val childrenGridItems = children.map { child ->
                    GridCardItem(
                        node = child,
                        isChild = true,
                        parentId = parentItem.node.id,
                        isShrunkBlock = shouldShrink,
                        shrunkBlockSize = childrenCount
                    )
                }

                if (shouldShrink) {
                    RenderFlatList(
                        itemsList = childrenGridItems,
                        expandedNodeIds = expandedNodeIds,
                        cols = cols,
                        paddingStart = paddingStart,
                        cardWidth = cardWidth,
                        spacing = spacing,
                        availableWidth = availableWidth,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onAddChild = onAddChild
                    )
                } else {
                    val mergedItems = childrenGridItems + remainingItems
                    RenderFlatList(
                        itemsList = mergedItems,
                        expandedNodeIds = expandedNodeIds,
                        cols = cols,
                        paddingStart = paddingStart,
                        cardWidth = cardWidth,
                        spacing = spacing,
                        availableWidth = availableWidth,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onAddChild = onAddChild
                    )
                }
            }
        }

        if (shouldShrink) {
            RenderFlatList(
                itemsList = remainingItems,
                expandedNodeIds = expandedNodeIds,
                cols = cols,
                paddingStart = paddingStart,
                cardWidth = cardWidth,
                spacing = spacing,
                availableWidth = availableWidth,
                onToggle = onToggle,
                onEdit = onEdit,
                onDelete = onDelete,
                onAddChild = onAddChild
            )
        }
    } else {
        RenderFlatList(
            itemsList = remainingItems,
            expandedNodeIds = expandedNodeIds,
            cols = cols,
            paddingStart = paddingStart,
            cardWidth = cardWidth,
            spacing = spacing,
            availableWidth = availableWidth,
            onToggle = onToggle,
            onEdit = onEdit,
            onDelete = onDelete,
            onAddChild = onAddChild
        )
    }
}

@Composable
private fun GridLocationCard(
    node: HBLocationTreeItem,
    isExpanded: Boolean,
    cardSize: Dp = 140.dp,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit
) {
    val hasChildren = node.children.isNotEmpty()
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        DeleteLocationDialog(node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    val padding = when {
        cardSize < 100.dp -> 6.dp
        cardSize < 120.dp -> 8.dp
        else -> 12.dp
    }
    val placeIconSize = when {
        cardSize < 100.dp -> 12.dp
        cardSize < 120.dp -> 14.dp
        else -> 18.dp
    }
    val expandIconSize = when {
        cardSize < 100.dp -> 10.dp
        cardSize < 120.dp -> 12.dp
        else -> 16.dp
    }
    val moreButtonSize = when {
        cardSize < 100.dp -> 16.dp
        cardSize < 120.dp -> 20.dp
        else -> 24.dp
    }
    val moreIconSize = when {
        cardSize < 100.dp -> 10.dp
        cardSize < 120.dp -> 12.dp
        else -> 16.dp
    }

    val nameStyle = when {
        cardSize < 90.dp -> MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.9f)
        cardSize < 120.dp -> MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    }

    val metaStyle = when {
        cardSize < 90.dp -> MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75f)
        cardSize < 120.dp -> MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f)
        else -> MaterialTheme.typography.labelSmall
    }

    val contentSpacing = when {
        cardSize < 100.dp -> 1.dp
        cardSize < 120.dp -> 2.dp
        else -> 4.dp
    }

    Card(
        modifier = Modifier.size(cardSize),
        onClick = if (hasChildren) onToggle else ({})
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top row: Place icon and controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        Icons.Default.Place, null,
                        Modifier.size(placeIconSize),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasChildren) {
                            Icon(
                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, Modifier.size(expandIconSize),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }, Modifier.size(moreButtonSize)) {
                                Icon(Icons.Default.MoreVert, null, Modifier.size(moreIconSize))
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
                
                // Bottom content: Name and metadata
                Column(verticalArrangement = Arrangement.spacedBy(contentSpacing)) {
                    Text(
                        node.name,
                        style = nameStyle,
                        maxLines = if (cardSize < 100.dp) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = buildList {
                        if (node.itemCount > 0) add("${node.itemCount} item${if (node.itemCount != 1) "s" else ""}")
                        if (hasChildren) add("${node.children.size} sub")
                    }.joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            meta,
                            style = metaStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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

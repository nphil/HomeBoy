package com.homeboy.app.ui.tags

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBTagTreeItem
import com.homeboy.app.ui.ALL_MATERIAL_ICONS
import com.homeboy.app.ui.TAG_ICONS
import com.homeboy.app.ui.items.parseHexColor
import com.homeboy.app.ui.tagIcon

val TAG_COLORS = listOf(
    "#6366f1", "#7c3aed", "#9333ea", "#c026d3", "#db2777",
    "#e11d48", "#dc2626", "#ea580c", "#d97706", "#65a30d",
    "#16a34a", "#0d9488", "#0891b2", "#2563eb", "#475569"
)

// Spring animations: expand smoothly without bounce, collapse crisp
private val expandEnter =
    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
    expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
private val collapseExit =
    fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
    shrinkVertically(spring(stiffness = Spring.StiffnessMedium))

@Composable
private fun leadingTagIcon(node: HBTagTreeItem) =
    tagIcon(node.icon) ?: Icons.Outlined.Label

@Composable
private fun tagColorOf(node: HBTagTreeItem): Color =
    node.color?.let { parseHexColor(it) } ?: MaterialTheme.colorScheme.primary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsTab() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: TagsViewModel = viewModel(factory = TagsViewModel.factory(app))

    val tree by vm.tree.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    var showAddSheet by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<HBTagTreeItem?>(null) }
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

    if (showAddSheet || editingTag != null) {
        TagSheet(
            existing = editingTag,
            parentId = addParentId,
            onDismiss = { showAddSheet = false; editingTag = null; addParentId = null },
            onSave = { name, desc, color, icon ->
                if (editingTag != null) vm.updateTag(editingTag!!.id, name, desc, color, icon)
                else vm.createTag(name, desc, color, icon, addParentId)
                showAddSheet = false; editingTag = null; addParentId = null
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Tags", fontWeight = FontWeight.SemiBold) },
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
                Icon(Icons.Default.Add, "Add tag")
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
                        Icon(Icons.Outlined.Label, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("No tags", style = MaterialTheme.typography.titleMedium,
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
                            onEdit = { editingTag = it },
                            onDelete = { vm.deleteTag(it) },
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
                            onEdit = { editingTag = it },
                            onDelete = { vm.deleteTag(it.id) },
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
// Grid view: compact card, depth indicated by connector lines
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
    val node: HBTagTreeItem,
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
    onEdit: (HBTagTreeItem) -> Unit,
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
            GridTagCard(
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
private fun GridTagCard(
    node: HBTagTreeItem,
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
        DeleteTagDialog(node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    val padding = when {
        cardSize < 100.dp -> 6.dp
        cardSize < 120.dp -> 8.dp
        else -> 12.dp
    }
    val tagIconSize = when {
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
                // Top row: tag icon and controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        leadingTagIcon(node), null,
                        Modifier.size(tagIconSize),
                        tint = tagColorOf(node)
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
                                    text = { Text("Add sub-tag") },
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
                    if (hasChildren) {
                        Text(
                            "${node.children.size} sub",
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
    node: HBTagTreeItem,
    depth: Int,
    expandedNodeIds: Set<String>,
    onToggle: (String) -> Unit,
    onEdit: (HBTagTreeItem) -> Unit,
    onDelete: (HBTagTreeItem) -> Unit,
    onAddChild: (HBTagTreeItem) -> Unit
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
    node: HBTagTreeItem,
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
        DeleteTagDialog(node.name, onConfirm = onDelete, onDismiss = { showDeleteDialog = false })
    }

    ElevatedCard(modifier = modifier, onClick = if (hasChildren) onToggle else ({})) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(leadingTagIcon(node), null, Modifier.size(20.dp), tint = tagColorOf(node))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (!node.description.isNullOrBlank()) {
                    Text(node.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (hasChildren) {
                    Text("${node.children.size} tag${if (node.children.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
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
                        text = { Text("Add sub-tag") },
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
private fun DeleteTagDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete tag?") },
        text = { Text("\"$name\" will be permanently deleted.") },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagSheet(
    existing: HBTagTreeItem?,
    parentId: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, color: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var selectedColor by remember { mutableStateOf(existing?.color ?: TAG_COLORS[0]) }
    var selectedIcon by remember { mutableStateOf(existing?.icon ?: "") }
    var iconSearch by remember { mutableStateOf("") }

    val previewColor = parseHexColor(selectedColor)
    val localIconKeys = remember { TAG_ICONS.map { it.first }.toSet() }
    val displayedIcons = remember(iconSearch, localIconKeys) {
        if (iconSearch.isBlank()) {
            TAG_ICONS
        } else {
            val q = iconSearch.lowercase().trim()
            ALL_MATERIAL_ICONS
                .filter { q in it.first.lowercase() }
                .sortedWith(compareBy({ !localIconKeys.contains(it.first) }, { it.first }))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (existing != null) "Edit Tag" else if (parentId != null) "Add Sub-Tag" else "Add Tag",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            OutlinedTextField(value = description, onValueChange = { description = it },
                label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)

            // Color
            Text("Color", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TAG_COLORS.chunked(8).forEach { rowColors ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowColors.forEach { hex ->
                        val c = parseHexColor(hex)
                        val isSelected = hex == selectedColor
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 36.dp else 28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { selectedColor = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }

            // Icon
            Text("Icon", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = iconSearch,
                onValueChange = { iconSearch = it },
                label = { Text("Search Material Icons") },
                placeholder = { Text("e.g. home, star, tools…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (iconSearch.isNotEmpty()) {
                        IconButton(onClick = { iconSearch = "" }) {
                            Icon(Icons.Default.Clear, "Clear search")
                        }
                    }
                }
            )

            if (iconSearch.isNotBlank() && displayedIcons.isEmpty()) {
                Text(
                    "No icons found for \"$iconSearch\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconPickerCell(
                    selected = selectedIcon.isBlank(),
                    accent = previewColor,
                    onClick = { selectedIcon = "" }
                ) {
                    Icon(Icons.Default.Block, "No icon", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                displayedIcons.forEach { (key, vector) ->
                    IconPickerCell(
                        selected = selectedIcon == key,
                        accent = previewColor,
                        onClick = { selectedIcon = key }
                    ) {
                        Icon(vector, key, Modifier.size(20.dp),
                            tint = if (selectedIcon == key) previewColor else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            if (selectedIcon.isNotBlank()) {
                Text(
                    "Selected: $selectedIcon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(name, description, selectedColor, selectedIcon) },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun IconPickerCell(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

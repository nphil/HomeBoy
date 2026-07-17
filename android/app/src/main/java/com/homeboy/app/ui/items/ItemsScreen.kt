package com.homeboy.app.ui.items

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.*
import androidx.compose.material3.adaptive.navigation.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ui.ConnectionStatusAction
import com.homeboy.app.api.HBItem
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBTag
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.SessionHolder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

@Composable
fun ItemsTab() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: ItemsViewModel = viewModel(factory = ItemsViewModel.factory(app))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two panes once there's room (tablet / landscape); single pane otherwise.
        if (maxWidth >= 720.dp) {
            DualPaneItems(vm, totalWidth = maxWidth)
        } else {
            SinglePaneItems(vm)
        }
    }
}

/** Right-pane content selector shared by both layouts (encoded as Strings so it survives config changes). */
private fun parseAddSpec(spec: String): Pair<String?, String?> {
    val parts = spec.split("|", limit = 2)
    val pid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
    val pname = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    return pid to pname
}

@Composable
private fun DualPaneItems(vm: ItemsViewModel, totalWidth: Dp) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var addSpec by rememberSaveable { mutableStateOf<String?>(null) }   // null = not adding; "" = root add
    var listFraction by rememberSaveable { mutableStateOf(0.36f) }

    val density = LocalDensity.current
    val totalPx = with(density) { totalWidth.toPx() }

    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Box(Modifier.fillMaxHeight().weight(listFraction)) {
            ItemsListPane(
                vm = vm,
                selectedItemId = if (addSpec == null) selectedId else null,
                onItemSelected = { selectedId = it; addSpec = null },
                onAddItem = { addSpec = "" }
            )
        }

        PaneSplitter(onDelta = { dx ->
            listFraction = (listFraction + dx / totalPx).coerceIn(0.25f, 0.6f)
        })

        Box(Modifier.fillMaxHeight().weight(1f - listFraction)) {
            when {
                addSpec != null -> {
                    val (pid, pname) = parseAddSpec(addSpec!!)
                    AddEditItemScreen(
                        itemId = null, parentId = pid, parentName = pname,
                        onBack = { addSpec = null },
                        onSaved = { vm.load(); addSpec = null }
                    )
                }
                selectedId != null -> ItemDetailScreen(
                    itemId = selectedId!!,
                    onBack = { selectedId = null },
                    onItemUpdated = { vm.load() },
                    onAddSubItem = { parentId, parentName -> addSpec = "$parentId|$parentName" }
                )
                else -> EmptyDetailPlaceholder()
            }
        }
    }
}

@Composable
private fun SinglePaneItems(vm: ItemsViewModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var addSpec by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(selectedId != null || addSpec != null) {
        if (addSpec != null) addSpec = null else selectedId = null
    }

    when {
        addSpec != null -> {
            val (pid, pname) = parseAddSpec(addSpec!!)
            AddEditItemScreen(
                itemId = null, parentId = pid, parentName = pname,
                onBack = { addSpec = null },
                onSaved = { vm.load(); addSpec = null }
            )
        }
        selectedId != null -> ItemDetailScreen(
            itemId = selectedId!!,
            onBack = { selectedId = null },
            onItemUpdated = { vm.load() },
            onAddSubItem = { parentId, parentName -> addSpec = "$parentId|$parentName" }
        )
        else -> ItemsListPane(
            vm = vm,
            selectedItemId = null,
            onItemSelected = { selectedId = it },
            onAddItem = { addSpec = "" }
        )
    }
}

/** Thin, themed, draggable splitter with a center grab handle — Gmail / Claude style. */
@Composable
private fun PaneSplitter(onDelta: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxHeight()
            .width(16.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDelta(it) },
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Hairline that blends into the surface.
        Box(
            Modifier.fillMaxHeight().width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        // Center grab handle — tints to primary while dragging.
        Box(
            Modifier.width(4.dp).height(40.dp).clip(CircleShape).background(
                if (dragging) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsListPane(
    vm: ItemsViewModel,
    selectedItemId: String? = null,
    onItemSelected: (String) -> Unit,
    onAddItem: () -> Unit
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val filterLocationId by vm.filterLocationId.collectAsStateWithLifecycle()
    val filterTagId by vm.filterTagId.collectAsStateWithLifecycle()
    val showArchived by vm.showArchived.collectAsStateWithLifecycle()
    val sortMode by vm.sortMode.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    var showFilters by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(emptySet<String>()) }
    val isSelecting = selectedItems.isNotEmpty()
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    // Snap results to the top whenever the search results change. Semantic re-ranking REORDERS the
    // list, and because the rows are keyed by id, a LazyGrid/Column anchors to whatever item was at
    // the top and follows it to its new (lower) position — scrolling the user down. Re-running this
    // on `items` (after the debounced results arrive), not just on the keystroke, wins that race.
    LaunchedEffect(items, query) {
        if (query.isNotBlank()) {
            // requestScrollToItem (not scrollToItem): a non-suspend, unconditional "put index 0 at
            // the top on the next measure" that overrides the key-anchor and survives the rapid
            // re-emissions while typing (which would cancel a suspend scroll mid-flight).
            runCatching { gridState.requestScrollToItem(0) }
            runCatching { listState.requestScrollToItem(0) }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler(isSelecting) { selectedItems = emptySet() }

    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearSnackbar()
        }
    }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete ${selectedItems.size} item${if (selectedItems.size > 1) "s" else ""}?") },
            text = { Text("These items will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDeleteConfirm = false
                    vm.bulkDelete(selectedItems)
                    selectedItems = emptySet()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (showSearch) {
                SearchBar(
                    query = query,
                    onQueryChange = { vm.setQuery(it) },
                    onSearch = {},
                    active = false,
                    onActiveChange = {},
                    placeholder = { Text("Search items…") },
                    leadingIcon = {
                        IconButton(onClick = { showSearch = false; vm.setQuery("") }) {
                            Icon(Icons.Default.ArrowBack, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                ) {}
            } else {
                TopAppBar(
                    title = { Text("Items", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        ConnectionStatusAction()
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        IconButton(onClick = { showFilters = !showFilters }) {
                            Icon(
                                if (showFilters) Icons.Filled.FilterList else Icons.Outlined.FilterList,
                                "Filters"
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        floatingActionButton = {
            if (!isSelecting) {
                ExtendedFloatingActionButton(
                    onClick = onAddItem,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add Item") }
                )
            }
        },
        bottomBar = {
            if (isSelecting) {
                BulkActionBar(
                    count = selectedItems.size,
                    onArchive = { vm.bulkArchive(selectedItems); selectedItems = emptySet() },
                    onDelete = { showBulkDeleteConfirm = true },
                    onClear = { selectedItems = emptySet() }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(showFilters) {
                FilterPanel(
                    locations = locations,
                    tags = tags,
                    filterLocationId = filterLocationId,
                    filterTagId = filterTagId,
                    showArchived = showArchived,
                    sortMode = sortMode,
                    viewMode = viewMode,
                    sortMenuOpen = sortMenuOpen,
                    onSortMenuToggle = { sortMenuOpen = it },
                    onLocationFilter = { vm.setFilterLocation(it) },
                    onTagFilter = { vm.setFilterTag(it) },
                    onArchivedToggle = { vm.setShowArchived(it) },
                    onSortSelected = { vm.setSortMode(it) },
                    onViewModeToggle = { vm.toggleViewMode() },
                    onClearAll = { vm.clearFilters() }
                )
            }

            if (loading && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("No items", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (viewMode == "grid") {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }, contentType = { "item" }) { item ->
                        val isItemSelected = item.id in selectedItems
                        ItemGridCard(
                            item = item,
                            isSelecting = isSelecting,
                            isSelected = isItemSelected,
                            onClick = {
                                if (isSelecting) {
                                    selectedItems = if (isItemSelected) selectedItems - item.id else selectedItems + item.id
                                } else onItemSelected(item.id)
                            },
                            onLongClick = { selectedItems = selectedItems + item.id }
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }, contentType = { "item" }) { item ->
                        val isItemSelected = item.id in selectedItems
                        ItemListRow(
                            item = item,
                            selected = item.id == selectedItemId,
                            isSelecting = isSelecting,
                            isSelected = isItemSelected,
                            onClick = {
                                if (isSelecting) {
                                    selectedItems = if (isItemSelected) selectedItems - item.id else selectedItems + item.id
                                } else onItemSelected(item.id)
                            },
                            onLongClick = { selectedItems = selectedItems + item.id }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(
    locations: List<HBLocation>,
    tags: List<HBTag>,
    filterLocationId: String?,
    filterTagId: String?,
    showArchived: Boolean,
    sortMode: SortMode,
    viewMode: String,
    sortMenuOpen: Boolean,
    onSortMenuToggle: (Boolean) -> Unit,
    onLocationFilter: (String?) -> Unit,
    onTagFilter: (String?) -> Unit,
    onArchivedToggle: (Boolean) -> Unit,
    onSortSelected: (SortMode) -> Unit,
    onViewModeToggle: () -> Unit,
    onClearAll: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Sort chip
                Box {
                    FilterChip(
                        selected = sortMode != SortMode.NAME_ASC,
                        onClick = { onSortMenuToggle(true) },
                        label = { Text(sortMode.label, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)) }
                    )
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { onSortMenuToggle(false) }) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = { onSortSelected(mode); onSortMenuToggle(false) },
                                leadingIcon = if (mode == sortMode) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }

                // View mode chip
                FilterChip(
                    selected = false,
                    onClick = onViewModeToggle,
                    label = { Text(if (viewMode == "list") "List" else "Grid", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(
                            if (viewMode == "list") Icons.Default.ViewList else Icons.Default.GridView,
                            null, Modifier.size(16.dp)
                        )
                    }
                )

                // Archive chip
                FilterChip(
                    selected = showArchived,
                    onClick = { onArchivedToggle(!showArchived) },
                    label = { Text(if (showArchived) "Archived" else "Active", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Default.Archive, null, Modifier.size(16.dp)) }
                )
            }

            // Location + tag filters
            if (locations.isNotEmpty() || tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (locations.isNotEmpty()) {
                        var locMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(
                                selected = filterLocationId != null,
                                onClick = { locMenuOpen = true },
                                label = {
                                    Text(
                                        locations.find { it.id == filterLocationId }?.name ?: "Location",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Place, null, Modifier.size(16.dp)) },
                                trailingIcon = if (filterLocationId != null) {
                                    { Icon(Icons.Default.Close, null, Modifier.size(14.dp)
                                        .clickable { onLocationFilter(null) }) }
                                } else null
                            )
                            DropdownMenu(expanded = locMenuOpen, onDismissRequest = { locMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("All locations") }, onClick = { onLocationFilter(null); locMenuOpen = false })
                                locations.forEach { loc ->
                                    DropdownMenuItem(
                                        text = { Text(loc.name) },
                                        onClick = { onLocationFilter(loc.id); locMenuOpen = false },
                                        leadingIcon = if (loc.id == filterLocationId) {
                                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }

                    if (tags.isNotEmpty()) {
                        var tagMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(
                                selected = filterTagId != null,
                                onClick = { tagMenuOpen = true },
                                label = {
                                    Text(
                                        tags.find { it.id == filterTagId }?.name ?: "Tag",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Label, null, Modifier.size(16.dp)) },
                                trailingIcon = if (filterTagId != null) {
                                    { Icon(Icons.Default.Close, null, Modifier.size(14.dp)
                                        .clickable { onTagFilter(null) }) }
                                } else null
                            )
                            DropdownMenu(expanded = tagMenuOpen, onDismissRequest = { tagMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("All tags") }, onClick = { onTagFilter(null); tagMenuOpen = false })
                                tags.forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text(tag.name) },
                                        onClick = { onTagFilter(tag.id); tagMenuOpen = false },
                                        leadingIcon = if (tag.id == filterTagId) {
                                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }

                    if (filterLocationId != null || filterTagId != null) {
                        TextButton(onClick = onClearAll) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemListRow(
    item: HBItem,
    selected: Boolean = false,
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.archived) 0.6f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 1.dp,
            pressedElevation = 2.dp
        )
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (item.archived) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Archive, null, modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            supportingContent = {
                Column {
                    item.effectiveLocation?.let { loc ->
                        Text(loc.name, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.effectiveLabels.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.effectiveLabels.take(3).forEach { label ->
                                TagChipSmall(label.name, label.color)
                            }
                        }
                    }
                }
            },
            trailingContent = {
                if (item.quantity > 1) {
                    Text("×${item.quantity}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium)
                }
            },
            leadingContent = {
                if (isSelecting) {
                    Checkbox(checked = isSelected, onCheckedChange = null,
                        modifier = Modifier.size(44.dp))
                } else {
                    ItemThumbnail(item = item, size = 44.dp, corner = 8.dp, iconSize = 22.dp)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemGridCard(
    item: HBItem,
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (item.archived) 0.6f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = if (isSelected) {
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            )
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainer,
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        }
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    ItemThumbnail(item = item, size = 110.dp, corner = 12.dp, iconSize = 32.dp,
                        fillWidth = true)
                    if (item.archived && !isSelecting) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.8f),
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Archive, null,
                                modifier = Modifier.padding(4.dp).size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (isSelecting) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val locName = item.effectiveLocation?.name ?: ""
                        Text(
                            text = locName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.quantity > 1) {
                            Text(
                                text = "×${item.quantity}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemThumbnail(
    item: HBItem,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    fillWidth: Boolean = false
) {
    val shape = RoundedCornerShape(corner)
    val boxMod = (if (fillWidth) Modifier.fillMaxSize() else Modifier.size(size))
        .clip(shape)
        .background(MaterialTheme.colorScheme.primaryContainer)

    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    // The entities list response doesn't include an image id, so fall back to
    // resolving the item's primary photo lazily (cached per session).
    var attId by remember(item.id) { mutableStateOf(item.previewAttachmentId) }
    LaunchedEffect(item.id) {
        if (attId == null) attId = ThumbnailResolver.resolve(item.id, app.repository)
    }
    val currentAttId = attId

    Box(boxMod, contentAlignment = Alignment.Center) {
        if (currentAttId != null && SessionHolder.apiBase.isNotBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(ctx)
                    // photoModel resolves "pending-" ids (offline-queued photos) to their local file
                    .data(app.repository.photoModel(item.id, currentAttId))
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Icon(Icons.Outlined.Inventory2, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(iconSize))
                },
                error = {
                    Icon(Icons.Outlined.Inventory2, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(iconSize))
                }
            )
        } else {
            Icon(Icons.Outlined.Inventory2, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
fun TagChipSmall(name: String, hexColor: String? = null) {
    val bg = hexColor?.let { parseHexColor(it) } ?: MaterialTheme.colorScheme.secondaryContainer
    Surface(
        color = bg.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun parseHexColor(hex: String): Color {
    return try {
        val cleaned = hex.trimStart('#')
        val value = cleaned.toLong(16)
        Color(
            red = ((value shr 16) and 0xFF) / 255f,
            green = ((value shr 8) and 0xFF) / 255f,
            blue = (value and 0xFF) / 255f
        )
    } catch (_: Exception) {
        Color.Gray
    }
}

/**
 * Resolves a list item's primary photo attachment id by fetching its detail once.
 * Cached per session ("" = item has no photo) so each item is fetched at most once.
 */
private object ThumbnailResolver {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(itemId: String, repo: HomeboxRepository): String? {
        cache[itemId]?.let { return it.ifEmpty { null } }
        return try {
            val detail = repo.getItem(itemId)
            val att = detail.attachments?.firstOrNull {
                (it.type ?: "photo").equals("photo", ignoreCase = true)
            }
            val id = att?.id ?: ""
            cache[itemId] = id
            id.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
private fun BulkActionBar(
    count: Int,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, "Clear selection",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                "$count selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = onArchive) {
                Icon(Icons.Default.Archive, "Archive selected",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete selected",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyDetailPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))
            Text("Select an item", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

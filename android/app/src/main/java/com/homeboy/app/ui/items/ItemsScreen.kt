package com.homeboy.app.ui.items

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBItem
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBTag
import com.homeboy.app.data.SessionHolder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ItemsTab() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: ItemsViewModel = viewModel(factory = ItemsViewModel.factory(app))

    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()

    BackHandler(navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                ItemsListPane(
                    vm = vm,
                    selectedItemId = navigator.currentDestination?.content?.takeIf { it != "add" },
                    onItemSelected = { id ->
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
                    },
                    onAddItem = {
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Extra, "add") }
                    }
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val itemId = navigator.currentDestination?.content
                if (itemId != null && itemId != "add") {
                    ItemDetailScreen(
                        itemId = itemId,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onItemUpdated = { vm.load() },
                        onAddSubItem = { parentId, parentName ->
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Extra, "add:$parentId:$parentName")
                            }
                        }
                    )
                } else {
                    EmptyDetailPlaceholder()
                }
            }
        },
        extraPane = {
            AnimatedPane {
                val content = navigator.currentDestination?.content ?: ""
                val parts = content.split(":")
                val parentId = if (parts.size >= 2) parts[1] else null
                val parentName = if (parts.size >= 3) parts[2] else null
                AddEditItemScreen(
                    itemId = null,
                    parentId = parentId,
                    parentName = parentName,
                    onBack = { scope.launch { navigator.navigateBack() } },
                    onSaved = {
                        vm.load()
                        scope.launch { navigator.navigateBack() }
                    }
                )
            }
        }
    )
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

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearSnackbar()
        }
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
            ExtendedFloatingActionButton(
                onClick = onAddItem,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add Item") }
            )
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
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }, contentType = { "item" }) { item ->
                        ItemGridCard(item = item, onClick = { onItemSelected(item.id) },
                            onDelete = { vm.deleteItem(item.id) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }, contentType = { "item" }) { item ->
                        ItemListRow(item = item,
                            selected = item.id == selectedItemId,
                            onClick = { onItemSelected(item.id) },
                            onDelete = { vm.deleteItem(item.id) })
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemListRow(
    item: HBItem,
    selected: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { showDeleteDialog = true }
            false
        }
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete item?") },
            text = { Text("\"${item.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.padding(end = 20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
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
                    if (item.location != null) {
                        Text(item.location.name, style = MaterialTheme.typography.bodySmall,
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
                ItemThumbnail(item = item, size = 44.dp, corner = 8.dp, iconSize = 22.dp)
            },
            colors = ListItemDefaults.colors(
                containerColor = if (selected)
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                else
                    MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (item.archived) 0.6f else 1f)
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun ItemGridCard(item: HBItem, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete item?") },
            text = { Text("\"${item.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (item.archived) 0.6f else 1f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                ItemThumbnail(item = item, size = 96.dp, corner = 8.dp, iconSize = 36.dp,
                    fillWidth = true)
                if (item.archived) {
                    Icon(Icons.Default.Archive, null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (item.location != null) {
                Text(item.location.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (item.quantity > 1) {
                Text("×${item.quantity}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
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
    val attId = item.previewAttachmentId

    Box(boxMod, contentAlignment = Alignment.Center) {
        if (attId != null && SessionHolder.apiBase.isNotBlank()) {
            val ctx = LocalContext.current
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(SessionHolder.attachmentUrl(item.id, attId))
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

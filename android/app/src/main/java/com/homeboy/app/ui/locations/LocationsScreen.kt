package com.homeboy.app.ui.locations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBLocationTreeItem

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
    val snackbarHostState = remember { SnackbarHostState() }

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

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Locations", fontWeight = FontWeight.SemiBold) },
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
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                tree.forEach { node ->
                    item {
                        LocationTreeNode(
                            node = node,
                            depth = 0,
                            onEdit = { editingLocation = it },
                            onDelete = { vm.deleteLocation(it.id) },
                            onAddChild = { addParentId = it.id; showAddSheet = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationTreeNode(
    node: HBLocationTreeItem,
    depth: Int,
    onEdit: (HBLocationTreeItem) -> Unit,
    onDelete: (HBLocationTreeItem) -> Unit,
    onAddChild: (HBLocationTreeItem) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete location?") },
            text = { Text("\"${node.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete(node) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    Column {
        ListItem(
            headlineContent = { Text(node.name, fontWeight = FontWeight.Medium) },
            supportingContent = node.description?.takeIf { it.isNotBlank() }?.let {
                { Text(it, style = MaterialTheme.typography.bodySmall) }
            },
            leadingContent = {
                Row {
                    if (depth > 0) Spacer(Modifier.width((depth * 20).dp))
                    Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (node.children.isNotEmpty()) {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, Modifier.size(20.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, null, Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Add sub-location") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menuExpanded = false; onAddChild(node) }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menuExpanded = false; onEdit(node) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
            },
            modifier = Modifier.clickable { expanded = !expanded }
        )
        HorizontalDivider(Modifier.padding(start = if (depth > 0) ((depth * 20) + 56).dp else 56.dp))

        if (expanded && node.children.isNotEmpty()) {
            node.children.forEach { child ->
                LocationTreeNode(node = child, depth = depth + 1,
                    onEdit = onEdit, onDelete = onDelete, onAddChild = onAddChild)
            }
        }
    }
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

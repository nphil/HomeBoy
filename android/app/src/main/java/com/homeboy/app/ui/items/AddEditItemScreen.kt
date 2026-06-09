package com.homeboy.app.ui.items

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBTag

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditItemScreen(
    itemId: String?,        // null = create new
    parentId: String?,
    parentName: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HomeboxApplication
    val vm: AddEditItemViewModel = viewModel(
        key = "addedit_${itemId ?: "new"}",
        factory = AddEditItemViewModel.factory(app)
    )

    val existingItem by vm.existingItem.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()

    LaunchedEffect(itemId) { itemId?.let { vm.loadExisting(it) } }
    LaunchedEffect(saved) { if (saved) onSaved() }

    // Form state — initialised once from existingItem when it arrives
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var selectedLocationId by remember { mutableStateOf<String?>(null) }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var locationMenuOpen by remember { mutableStateOf(false) }
    var tagMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(existingItem) {
        existingItem?.let { item ->
            if (name.isEmpty()) {
                name = item.name
                description = item.description ?: ""
                quantityText = item.quantity.toString()
                selectedLocationId = item.location?.id
                selectedTagIds = item.effectiveLabels.map { it.id }.toSet()
            }
        }
    }

    LaunchedEffect(parentId) {
        // pre-seed parent, nothing else needed
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (itemId != null) "Edit Item" else "Add Item", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, null) }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val qty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            vm.save(
                                name = name.trim(),
                                description = description.trim(),
                                quantity = qty,
                                locationId = selectedLocationId,
                                tagIds = selectedTagIds.toList(),
                                parentId = parentId?.takeIf { it.isNotBlank() },
                                existingId = itemId
                            )
                        },
                        enabled = name.isNotBlank() && !saving
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (parentName != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Sub-item of $parentName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(), maxLines = 4
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { quantityText = ((quantityText.toIntOrNull() ?: 1) - 1).coerceAtLeast(1).toString() }) {
                    Icon(Icons.Default.Remove, null)
                }
                OutlinedTextField(
                    value = quantityText, onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                IconButton(onClick = { quantityText = ((quantityText.toIntOrNull() ?: 1) + 1).toString() }) {
                    Icon(Icons.Default.Add, null)
                }
            }

            // Location picker
            Box {
                OutlinedTextField(
                    value = locations.find { it.id == selectedLocationId }?.name ?: "",
                    onValueChange = {},
                    label = { Text("Location") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { locationMenuOpen = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(expanded = locationMenuOpen, onDismissRequest = { locationMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("No location") }, onClick = { selectedLocationId = null; locationMenuOpen = false })
                    locations.forEach { loc ->
                        DropdownMenuItem(
                            text = { Text(loc.name) },
                            onClick = { selectedLocationId = loc.id; locationMenuOpen = false },
                            leadingIcon = if (loc.id == selectedLocationId) {
                                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            // Tag picker
            if (tags.isNotEmpty()) {
                Column {
                    Text("Tags", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tags.forEach { tag ->
                            val selected = tag.id in selectedTagIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedTagIds = if (selected)
                                        selectedTagIds - tag.id
                                    else
                                        selectedTagIds + tag.id
                                },
                                label = { Text(tag.name, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

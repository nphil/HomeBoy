package com.homeboy.app.ui.items

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.LlmEngineManager
import com.homeboy.app.ai.TagSuggestionService
import com.homeboy.app.api.HBEntityField
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val keepLocation by vm.keepLocation.collectAsStateWithLifecycle()
    val keepTags by vm.keepTags.collectAsStateWithLifecycle()
    val stickyLocationId by vm.stickyLocationId.collectAsStateWithLifecycle()
    val stickyTagIds by vm.stickyTagIds.collectAsStateWithLifecycle()
    val aiSuggestionsOn by vm.aiSuggestionsOn.collectAsStateWithLifecycle()
    val tagSuggestions by vm.tagSuggestions.collectAsStateWithLifecycle()
    val llmState by vm.llmState.collectAsStateWithLifecycle()

    val isCreate = itemId == null
    val scope = rememberCoroutineScope()

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

    // Photos picked from the gallery, kept as raw bytes for upload on save.
    val photoBytes = remember { mutableStateListOf<ByteArray>() }
    // Editable custom fields (seeded once from the existing item).
    val customFields = remember { mutableStateListOf<HBEntityField>() }
    var stickySeeded by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    }
                }
                photoBytes.addAll(loaded)
            }
        }
    }

    LaunchedEffect(existingItem) {
        existingItem?.let { item ->
            if (name.isEmpty()) {
                name = item.name
                description = item.description ?: ""
                quantityText = item.quantity.toString()
                selectedLocationId = item.effectiveLocation?.id
                selectedTagIds = item.effectiveLabels.map { it.id }.toSet()
                customFields.clear()
                customFields.addAll(item.fields.orEmpty())
            }
        }
    }

    // Seed sticky location/tags for a fresh quick-add (not for sub-items or edits).
    LaunchedEffect(stickyLocationId, stickyTagIds, keepLocation, keepTags) {
        if (isCreate && !stickySeeded && parentId == null) {
            if (keepLocation && stickyLocationId != null && selectedLocationId == null) {
                selectedLocationId = stickyLocationId
            }
            if (keepTags && stickyTagIds.isNotEmpty() && selectedTagIds.isEmpty()) {
                selectedTagIds = stickyTagIds.toSet()
            }
            stickySeeded = true
        }
    }

    // Ask the on-device LLM for tag ideas shortly after the user stops typing.
    LaunchedEffect(name, description, aiSuggestionsOn) {
        if (aiSuggestionsOn) vm.requestTagSuggestions(name, description)
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
                                existingId = itemId,
                                photos = photoBytes.toList(),
                                fields = customFields.toList()
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

        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 640.dp).verticalScroll(rememberScrollState())
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
                label = { Text("Name") },
                placeholder = { Text("What is it?") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("Required") },
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next
                )
            )

            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(), maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                )
            )

            // Quantity — Material You tonal stepper.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quantity", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = { quantityText = ((quantityText.toIntOrNull() ?: 1) - 1).coerceAtLeast(1).toString() }
                    ) { Icon(Icons.Default.Remove, "Decrease") }
                    Text(
                        (quantityText.toIntOrNull() ?: 1).toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.widthIn(min = 40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    FilledTonalIconButton(
                        onClick = { quantityText = ((quantityText.toIntOrNull() ?: 1) + 1).toString() }
                    ) { Icon(Icons.Default.Add, "Increase") }
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

            // Tags — AI suggestions (when on) sit above a bounded, scrollable list of every tag,
            // so a long tag library never takes over the form.
            if (aiSuggestionsOn || tags.isNotEmpty()) {
                TagsSection(
                    allTags = tags,
                    selectedTagIds = selectedTagIds,
                    aiOn = aiSuggestionsOn,
                    llmState = llmState,
                    suggestions = tagSuggestions,
                    onToggle = { id ->
                        selectedTagIds = if (id in selectedTagIds) selectedTagIds - id
                                         else selectedTagIds + id
                    },
                    onAddNovel = { novelName ->
                        vm.createNovelTag(novelName) { created -> selectedTagIds = selectedTagIds + created.id }
                    }
                )
            }

            // Custom fields
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Custom Fields", Modifier.weight(1f))
                    TextButton(onClick = {
                        customFields.add(HBEntityField(type = "text", name = "", textValue = ""))
                    }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    customFields.forEachIndexed { i, field ->
                        CustomFieldRow(
                            field = field,
                            onChange = { customFields[i] = it },
                            onRemove = { customFields.removeAt(i) }
                        )
                    }
                }
            }

            // Photos
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Photos", Modifier.weight(1f))
                    TextButton(onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(Icons.Default.AddAPhoto, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                if (photoBytes.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(photoBytes) { index, bytes ->
                            val bmp = remember(bytes) {
                                runCatching {
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }.getOrNull()
                            }
                            Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                                bmp?.let {
                                    Image(it.asImageBitmap(), null,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Icon(Icons.Default.Cancel, "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                                        .clickable { photoBytes.removeAt(index) })
                                if (index == 0) {
                                    Surface(color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(2.dp)) {
                                        Text("Primary", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sticky fields (quick-add only)
            if (isCreate) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep location for next add", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = keepLocation, onCheckedChange = { vm.setKeepLocation(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep tags for next add", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = keepTags, onCheckedChange = { vm.setKeepTags(it) })
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(32.dp))
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TagsSection(
    allTags: List<HBTag>,
    selectedTagIds: Set<String>,
    aiOn: Boolean,
    llmState: LlmEngineManager.State,
    suggestions: TagSuggestionService.Suggestions?,
    onToggle: (String) -> Unit,
    onAddNovel: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Tags", Modifier.weight(1f))
            val n = selectedTagIds.size
            if (n > 0) {
                Text("$n selected", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (aiOn) {
            AiSuggestionBox(
                llmState = llmState,
                suggestions = suggestions,
                selectedTagIds = selectedTagIds,
                onToggleExisting = onToggle,
                onAddNovel = onAddNovel
            )
        }

        if (allTags.isNotEmpty()) {
            // Selected tags float to the front so the current choice stays visible in the scroll box.
            val ordered = remember(allTags, selectedTagIds) {
                allTags.sortedByDescending { it.id in selectedTagIds }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ordered.forEach { tag ->
                            TagFilterChip(
                                tag = tag,
                                selected = tag.id in selectedTagIds,
                                onClick = { onToggle(tag.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A selectable chip for an existing tag, showing its own icon/color when it has one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagFilterChip(tag: HBTag, selected: Boolean, onClick: () -> Unit) {
    val leading = com.homeboy.app.ui.tagIcon(tag.icon)
    val tagColor = tag.color?.let { parseHexColor(it) }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(tag.name, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = when {
            selected -> { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } }
            leading != null -> {
                { Icon(leading, null, Modifier.size(16.dp),
                    tint = tagColor ?: MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else -> null
        }
    )
}

/**
 * AI tag ideas. Matches against the user's existing tags first (tap to apply — no "+", they already
 * exist); anything new is offered as a novel chip with a "+" since tapping it creates the tag.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AiSuggestionBox(
    llmState: LlmEngineManager.State,
    suggestions: TagSuggestionService.Suggestions?,
    selectedTagIds: Set<String>,
    onToggleExisting: (String) -> Unit,
    onAddNovel: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("AI suggestions", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            when (llmState) {
                is LlmEngineManager.State.Loading -> AiStatusLine("Loading AI model…")
                is LlmEngineManager.State.Generating -> AiStatusLine("Finding tags…")
                is LlmEngineManager.State.Error -> Text(
                    llmState.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    val s = suggestions
                    if (s == null || s.isEmpty) {
                        Text("Type a name to get tag ideas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Existing matches first — tap to apply, no "+" (they already exist).
                            s.existing.forEach { tag ->
                                val sel = tag.id in selectedTagIds
                                FilterChip(
                                    selected = sel,
                                    onClick = { onToggleExisting(tag.id) },
                                    label = { Text(tag.name, style = MaterialTheme.typography.labelMedium) },
                                    leadingIcon = if (sel) {
                                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                            // Novel ideas — "+" because tapping creates a brand-new tag.
                            s.novel.forEach { novelName ->
                                AssistChip(
                                    onClick = { onAddNovel(novelName) },
                                    label = { Text(novelName, style = MaterialTheme.typography.labelMedium) },
                                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) }
                                )
                            }
                        }
                        Text("via ${s.modelLabel} · ${s.backend.shortLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun AiStatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CustomFieldRow(
    field: HBEntityField,
    onChange: (HBEntityField) -> Unit,
    onRemove: () -> Unit
) {
    var typeMenu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = field.name,
                    onValueChange = { onChange(field.copy(name = it)) },
                    label = { Text("Field name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.DeleteOutline, "Remove field",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }) {
                        Text(field.type.replaceFirstChar { it.uppercase() })
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        listOf("text", "number", "boolean").forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.replaceFirstChar { it.uppercase() }) },
                                onClick = { typeMenu = false; onChange(field.copy(type = t)) }
                            )
                        }
                    }
                }
                when (field.type) {
                    "boolean" -> {
                        Spacer(Modifier.weight(1f))
                        Text(if (field.booleanValue) "True" else "False",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(checked = field.booleanValue,
                            onCheckedChange = { onChange(field.copy(booleanValue = it)) })
                    }
                    "number" -> OutlinedTextField(
                        value = if (field.numberValue == 0) "" else field.numberValue.toString(),
                        onValueChange = { onChange(field.copy(numberValue = it.toIntOrNull() ?: 0)) },
                        label = { Text("Value") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    else -> OutlinedTextField(
                        value = field.textValue,
                        onValueChange = { onChange(field.copy(textValue = it)) },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

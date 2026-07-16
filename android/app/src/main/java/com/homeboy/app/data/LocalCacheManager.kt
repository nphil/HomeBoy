package com.homeboy.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.homeboy.app.api.*
import java.io.File
import java.util.UUID

data class PendingMutation(
    val uuid: String,
    val type: String, // CREATE/UPDATE/DELETE_ITEM, _LOCATION, _TAG, _MAINTENANCE
    val targetId: String,
    val payloadJson: String
)

/** Payload for queued maintenance mutations — the entry plus the item it belongs to. */
data class PendingMaintenancePayload(
    val itemId: String,
    val entry: HBMaintenanceCreate
)

class LocalCacheManager(private val context: Context) {
    private val gson = Gson()

    private val itemsFile = File(context.filesDir, "items_cache.json")
    private val detailsFile = File(context.filesDir, "details_cache.json")
    private val locationsFile = File(context.filesDir, "locations_cache.json")
    private val tagsFile = File(context.filesDir, "tags_cache.json")
    private val mutationsFile = File(context.filesDir, "pending_mutations.json")

    init {
        // Publish the persisted queue size so the UI badge is correct from launch.
        ConnectionMonitor.setPendingCount(getPendingMutations().size)
    }

    // -------------------------------------------------------------------------
    // Generic blob cache (maintenance lists, statistics, ...)
    // -------------------------------------------------------------------------

    private fun blobFile(key: String) =
        File(context.filesDir, "cache_" + key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun <T> saveBlob(key: String, value: T) {
        try {
            blobFile(key).writeText(gson.toJson(value))
        } catch (_: Exception) {}
    }

    fun <T> getBlob(key: String, type: java.lang.reflect.Type): T? {
        val f = blobFile(key)
        if (!f.exists()) return null
        return try {
            gson.fromJson<T>(f.readText(), type)
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T> getBlob(key: String): T? =
        getBlob(key, object : TypeToken<T>() {}.type)

    // -------------------------------------------------------------------------
    // File I/O
    // -------------------------------------------------------------------------

    fun getCachedItems(): List<HBItem> {
        if (!itemsFile.exists()) return emptyList()
        return try {
            val json = itemsFile.readText()
            val type = object : TypeToken<List<HBItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedItems(items: List<HBItem>) {
        try {
            itemsFile.writeText(gson.toJson(items))
        } catch (_: Exception) {}
    }

    fun getCachedItemDetails(): Map<String, HBItemDetail> {
        if (!detailsFile.exists()) return emptyMap()
        return try {
            val json = detailsFile.readText()
            val type = object : TypeToken<Map<String, HBItemDetail>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveCachedItemDetails(details: Map<String, HBItemDetail>) {
        try {
            detailsFile.writeText(gson.toJson(details))
        } catch (_: Exception) {}
    }

    fun saveCachedItemDetail(detail: HBItemDetail) {
        val details = getCachedItemDetails().toMutableMap()
        details[detail.id] = detail
        saveCachedItemDetails(details)
    }

    fun getCachedLocations(): List<HBLocation> {
        if (!locationsFile.exists()) return emptyList()
        return try {
            val json = locationsFile.readText()
            val type = object : TypeToken<List<HBLocation>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedLocations(locs: List<HBLocation>) {
        try {
            locationsFile.writeText(gson.toJson(locs))
        } catch (_: Exception) {}
    }

    fun getCachedTags(): List<HBTag> {
        if (!tagsFile.exists()) return emptyList()
        return try {
            val json = tagsFile.readText()
            val type = object : TypeToken<List<HBTag>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedTags(tags: List<HBTag>) {
        try {
            tagsFile.writeText(gson.toJson(tags))
        } catch (_: Exception) {}
    }

    fun getPendingMutations(): List<PendingMutation> {
        if (!mutationsFile.exists()) return emptyList()
        return try {
            val json = mutationsFile.readText()
            val type = object : TypeToken<List<PendingMutation>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePendingMutations(mutations: List<PendingMutation>) {
        try {
            mutationsFile.writeText(gson.toJson(mutations))
        } catch (_: Exception) {}
        ConnectionMonitor.setPendingCount(mutations.size)
    }

    fun clearPendingMutations() {
        savePendingMutations(emptyList())
    }

    // -------------------------------------------------------------------------
    // Offline Mutation Queueing & Overlay
    // -------------------------------------------------------------------------

    fun queueMutation(type: String, targetId: String, payload: Any): String {
        val uuid = UUID.randomUUID().toString()
        val payloadJson = gson.toJson(payload)
        val mutation = PendingMutation(uuid, type, targetId, payloadJson)
        val list = getPendingMutations().toMutableList()
        list.add(mutation)
        savePendingMutations(list)
        return uuid
    }

    // -------------------------------------------------------------------------
    // Offline Data Assembly (Cache + Local Queue Overlay)
    // -------------------------------------------------------------------------

    fun getAppliedItems(): List<HBItem> {
        val cached = getCachedItems().toMutableList()
        val mutations = getPendingMutations()

        for (m in mutations) {
            when (m.type) {
                "CREATE_ITEM" -> {
                    val payload = gson.fromJson(m.payloadJson, HBItemCreate::class.java)
                    val parentName = getCachedLocations().firstOrNull { it.id == payload.parentId }?.name
                        ?: cached.firstOrNull { it.id == payload.parentId }?.name ?: ""
                    val loc = payload.parentId?.let { HBItemLocation(it, parentName) }
                    val item = HBItem(
                        id = m.targetId,
                        name = payload.name,
                        description = payload.description,
                        quantity = payload.quantity,
                        archived = false,
                        location = loc,
                        parent = loc,
                        labels = payload.tagIds.map { tagId ->
                            val tagName = getCachedTags().firstOrNull { it.id == tagId }?.name ?: ""
                            HBItemLabel(tagId, tagName)
                        }
                    )
                    cached.add(item)
                }
                "UPDATE_ITEM" -> {
                    val payload = gson.fromJson(m.payloadJson, HBItemUpdate::class.java)
                    val index = cached.indexOfFirst { it.id == m.targetId }
                    val parentName = getCachedLocations().firstOrNull { it.id == payload.parentId }?.name
                        ?: cached.firstOrNull { it.id == payload.parentId }?.name ?: ""
                    val loc = payload.parentId?.let { HBItemLocation(it, parentName) }
                    if (index != -1) {
                        val original = cached[index]
                        cached[index] = original.copy(
                            name = payload.name,
                            description = payload.description,
                            quantity = payload.quantity,
                            archived = payload.archived,
                            location = loc,
                            parent = loc,
                            labels = payload.tagIds.map { tagId ->
                                val tagName = getCachedTags().firstOrNull { it.id == tagId }?.name ?: ""
                                HBItemLabel(tagId, tagName)
                            }
                        )
                    }
                }
                "DELETE_ITEM" -> {
                    cached.removeAll { it.id == m.targetId }
                }
            }
        }
        return cached
    }

    fun getAppliedItemDetail(id: String): HBItemDetail? {
        val details = getCachedItemDetails()
        var detail = details[id]

        val mutations = getPendingMutations()
        // Overlay mutations that affect this item
        for (m in mutations) {
            if (m.targetId == id) {
                when (m.type) {
                    "CREATE_ITEM" -> {
                        val payload = gson.fromJson(m.payloadJson, HBItemCreate::class.java)
                        val parentName = getCachedLocations().firstOrNull { it.id == payload.parentId }?.name ?: ""
                        val loc = payload.parentId?.let { HBItemLocation(it, parentName) }
                        detail = HBItemDetail(
                            id = id,
                            name = payload.name,
                            description = payload.description,
                            quantity = payload.quantity,
                            archived = false,
                            location = loc,
                            parent = payload.parentId?.let { HBItemSummary(it, parentName) },
                            labels = payload.tagIds.map { tagId ->
                                val tagName = getCachedTags().firstOrNull { it.id == tagId }?.name ?: ""
                                HBItemLabel(tagId, tagName)
                            }
                        )
                    }
                    "UPDATE_ITEM" -> {
                        val payload = gson.fromJson(m.payloadJson, HBItemUpdate::class.java)
                        val parentName = getCachedLocations().firstOrNull { it.id == payload.parentId }?.name ?: ""
                        val loc = payload.parentId?.let { HBItemLocation(it, parentName) }
                        detail = (detail ?: HBItemDetail(id = id, name = payload.name)).copy(
                            name = payload.name,
                            description = payload.description,
                            quantity = payload.quantity,
                            archived = payload.archived,
                            insured = payload.insured,
                            notes = payload.notes,
                            serialNumber = payload.serialNumber,
                            modelNumber = payload.modelNumber,
                            manufacturer = payload.manufacturer,
                            assetId = payload.assetId,
                            lifetimeWarranty = payload.lifetimeWarranty,
                            warrantyExpires = payload.warrantyExpires,
                            warrantyDetails = payload.warrantyDetails,
                            purchasePrice = payload.purchasePrice,
                            purchaseFrom = payload.purchaseFrom,
                            purchaseDate = payload.purchaseDate,
                            soldTo = payload.soldTo,
                            soldPrice = payload.soldPrice,
                            soldDate = payload.soldDate,
                            soldNotes = payload.soldNotes,
                            location = loc,
                            parent = payload.parentId?.let { HBItemSummary(it, parentName) },
                            labels = payload.tagIds.map { tagId ->
                                val tagName = getCachedTags().firstOrNull { it.id == tagId }?.name ?: ""
                                HBItemLabel(tagId, tagName)
                            }
                        )
                    }
                    "DELETE_ITEM" -> {
                        return null
                    }
                }
            }
        }
        return detail
    }

    fun getAppliedLocations(): List<HBLocation> {
        val cached = getCachedLocations().toMutableList()
        val mutations = getPendingMutations()

        for (m in mutations) {
            when (m.type) {
                "CREATE_LOCATION" -> {
                    val payload = gson.fromJson(m.payloadJson, HBLocationCreate::class.java)
                    val loc = HBLocation(
                        id = m.targetId,
                        name = payload.name,
                        description = payload.description,
                        parentId = payload.parentId,
                        itemCount = 0
                    )
                    cached.add(loc)
                }
                "UPDATE_LOCATION" -> {
                    val payload = gson.fromJson(m.payloadJson, HBLocationUpdate::class.java)
                    val index = cached.indexOfFirst { it.id == m.targetId }
                    if (index != -1) {
                        val original = cached[index]
                        cached[index] = original.copy(
                            name = payload.name,
                            description = payload.description,
                            parentId = payload.parentId
                        )
                    }
                }
                "DELETE_LOCATION" -> {
                    cached.removeAll { it.id == m.targetId }
                }
            }
        }
        return cached
    }

    fun getAppliedTags(): List<HBTag> {
        val cached = getCachedTags().toMutableList()
        val mutations = getPendingMutations()

        for (m in mutations) {
            when (m.type) {
                "CREATE_TAG" -> {
                    val payload = gson.fromJson(m.payloadJson, HBTagCreate::class.java)
                    val tag = HBTag(
                        id = m.targetId,
                        name = payload.name,
                        color = payload.color
                    )
                    cached.add(tag)
                }
                "UPDATE_TAG" -> {
                    val payload = gson.fromJson(m.payloadJson, HBTagUpdate::class.java)
                    val index = cached.indexOfFirst { it.id == m.targetId }
                    if (index != -1) {
                        val original = cached[index]
                        cached[index] = original.copy(
                            name = payload.name,
                            color = payload.color
                        )
                    }
                }
                "DELETE_TAG" -> {
                    cached.removeAll { it.id == m.targetId }
                }
            }
        }
        return cached
    }

    // -------------------------------------------------------------------------
    // Maintenance cache + overlay
    // -------------------------------------------------------------------------

    fun maintenanceKey(itemId: String) = "maint_item_$itemId"
    fun allMaintenanceKey(status: String?) = "maint_all_${status ?: "both"}"

    /** Cached maintenance log for one item with pending offline edits applied. */
    fun getAppliedMaintenance(itemId: String): List<HBMaintenanceEntry> {
        val type = object : TypeToken<List<HBMaintenanceEntry>>() {}.type
        val cached = getBlob<List<HBMaintenanceEntry>>(maintenanceKey(itemId), type).orEmpty().toMutableList()

        for (m in getPendingMutations()) {
            when (m.type) {
                "CREATE_MAINTENANCE" -> {
                    val p = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                    if (p.itemId == itemId) cached.add(entryFrom(m.targetId, p.entry))
                }
                "UPDATE_MAINTENANCE" -> {
                    val p = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                    val i = cached.indexOfFirst { it.id == m.targetId }
                    if (i != -1) cached[i] = entryFrom(m.targetId, p.entry)
                }
                "DELETE_MAINTENANCE" -> cached.removeAll { it.id == m.targetId }
            }
        }
        return cached
    }

    /** Cached global maintenance log with pending offline edits applied. */
    fun getAppliedAllMaintenance(status: String?): List<HBMaintenanceWithDetails> {
        val type = object : TypeToken<List<HBMaintenanceWithDetails>>() {}.type
        val cached = getBlob<List<HBMaintenanceWithDetails>>(allMaintenanceKey(status), type)
            .orEmpty().toMutableList()

        for (m in getPendingMutations()) {
            when (m.type) {
                "CREATE_MAINTENANCE" -> {
                    val p = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                    val itemName = getAppliedItems().firstOrNull { it.id == p.itemId }?.name
                    cached.add(withDetailsFrom(m.targetId, p, itemName))
                }
                "UPDATE_MAINTENANCE" -> {
                    val p = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                    val i = cached.indexOfFirst { it.id == m.targetId }
                    if (i != -1) {
                        val old = cached[i]
                        cached[i] = withDetailsFrom(m.targetId, p, old.itemName)
                            .copy(itemId = old.itemId)
                    }
                }
                "DELETE_MAINTENANCE" -> cached.removeAll { it.id == m.targetId }
            }
        }
        return cached
    }

    private fun entryFrom(id: String, e: HBMaintenanceCreate) = HBMaintenanceEntry(
        id = id,
        name = e.name,
        description = e.description,
        date = e.date,
        scheduledDate = e.scheduledDate,
        cost = e.cost
    )

    private fun withDetailsFrom(id: String, p: PendingMaintenancePayload, itemName: String?) =
        HBMaintenanceWithDetails(
            id = id,
            name = p.entry.name,
            description = p.entry.description,
            date = p.entry.date,
            scheduledDate = p.entry.scheduledDate,
            cost = p.entry.cost,
            itemId = p.itemId.takeIf { it.isNotBlank() },
            itemName = itemName
        )

    // -------------------------------------------------------------------------
    // Location Tree Builder Helper
    // -------------------------------------------------------------------------

    fun buildLocationTree(locations: List<HBLocation>): List<HBLocationTreeItem> {
        val itemsMap = locations.map { loc ->
            loc.id to HBLocationTreeItem(
                id = loc.id,
                name = loc.name,
                description = loc.description,
                children = emptyList(),
                itemCount = loc.itemCount
            )
        }.toMap().toMutableMap()

        val childrenMap = mutableMapOf<String?, MutableList<HBLocationTreeItem>>()
        locations.forEach { loc ->
            val parentId = loc.parentId
            val treeItem = itemsMap[loc.id] ?: return@forEach
            val cleanParentId = if (parentId.isNullOrBlank()) null else parentId
            if (!childrenMap.containsKey(cleanParentId)) {
                childrenMap[cleanParentId] = mutableListOf()
            }
            childrenMap[cleanParentId]?.add(treeItem)
        }

        fun assemble(item: HBLocationTreeItem): HBLocationTreeItem {
            val directChildren = childrenMap[item.id] ?: emptyList()
            val fullyAssembledChildren = directChildren.map { assemble(it) }
            return item.copy(children = fullyAssembledChildren)
        }

        val roots = childrenMap[null] ?: childrenMap[""] ?: emptyList()
        return roots.map { assemble(it) }
    }
}

package com.homeboy.app.data

import android.content.Context
import com.google.gson.Gson
import com.homeboy.app.api.*
import java.io.IOException
import java.util.UUID

class HomeboxRepository(
    private val context: Context,
    private val prefs: PreferencesRepository
) {
    private val cache = LocalCacheManager(context)
    private val gson = Gson()
    private var _client: HomeboxClient? = null

    private suspend fun client(): HomeboxClient {
        val token = prefs.getToken()
        val url = prefs.getServerUrl()
        val tenantId = prefs.getTenant()
        val existing = _client
        if (existing != null && token.isNotBlank()) {
            existing.token = token
            existing.tenant = tenantId
            syncSession(existing)
            return existing
        }
        val c = HomeboxClient(url)
        c.token = token
        c.tenant = tenantId
        _client = c
        syncSession(c)
        return c
    }

    private fun syncSession(c: HomeboxClient) {
        SessionHolder.apiBase = c.apiBase
        SessionHolder.token = c.token
        SessionHolder.tenant = c.tenant
    }

    fun invalidate() { _client = null }

    suspend fun login(serverUrl: String, email: String, password: String) {
        val url = serverUrl.trimEnd('/')
        prefs.setServerUrl(url)
        invalidate()
        val c = HomeboxClient(url)
        val resp = c.login(email, password)
        prefs.setToken(resp.token)
        c.token = resp.token
        _client = c
        syncSession(c)
    }

    suspend fun listGroups() = client().listGroups()

    suspend fun setActiveGroup(id: String?, name: String) {
        prefs.setTenant(id, name)
        _client?.let { it.tenant = id; syncSession(it) }
    }

    suspend fun uploadAttachment(itemId: String, bytes: ByteArray, filename: String, primary: Boolean) =
        client().uploadAttachment(itemId, bytes, filename, primary)

    suspend fun logout() { prefs.logout(); invalidate() }

    suspend fun getMe() = client().getMe()

    // -------------------------------------------------------------------------
    // Background Synchronization
    // -------------------------------------------------------------------------

    private suspend fun syncPendingMutations(client: HomeboxClient) {
        val mutations = cache.getPendingMutations()
        if (mutations.isEmpty()) return

        val idMap = mutableMapOf<String, String>()
        val remainingMutations = mutableListOf<PendingMutation>()

        for (m in mutations) {
            val translatedTargetId = idMap[m.targetId] ?: m.targetId
            try {
                when (m.type) {
                    "CREATE_ITEM" -> {
                        val payload = gson.fromJson(m.payloadJson, HBItemCreate::class.java)
                        val translatedParentId = payload.parentId?.let { idMap[it] ?: it }
                        val finalPayload = payload.copy(parentId = translatedParentId)
                        val created = client.createItem(finalPayload)
                        idMap[m.targetId] = created.id
                    }
                    "UPDATE_ITEM" -> {
                        val payload = gson.fromJson(m.payloadJson, HBItemUpdate::class.java)
                        val translatedParentId = payload.parentId?.let { idMap[it] ?: it }
                        val finalPayload = payload.copy(parentId = translatedParentId)
                        client.updateItem(translatedTargetId, finalPayload)
                    }
                    "DELETE_ITEM" -> {
                        client.deleteItem(translatedTargetId)
                    }
                    "CREATE_LOCATION" -> {
                        val payload = gson.fromJson(m.payloadJson, HBLocationCreate::class.java)
                        val translatedParentId = payload.parentId?.let { idMap[it] ?: it }
                        val finalPayload = payload.copy(parentId = translatedParentId)
                        val created = client.createLocation(finalPayload)
                        idMap[m.targetId] = created.id
                    }
                    "UPDATE_LOCATION" -> {
                        val payload = gson.fromJson(m.payloadJson, HBLocationUpdate::class.java)
                        val translatedParentId = payload.parentId?.let { idMap[it] ?: it }
                        val finalPayload = payload.copy(parentId = translatedParentId)
                        client.updateLocation(translatedTargetId, finalPayload)
                    }
                    "DELETE_LOCATION" -> {
                        client.deleteLocation(translatedTargetId)
                    }
                    "CREATE_TAG" -> {
                        val payload = gson.fromJson(m.payloadJson, HBTagCreate::class.java)
                        val created = client.createTag(payload)
                        idMap[m.targetId] = created.id
                    }
                    "UPDATE_TAG" -> {
                        val payload = gson.fromJson(m.payloadJson, HBTagUpdate::class.java)
                        client.updateTag(translatedTargetId, payload)
                    }
                    "DELETE_TAG" -> {
                        client.deleteTag(translatedTargetId)
                    }
                }
            } catch (e: IOException) {
                remainingMutations.add(m)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (remainingMutations.isEmpty()) {
            cache.clearPendingMutations()
        } else {
            val updatedRemaining = remainingMutations.map { m ->
                val newTargetId = idMap[m.targetId] ?: m.targetId
                var newPayloadJson = m.payloadJson
                try {
                    when (m.type) {
                        "CREATE_ITEM" -> {
                            val payload = gson.fromJson(m.payloadJson, HBItemCreate::class.java)
                            val finalPayload = payload.copy(parentId = payload.parentId?.let { idMap[it] ?: it })
                            newPayloadJson = gson.toJson(finalPayload)
                        }
                        "UPDATE_ITEM" -> {
                            val payload = gson.fromJson(m.payloadJson, HBItemUpdate::class.java)
                            val finalPayload = payload.copy(parentId = payload.parentId?.let { idMap[it] ?: it })
                            newPayloadJson = gson.toJson(finalPayload)
                        }
                        "CREATE_LOCATION" -> {
                            val payload = gson.fromJson(m.payloadJson, HBLocationCreate::class.java)
                            val finalPayload = payload.copy(parentId = payload.parentId?.let { idMap[it] ?: it })
                            newPayloadJson = gson.toJson(finalPayload)
                        }
                        "UPDATE_LOCATION" -> {
                            val payload = gson.fromJson(m.payloadJson, HBLocationUpdate::class.java)
                            val finalPayload = payload.copy(parentId = payload.parentId?.let { idMap[it] ?: it })
                            newPayloadJson = gson.toJson(finalPayload)
                        }
                    }
                } catch (_: Exception) {}
                m.copy(targetId = newTargetId, payloadJson = newPayloadJson)
            }
            cache.savePendingMutations(updatedRemaining)
        }
    }

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    suspend fun listItems(
        query: String? = null,
        locationIds: List<String> = emptyList(),
        labelIds: List<String> = emptyList(),
        parentIds: List<String> = emptyList(),
        includeArchived: Boolean = false,
        page: Int = 1,
        pageSize: Int = 500
    ): HBItemListResponse {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.listItems(query, locationIds, labelIds, parentIds, includeArchived, page, pageSize)
            cache.saveCachedItems(resp.items)
            return resp
        } catch (e: IOException) {
            val applied = cache.getAppliedItems()
            val filtered = applied.filter { item ->
                if (!includeArchived && item.archived) return@filter false
                if (!query.isNullOrBlank()) {
                    val q = query.lowercase()
                    val matchesName = item.name.lowercase().contains(q)
                    val matchesDesc = item.description?.lowercase()?.contains(q) ?: false
                    if (!matchesName && !matchesDesc) return@filter false
                }
                if (locationIds.isNotEmpty() && item.effectiveLocation?.id !in locationIds) return@filter false
                if (parentIds.isNotEmpty() && item.effectiveLocation?.id !in parentIds) return@filter false
                if (labelIds.isNotEmpty() && item.effectiveLabels.none { it.id in labelIds }) return@filter false
                true
            }
            return HBItemListResponse(items = filtered, total = filtered.size, page = page, pageSize = pageSize)
        }
    }

    suspend fun getItem(id: String): HBItemDetail {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.getItem(id)
            cache.saveCachedItemDetail(resp)
            return resp
        } catch (e: IOException) {
            return cache.getAppliedItemDetail(id) ?: throw e
        }
    }

    suspend fun createItem(item: HBItemCreate): HBItemDetail {
        val tempId = UUID.randomUUID().toString()
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.createItem(item)
            try {
                val fresh = c.listItems()
                cache.saveCachedItems(fresh.items)
            } catch (_: Exception) {}
            return resp
        } catch (e: IOException) {
            cache.queueMutation("CREATE_ITEM", tempId, item)
            val parentName = cache.getCachedLocations().firstOrNull { it.id == item.parentId }?.name ?: ""
            val loc = item.parentId?.let { HBItemLocation(it, parentName) }
            val mockDetail = HBItemDetail(
                id = tempId,
                name = item.name,
                description = item.description,
                quantity = item.quantity,
                location = loc,
                parent = item.parentId?.let { HBItemSummary(it, parentName) },
                labels = item.tagIds.map { tagId ->
                    val tagName = cache.getCachedTags().firstOrNull { it.id == tagId }?.name ?: ""
                    HBItemLabel(tagId, tagName)
                }
            )
            cache.saveCachedItemDetail(mockDetail)
            val mockItem = HBItem(
                id = tempId,
                name = item.name,
                description = item.description,
                quantity = item.quantity,
                location = loc,
                parent = loc,
                labels = mockDetail.labels
            )
            cache.saveCachedItems(cache.getCachedItems() + mockItem)
            return mockDetail
        }
    }

    suspend fun updateItem(id: String, item: HBItemUpdate): HBItemDetail {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.updateItem(id, item)
            try {
                val fresh = c.listItems()
                cache.saveCachedItems(fresh.items)
            } catch (_: Exception) {}
            return resp
        } catch (e: IOException) {
            cache.queueMutation("UPDATE_ITEM", id, item)
            val parentName = cache.getCachedLocations().firstOrNull { it.id == item.parentId }?.name ?: ""
            val loc = item.parentId?.let { HBItemLocation(it, parentName) }
            val updatedDetail = HBItemDetail(
                id = id,
                name = item.name,
                description = item.description,
                quantity = item.quantity,
                archived = item.archived,
                insured = item.insured,
                notes = item.notes,
                serialNumber = item.serialNumber,
                modelNumber = item.modelNumber,
                manufacturer = item.manufacturer,
                assetId = item.assetId,
                lifetimeWarranty = item.lifetimeWarranty,
                warrantyExpires = item.warrantyExpires,
                warrantyDetails = item.warrantyDetails,
                purchasePrice = item.purchasePrice,
                purchaseFrom = item.purchaseFrom,
                purchaseDate = item.purchaseDate,
                soldTo = item.soldTo,
                soldPrice = item.soldPrice,
                soldDate = item.soldDate,
                soldNotes = item.soldNotes,
                location = loc,
                parent = item.parentId?.let { HBItemSummary(it, parentName) },
                labels = item.tagIds.map { tagId ->
                    val tagName = cache.getCachedTags().firstOrNull { it.id == tagId }?.name ?: ""
                    HBItemLabel(tagId, tagName)
                }
            )
            cache.saveCachedItemDetail(updatedDetail)
            val updatedList = cache.getCachedItems().map {
                if (it.id == id) {
                    val mLoc = item.parentId?.let { pId -> HBItemLocation(pId, parentName) }
                    it.copy(
                        name = item.name,
                        description = item.description,
                        quantity = item.quantity,
                        archived = item.archived,
                        location = mLoc,
                        parent = mLoc,
                        labels = updatedDetail.labels
                    )
                } else it
            }
            cache.saveCachedItems(updatedList)
            return updatedDetail
        }
    }

    suspend fun deleteItem(id: String) {
        try {
            val c = client()
            syncPendingMutations(c)
            c.deleteItem(id)
            try {
                val fresh = c.listItems()
                cache.saveCachedItems(fresh.items)
            } catch (_: Exception) {}
        } catch (e: IOException) {
            cache.queueMutation("DELETE_ITEM", id, "")
            val filtered = cache.getCachedItems().filter { it.id != id }
            cache.saveCachedItems(filtered)
            val details = cache.getCachedItemDetails().toMutableMap()
            details.remove(id)
            cache.saveCachedItemDetails(details)
        }
    }

    // -------------------------------------------------------------------------
    // Maintenance
    // -------------------------------------------------------------------------

    suspend fun listMaintenance(itemId: String) = client().listMaintenance(itemId)
    suspend fun createMaintenance(itemId: String, entry: HBMaintenanceCreate) = client().createMaintenance(itemId, entry)
    suspend fun updateMaintenance(id: String, entry: HBMaintenanceCreate) = client().updateMaintenance(id, entry)
    suspend fun deleteMaintenance(id: String) = client().deleteMaintenance(id)

    /** Global maintenance log across all items. status = scheduled | completed | both */
    suspend fun listAllMaintenance(status: String? = "both") = client().listAllMaintenance(status)

    // -------------------------------------------------------------------------
    // Statistics
    // -------------------------------------------------------------------------

    suspend fun getStatistics() = client().getStatistics()
    suspend fun getStatsByLocation() = client().getStatsByLocation()
    suspend fun getStatsByTag() = client().getStatsByTag()
    suspend fun getPurchasePriceOverTime(start: String?, end: String?) =
        client().getPurchasePriceOverTime(start, end)

    // -------------------------------------------------------------------------
    // Locations
    // -------------------------------------------------------------------------

    suspend fun listLocations(): List<HBLocation> {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.listLocations()
            cache.saveCachedLocations(resp)
            return resp
        } catch (e: IOException) {
            return cache.getAppliedLocations()
        }
    }

    suspend fun getLocationTree(): List<HBLocationTreeItem> {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.getLocationTree()
            return resp
        } catch (e: IOException) {
            return cache.buildLocationTree(cache.getAppliedLocations())
        }
    }

    suspend fun createLocation(loc: HBLocationCreate): HBLocation {
        val tempId = UUID.randomUUID().toString()
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.createLocation(loc)
            try {
                val fresh = c.listLocations()
                cache.saveCachedLocations(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: IOException) {
            cache.queueMutation("CREATE_LOCATION", tempId, loc)
            val mockLoc = HBLocation(
                id = tempId,
                name = loc.name,
                description = loc.description,
                parentId = loc.parentId,
                itemCount = 0
            )
            cache.saveCachedLocations(cache.getCachedLocations() + mockLoc)
            return mockLoc
        }
    }

    suspend fun updateLocation(id: String, loc: HBLocationUpdate): HBLocation {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.updateLocation(id, loc)
            try {
                val fresh = c.listLocations()
                cache.saveCachedLocations(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: IOException) {
            cache.queueMutation("UPDATE_LOCATION", id, loc)
            val updated = cache.getCachedLocations().map {
                if (it.id == id) {
                    it.copy(name = loc.name, description = loc.description, parentId = loc.parentId)
                } else it
            }
            cache.saveCachedLocations(updated)
            return HBLocation(id = id, name = loc.name, description = loc.description, parentId = loc.parentId)
        }
    }

    suspend fun deleteLocation(id: String) {
        try {
            val c = client()
            syncPendingMutations(c)
            c.deleteLocation(id)
            try {
                val fresh = c.listLocations()
                cache.saveCachedLocations(fresh)
            } catch (_: Exception) {}
        } catch (e: IOException) {
            cache.queueMutation("DELETE_LOCATION", id, "")
            val filtered = cache.getCachedLocations().filter { it.id != id }
            cache.saveCachedLocations(filtered)
        }
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    suspend fun listTags(): List<HBTag> {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.listTags()
            cache.saveCachedTags(resp)
            return resp
        } catch (e: IOException) {
            return cache.getAppliedTags()
        }
    }

    suspend fun createTag(tag: HBTagCreate): HBTag {
        val tempId = UUID.randomUUID().toString()
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.createTag(tag)
            try {
                val fresh = c.listTags()
                cache.saveCachedTags(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: IOException) {
            cache.queueMutation("CREATE_TAG", tempId, tag)
            val mockTag = HBTag(id = tempId, name = tag.name, color = tag.color)
            cache.saveCachedTags(cache.getCachedTags() + mockTag)
            return mockTag
        }
    }

    suspend fun updateTag(id: String, tag: HBTagUpdate): HBTag {
        try {
            val c = client()
            syncPendingMutations(c)
            val resp = c.updateTag(id, tag)
            try {
                val fresh = c.listTags()
                cache.saveCachedTags(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: IOException) {
            cache.queueMutation("UPDATE_TAG", id, tag)
            val updated = cache.getCachedTags().map {
                if (it.id == id) {
                    it.copy(name = tag.name, color = tag.color)
                } else it
            }
            cache.saveCachedTags(updated)
            return HBTag(id = id, name = tag.name, color = tag.color)
        }
    }

    suspend fun deleteTag(id: String) {
        try {
            val c = client()
            syncPendingMutations(c)
            c.deleteTag(id)
            try {
                val fresh = c.listTags()
                cache.saveCachedTags(fresh)
            } catch (_: Exception) {}
        } catch (e: IOException) {
            cache.queueMutation("DELETE_TAG", id, "")
            val filtered = cache.getCachedTags().filter { it.id != id }
            cache.saveCachedTags(filtered)
        }
    }
}

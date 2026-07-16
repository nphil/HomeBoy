package com.homeboy.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.homeboy.app.api.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class HomeboxRepository(
    private val context: Context,
    private val prefs: PreferencesRepository
) {
    private val cache = LocalCacheManager(context)
    private val gson = Gson()
    private var _client: HomeboxClient? = null

    /** Serializes pending-mutation replay so parallel screen loads can't double-apply. */
    private val replayMutex = Mutex()
    private val syncingNow = AtomicBoolean(false)

    /**
     * temp id (assigned to an offline create) → real server id, learned at replay.
     * Screens may still hold entities under their temp ids until they reload; this
     * lets a follow-up edit/delete land on the real entity instead of 404ing.
     */
    private val idAliases = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun real(id: String): String = idAliases[id] ?: id

    private companion object {
        /** Max times the server may answer one queued mutation with 5xx before we drop it. */
        const val MAX_REPLAY_ATTEMPTS = 8
    }

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

    /** Seed ConnectionMonitor.pendingCount from disk; call off the main thread. */
    fun seedPendingCount() = cache.seedPendingCount()

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
        ConnectionMonitor.reportOnline()
    }

    suspend fun listGroups() = online { it.listGroups() }

    suspend fun setActiveGroup(id: String?, name: String) {
        // Best-effort: land queued offline edits under the tenant they were made in
        // before the header changes. If the server is unreachable they stay queued.
        try {
            _client?.let { c -> replayMutex.withLock { replayPendingMutationsLocked(c) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
        prefs.setTenant(id, name)
        _client?.let { it.tenant = id; syncSession(it) }
        // No cache file is tenant-scoped — wipe them so offline fallbacks can't
        // serve the previous group's data as the new group's.
        cache.clearAllCachedData()
    }

    suspend fun uploadAttachment(itemId: String, bytes: ByteArray, filename: String, primary: Boolean) =
        online(retries = 0) { it.uploadAttachment(real(itemId), bytes, filename, primary) }

    suspend fun logout() { prefs.logout(); invalidate() }

    suspend fun getMe() = online { it.getMe() }

    // -------------------------------------------------------------------------
    // Networking core: retry, status reporting, offline classification
    // -------------------------------------------------------------------------

    /**
     * Runs a server call with transient-failure retries and reports the outcome
     * to ConnectionMonitor. Queued offline mutations are replayed first so the
     * server response already reflects local edits. Throws on failure — callers
     * that can serve cached data catch transient errors and fall back.
     *
     * [retries]: extra attempts after the first, for transient failures only.
     * Creates AND deletes use 0 — retrying a create whose response was lost would
     * duplicate it, and retrying a committed-but-lost delete answers 404 (a false
     * failure); the offline queue is their retry mechanism.
     */
    private suspend fun <T> online(retries: Int = 1, block: suspend (HomeboxClient) -> T): T {
        val c = client()
        replayPendingMutations(c)
        // While known-offline don't stack retries (each can hold a full connect
        // timeout) — one attempt, then the cache fallback renders immediately.
        val attempts = if (ConnectionMonitor.state.value == ConnectionState.OFFLINE ||
            !ConnectionMonitor.networkAvailable
        ) 0 else retries
        try {
            var delayMs = 400L
            repeat(attempts) {
                try {
                    return block(c).also { ConnectionMonitor.reportOnline() }
                } catch (e: Exception) {
                    if (!e.isTransient()) throw e
                    delay(delayMs)
                    delayMs *= 3
                }
            }
            return block(c).also { ConnectionMonitor.reportOnline() }
        } catch (e: Exception) {
            if (e.isTransient()) ConnectionMonitor.reportOffline()
            throw e
        }
    }

    /** Fetch-through-cache: serve the server response and persist it, or fall back to the cached copy. */
    private suspend fun <T> cachedRead(
        key: String,
        type: java.lang.reflect.Type,
        block: suspend (HomeboxClient) -> T
    ): T {
        return try {
            val resp = online(block = block)
            cache.saveBlob(key, resp)
            resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.getBlob<T>(key, type) ?: throw e
        }
    }

    /**
     * Full sync pass: replay queued mutations, then refresh the offline caches
     * with fresh server data. Called when connectivity returns (network callback /
     * heartbeat) and from the status dialog's "Sync now". Emits a refresh tick on
     * success so every screen reloads. Returns true if the server was reachable.
     */
    suspend fun syncNow(): Boolean {
        if (!prefs.isLoggedIn()) return false
        if (!syncingNow.compareAndSet(false, true)) return false
        val wasOnline = ConnectionMonitor.state.value == ConnectionState.ONLINE
        ConnectionMonitor.syncStarted()
        return try {
            val c = client()
            replayMutex.withLock { replayPendingMutationsLocked(c) }
            coroutineScope {
                val items = async { c.listItems(pageSize = 1000).items }
                val locs = async { c.listLocations() }
                val tags = async { c.listTags() }
                cache.saveCachedItems(items.await())
                cache.saveCachedLocations(locs.await())
                cache.saveCachedTags(tags.await())
            }
            ConnectionMonitor.syncFinished(online = true)
            ConnectionMonitor.requestRefresh()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Sync didn't finish — restore the pre-sync state, don't fake ONLINE.
            ConnectionMonitor.syncFinished(online = wasOnline)
            throw e
        } catch (e: Exception) {
            // A permanent (non-transient) error still means the server answered.
            ConnectionMonitor.syncFinished(online = !e.isTransient())
            false
        } finally {
            syncingNow.set(false)
        }
    }

    // -------------------------------------------------------------------------
    // Background Synchronization
    // -------------------------------------------------------------------------

    private suspend fun replayPendingMutations(c: HomeboxClient) {
        // In-memory count check — avoids re-reading the queue file on every call.
        // Queued mutations MUST land before any new request (a fresh edit reaching
        // the server ahead of an older queued edit would be reverted at replay), so
        // this runs even while the monitor still says OFFLINE; the loop below aborts
        // on the first unreachable failure, so a dead server costs one attempt.
        if (ConnectionMonitor.pendingCount.value == 0) return
        replayMutex.withLock { replayPendingMutationsLocked(c) }
    }

    private suspend fun replayPendingMutationsLocked(client: HomeboxClient) {
        val mutations = cache.getPendingMutations()
        if (mutations.isEmpty()) return

        val idMap = mutableMapOf<String, String>()
        val remainingMutations = mutableListOf<PendingMutation>()
        var cancelled: kotlinx.coroutines.CancellationException? = null

        for ((index, m) in mutations.withIndex()) {
            val translatedTargetId = idMap[m.targetId] ?: idAliases[m.targetId] ?: m.targetId
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
                    "CREATE_MAINTENANCE" -> {
                        val payload = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                        val itemId = idMap[payload.itemId] ?: payload.itemId
                        val created = client.createMaintenance(itemId, payload.entry)
                        idMap[m.targetId] = created.id
                    }
                    "UPDATE_MAINTENANCE" -> {
                        val payload = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                        client.updateMaintenance(translatedTargetId, payload.entry)
                    }
                    "DELETE_MAINTENANCE" -> {
                        client.deleteMaintenance(translatedTargetId)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Persist progress before propagating: mutations already replayed
                    // this pass must not stay in the file and be double-applied later.
                    // The in-flight one is kept (idempotent updates re-run harmlessly).
                    cancelled = e
                    remainingMutations.add(m)
                    remainingMutations.addAll(mutations.subList(index + 1, mutations.size))
                    break
                }
                val serverChoked = e is HomeboxHttpException && e.isTransient()
                val authExpired = e is HomeboxHttpException && (e.code == 401 || e.code == 403)
                if (serverChoked && m.attempts + 1 >= MAX_REPLAY_ATTEMPTS) {
                    // Poison pill: the server keeps answering this specific mutation
                    // with 5xx — drop it so it can't wedge the queue forever.
                    e.printStackTrace()
                } else if (e.isTransient() || authExpired) {
                    // Unreachable, mid-restart, or logged-out: keep this mutation AND
                    // everything after it, in order. Replaying later mutations before
                    // earlier ones would corrupt causal order (e.g. an update that
                    // references a not-yet-created item), so abort the pass here.
                    remainingMutations.add(if (serverChoked) m.copy(attempts = m.attempts + 1) else m)
                    remainingMutations.addAll(mutations.subList(index + 1, mutations.size))
                    break
                } else {
                    // Permanently rejected (validation 4xx) → drop; it can never succeed.
                    e.printStackTrace()
                }
            }
        }

        // Remember temp→real id mappings so follow-up calls against a temp id
        // (screens may not have reloaded yet) land on the real entity.
        idAliases.putAll(idMap)

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
                        "CREATE_MAINTENANCE" -> {
                            val payload = gson.fromJson(m.payloadJson, PendingMaintenancePayload::class.java)
                            val finalPayload = payload.copy(itemId = idMap[payload.itemId] ?: payload.itemId)
                            newPayloadJson = gson.toJson(finalPayload)
                        }
                    }
                } catch (_: Exception) {}
                m.copy(targetId = newTargetId, payloadJson = newPayloadJson)
            }
            cache.savePendingMutations(updatedRemaining)
        }
        cancelled?.let { throw it }
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
            val resp = online { it.listItems(query, locationIds, labelIds, parentIds, includeArchived, page, pageSize) }
            cache.saveCachedItems(resp.items)
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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
            val resp = online { it.getItem(real(id)) }
            cache.saveCachedItemDetail(resp)
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            return cache.getAppliedItemDetail(id) ?: throw e
        }
    }

    suspend fun createItem(item: HBItemCreate): HBItemDetail {
        val tempId = UUID.randomUUID().toString()
        try {
            val c = client()
            val resp = online(retries = 0) { it.createItem(item) }
            try {
                val fresh = c.listItems()
                cache.saveCachedItems(fresh.items)
            } catch (_: Exception) {}
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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
            val resp = online { it.updateItem(real(id), item) }
            try {
                val fresh = c.listItems()
                cache.saveCachedItems(fresh.items)
            } catch (_: Exception) {}
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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
            online(retries = 0) { it.deleteItem(real(id)) }
            try {
                val fresh = c.listItems()
                cache.saveCachedItems(fresh.items)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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

    suspend fun listMaintenance(itemId: String): List<HBMaintenanceEntry> {
        return try {
            val resp = online { it.listMaintenance(real(itemId)) }
            cache.saveBlob(cache.maintenanceKey(itemId), resp)
            resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.getAppliedMaintenance(itemId)
        }
    }

    suspend fun createMaintenance(itemId: String, entry: HBMaintenanceCreate): HBMaintenanceEntry {
        return try {
            online(retries = 0) { it.createMaintenance(real(itemId), entry) }
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            val tempId = UUID.randomUUID().toString()
            cache.queueMutation("CREATE_MAINTENANCE", tempId, PendingMaintenancePayload(itemId, entry))
            entry.asEntry(tempId)
        }
    }

    suspend fun updateMaintenance(id: String, entry: HBMaintenanceCreate) {
        try {
            online { it.updateMaintenance(real(id), entry) }
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            // Editing an entry that itself was created offline: fold the edit into
            // the queued create instead of queueing an update against a temp id.
            // Under replayMutex so a concurrent sync can't replay/rewrite the queue
            // between our read and write.
            replayMutex.withLock {
                val pending = cache.getPendingMutations()
                val createIdx = pending.indexOfFirst { it.type == "CREATE_MAINTENANCE" && it.targetId == id }
                if (createIdx != -1) {
                    val old = gson.fromJson(pending[createIdx].payloadJson, PendingMaintenancePayload::class.java)
                    val updated = pending.toMutableList()
                    updated[createIdx] = pending[createIdx].copy(payloadJson = gson.toJson(old.copy(entry = entry)))
                    cache.savePendingMutations(updated)
                } else {
                    cache.queueMutation("UPDATE_MAINTENANCE", id, PendingMaintenancePayload("", entry))
                }
            }
        }
    }

    suspend fun deleteMaintenance(id: String) {
        try {
            online(retries = 0) { it.deleteMaintenance(real(id)) }
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            // Deleting an entry created offline just cancels its queued create.
            replayMutex.withLock {
                val pending = cache.getPendingMutations()
                val createIdx = pending.indexOfFirst { it.type == "CREATE_MAINTENANCE" && it.targetId == id }
                if (createIdx != -1) {
                    cache.savePendingMutations(pending.filterIndexed { i, _ -> i != createIdx })
                } else {
                    cache.queueMutation("DELETE_MAINTENANCE", id, "")
                }
            }
        }
    }

    /** Global maintenance log across all items. status = scheduled | completed | both */
    suspend fun listAllMaintenance(status: String? = "both"): List<HBMaintenanceWithDetails> {
        return try {
            val resp = online { it.listAllMaintenance(status) }
            cache.saveBlob(cache.allMaintenanceKey(status), resp)
            resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.getAppliedAllMaintenance(status)
        }
    }

    // -------------------------------------------------------------------------
    // Statistics
    // -------------------------------------------------------------------------

    suspend fun getStatistics(): HBGroupStatistics =
        cachedRead("stats_group", object : TypeToken<HBGroupStatistics>() {}.type) { it.getStatistics() }

    suspend fun getStatsByLocation(): List<HBTotalsByOrganizer> =
        cachedRead("stats_by_location", object : TypeToken<List<HBTotalsByOrganizer>>() {}.type) { it.getStatsByLocation() }

    suspend fun getStatsByTag(): List<HBTotalsByOrganizer> =
        cachedRead("stats_by_tag", object : TypeToken<List<HBTotalsByOrganizer>>() {}.type) { it.getStatsByTag() }

    // Stable cache key: callers pass a now()-anchored window, so keying on the
    // range would mean a guaranteed cache miss on every offline read.
    suspend fun getPurchasePriceOverTime(start: String?, end: String?): HBValueOverTime =
        cachedRead("stats_price", object : TypeToken<HBValueOverTime>() {}.type) {
            it.getPurchasePriceOverTime(start, end)
        }

    // -------------------------------------------------------------------------
    // Locations
    // -------------------------------------------------------------------------

    suspend fun listLocations(): List<HBLocation> {
        return try {
            val resp = online { it.listLocations() }
            cache.saveCachedLocations(resp)
            resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.getAppliedLocations()
        }
    }

    suspend fun getLocationTree(): List<HBLocationTreeItem> {
        return try {
            online { it.getLocationTree() }
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.buildLocationTree(cache.getAppliedLocations())
        }
    }

    suspend fun createLocation(loc: HBLocationCreate): HBLocation {
        val tempId = UUID.randomUUID().toString()
        try {
            val c = client()
            val resp = online(retries = 0) { it.createLocation(loc) }
            try {
                val fresh = c.listLocations()
                cache.saveCachedLocations(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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
            val resp = online { it.updateLocation(real(id), loc) }
            try {
                val fresh = c.listLocations()
                cache.saveCachedLocations(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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
            online(retries = 0) { it.deleteLocation(real(id)) }
            try {
                val fresh = c.listLocations()
                cache.saveCachedLocations(fresh)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.queueMutation("DELETE_LOCATION", id, "")
            val filtered = cache.getCachedLocations().filter { it.id != id }
            cache.saveCachedLocations(filtered)
        }
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    suspend fun listTags(): List<HBTag> {
        return try {
            val resp = online { it.listTags() }
            cache.saveCachedTags(resp)
            resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.getAppliedTags()
        }
    }

    /** Tag hierarchy built from the flat list (no server tree endpoint for tags). */
    suspend fun getTagTree(): List<HBTagTreeItem> = buildTagTree(listTags())

    suspend fun createTag(tag: HBTagCreate): HBTag {
        val tempId = UUID.randomUUID().toString()
        try {
            val c = client()
            val resp = online(retries = 0) { it.createTag(tag) }
            try {
                val fresh = c.listTags()
                cache.saveCachedTags(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.queueMutation("CREATE_TAG", tempId, tag)
            val mockTag = HBTag(id = tempId, name = tag.name, color = tag.color)
            cache.saveCachedTags(cache.getCachedTags() + mockTag)
            return mockTag
        }
    }

    suspend fun updateTag(id: String, tag: HBTagUpdate): HBTag {
        try {
            val c = client()
            val resp = online { it.updateTag(real(id), tag) }
            try {
                val fresh = c.listTags()
                cache.saveCachedTags(fresh)
            } catch (_: Exception) {}
            return resp
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
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
            online(retries = 0) { it.deleteTag(real(id)) }
            try {
                val fresh = c.listTags()
                cache.saveCachedTags(fresh)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            if (!e.isTransient()) throw e
            cache.queueMutation("DELETE_TAG", id, "")
            val filtered = cache.getCachedTags().filter { it.id != id }
            cache.saveCachedTags(filtered)
        }
    }
}

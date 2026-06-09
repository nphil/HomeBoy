package com.homeboy.app.data

import com.homeboy.app.api.*

class HomeboxRepository(private val prefs: PreferencesRepository) {

    private var _client: HomeboxClient? = null

    private suspend fun client(): HomeboxClient {
        val token = prefs.getToken()
        val url = prefs.getServerUrl()
        val existing = _client
        if (existing != null && token.isNotBlank()) {
            existing.token = token
            existing.tenant = prefs.getTenant()
            return existing
        }
        val c = HomeboxClient(url)
        c.token = token
        c.tenant = prefs.getTenant()
        _client = c
        return c
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
    }

    suspend fun logout() { prefs.logout(); invalidate() }

    suspend fun getMe() = client().getMe()

    suspend fun listItems(
        query: String? = null,
        locationIds: List<String> = emptyList(),
        labelIds: List<String> = emptyList(),
        parentIds: List<String> = emptyList(),
        includeArchived: Boolean = false,
        page: Int = 1,
        pageSize: Int = 500
    ) = client().listItems(query, locationIds, labelIds, parentIds, includeArchived, page, pageSize)

    suspend fun getItem(id: String) = client().getItem(id)
    suspend fun createItem(item: HBItemCreate) = client().createItem(item)
    suspend fun updateItem(id: String, item: HBItemUpdate) = client().updateItem(id, item)
    suspend fun deleteItem(id: String) = client().deleteItem(id)

    suspend fun listMaintenance(itemId: String) = client().listMaintenance(itemId)
    suspend fun createMaintenance(itemId: String, entry: HBMaintenanceCreate) = client().createMaintenance(itemId, entry)
    suspend fun updateMaintenance(id: String, entry: HBMaintenanceCreate) = client().updateMaintenance(id, entry)
    suspend fun deleteMaintenance(id: String) = client().deleteMaintenance(id)

    suspend fun listLocations() = client().listLocations()
    suspend fun getLocationTree() = client().getLocationTree()
    suspend fun createLocation(loc: HBLocationCreate) = client().createLocation(loc)
    suspend fun updateLocation(id: String, loc: HBLocationUpdate) = client().updateLocation(id, loc)
    suspend fun deleteLocation(id: String) = client().deleteLocation(id)

    suspend fun listTags() = client().listTags()
    suspend fun createTag(tag: HBTagCreate) = client().createTag(tag)
    suspend fun updateTag(id: String, tag: HBTagUpdate) = client().updateTag(id, tag)
    suspend fun deleteTag(id: String) = client().deleteTag(id)
}

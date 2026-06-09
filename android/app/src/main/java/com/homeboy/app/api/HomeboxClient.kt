package com.homeboy.app.api

import com.google.gson.GsonBuilder
import com.homeboy.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class HomeboxClient(baseUrl: String) {

    private val gson = GsonBuilder().create()

    /** Normalized base, e.g. "http://host:3100/api/". Public for image loading. */
    val apiBase: String = normalizeBase(baseUrl)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(apiBase)
        .client(http)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api = retrofit.create(HomeboxApiService::class.java)

    // Set after successful login
    var token: String = ""
    var tenant: String? = null

    // Raw token — no "Bearer " prefix per Homebox API spec
    private fun auth() = token

    /** Full URL for an item attachment (used by Coil to load thumbnails/photos). */
    fun attachmentUrl(itemId: String, attachmentId: String): String =
        "${apiBase}v1/items/$itemId/attachments/$attachmentId"

    private companion object {
        fun normalizeBase(url: String): String {
            val base = url.trimEnd('/')
            return "$base/api/"
        }
    }

    suspend fun login(email: String, password: String): HBAuthResponse {
        val resp = api.login(email, password)
        if (!resp.isSuccessful) throw Exception("Login failed (${resp.code()}): ${resp.errorBody()?.string()}")
        return resp.body() ?: throw Exception("Empty login response")
    }

    suspend fun getMe(): HBUserInfo {
        val resp = api.getMe(auth())
        if (!resp.isSuccessful) throw Exception("GetMe failed: ${resp.code()}")
        return resp.body() ?: HBUserInfo()
    }

    suspend fun listItems(
        query: String? = null,
        locationIds: List<String> = emptyList(),
        labelIds: List<String> = emptyList(),
        parentIds: List<String> = emptyList(),
        includeArchived: Boolean = false,
        page: Int = 1,
        pageSize: Int = 500
    ): HBItemListResponse {
        val resp = api.listItems(
            token = auth(),
            tenant = tenant,
            query = query?.takeIf { it.isNotBlank() },
            locationIds = locationIds.takeIf { it.isNotEmpty() },
            labelIds = labelIds.takeIf { it.isNotEmpty() },
            parentIds = parentIds.takeIf { it.isNotEmpty() },
            includeArchived = if (includeArchived) true else null,
            page = page,
            pageSize = pageSize
        )
        if (!resp.isSuccessful) throw Exception("listItems failed: ${resp.code()}")
        return resp.body() ?: HBItemListResponse()
    }

    suspend fun getItem(id: String): HBItemDetail {
        val resp = api.getItem(auth(), tenant, id)
        if (!resp.isSuccessful) throw Exception("getItem failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty item response")
    }

    suspend fun createItem(item: HBItemCreate): HBItemDetail {
        val resp = api.createItem(auth(), tenant, item)
        if (!resp.isSuccessful) throw Exception("createItem failed: ${resp.code()} ${resp.errorBody()?.string()}")
        return resp.body() ?: throw Exception("Empty create response")
    }

    suspend fun updateItem(id: String, item: HBItemUpdate): HBItemDetail {
        val resp = api.updateItem(auth(), tenant, id, item)
        if (!resp.isSuccessful) throw Exception("updateItem failed: ${resp.code()} ${resp.errorBody()?.string()}")
        return resp.body() ?: throw Exception("Empty update response")
    }

    suspend fun deleteItem(id: String) {
        val resp = api.deleteItem(auth(), tenant, id)
        if (!resp.isSuccessful) throw Exception("deleteItem failed: ${resp.code()}")
    }

    suspend fun listMaintenance(itemId: String): List<HBMaintenanceEntry> {
        val resp = api.listMaintenance(auth(), tenant, itemId)
        if (!resp.isSuccessful) throw Exception("listMaintenance failed: ${resp.code()}")
        return resp.body() ?: emptyList()
    }

    suspend fun createMaintenance(itemId: String, entry: HBMaintenanceCreate): HBMaintenanceEntry {
        val resp = api.createMaintenance(auth(), tenant, itemId, entry)
        if (!resp.isSuccessful) throw Exception("createMaintenance failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty maintenance response")
    }

    suspend fun updateMaintenance(id: String, entry: HBMaintenanceCreate) {
        val resp = api.updateMaintenance(auth(), tenant, id, entry)
        if (!resp.isSuccessful) throw Exception("updateMaintenance failed: ${resp.code()}")
    }

    suspend fun deleteMaintenance(id: String) {
        val resp = api.deleteMaintenance(auth(), tenant, id)
        if (!resp.isSuccessful) throw Exception("deleteMaintenance failed: ${resp.code()}")
    }

    suspend fun listLocations(): List<HBLocation> {
        val resp = api.listLocations(auth(), tenant)
        if (!resp.isSuccessful) throw Exception("listLocations failed: ${resp.code()}")
        return resp.body() ?: emptyList()
    }

    suspend fun getLocationTree(): List<HBLocationTreeItem> {
        val resp = api.getLocationTree(auth(), tenant)
        if (!resp.isSuccessful) throw Exception("getLocationTree failed: ${resp.code()}")
        return resp.body() ?: emptyList()
    }

    suspend fun createLocation(location: HBLocationCreate): HBLocation {
        val resp = api.createLocation(auth(), tenant, location)
        if (!resp.isSuccessful) throw Exception("createLocation failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty location response")
    }

    suspend fun updateLocation(id: String, location: HBLocationUpdate): HBLocation {
        val resp = api.updateLocation(auth(), tenant, id, location)
        if (!resp.isSuccessful) throw Exception("updateLocation failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty location response")
    }

    suspend fun deleteLocation(id: String) {
        val resp = api.deleteLocation(auth(), tenant, id)
        if (!resp.isSuccessful) throw Exception("deleteLocation failed: ${resp.code()}")
    }

    suspend fun listTags(): List<HBTag> {
        val resp = api.listTags(auth(), tenant)
        if (!resp.isSuccessful) throw Exception("listTags failed: ${resp.code()}")
        val body = resp.body() ?: return emptyList()
        return if (body.isJsonArray) {
            gson.fromJson(body.asJsonArray, Array<HBTag>::class.java).toList()
        } else {
            val items = body.asJsonObject.getAsJsonArray("items") ?: return emptyList()
            gson.fromJson(items, Array<HBTag>::class.java).toList()
        }
    }

    suspend fun createTag(tag: HBTagCreate): HBTag {
        val resp = api.createTag(auth(), tenant, tag)
        if (!resp.isSuccessful) throw Exception("createTag failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty tag response")
    }

    suspend fun updateTag(id: String, tag: HBTagUpdate): HBTag {
        val resp = api.updateTag(auth(), tenant, id, tag)
        if (!resp.isSuccessful) throw Exception("updateTag failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty tag response")
    }

    suspend fun deleteTag(id: String) {
        val resp = api.deleteTag(auth(), tenant, id)
        if (!resp.isSuccessful) throw Exception("deleteTag failed: ${resp.code()}")
    }

    suspend fun listGroups(): List<HBGroup> {
        val resp = api.listGroups(auth(), tenant)
        if (!resp.isSuccessful) throw Exception("listGroups failed: ${resp.code()}")
        val body = resp.body() ?: return emptyList()
        val arr = when {
            body.isJsonArray -> body.asJsonArray
            body.isJsonObject -> body.asJsonObject.getAsJsonArray("items") ?: return emptyList()
            else -> return emptyList()
        }
        return gson.fromJson(arr, Array<HBGroup>::class.java).toList()
    }

    /** Upload a photo to an item. [bytes] is the raw JPEG/PNG content. */
    suspend fun uploadAttachment(
        itemId: String,
        bytes: ByteArray,
        filename: String,
        primary: Boolean
    ): HBAttachment {
        val fileBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", filename, fileBody)
        val typePart = "photo".toRequestBody("text/plain".toMediaTypeOrNull())
        val resp = api.uploadAttachment(auth(), tenant, itemId, filePart, typePart)
        if (!resp.isSuccessful) throw Exception("uploadAttachment failed: ${resp.code()}")
        return resp.body() ?: throw Exception("Empty attachment response")
    }
}

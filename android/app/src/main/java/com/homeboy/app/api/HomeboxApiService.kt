package com.homeboy.app.api

import com.google.gson.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Homebox v0.25.x+ REST surface.
 *
 * NOTE: Homebox unified items + locations under a single `/v1/entities` resource.
 * Items are `isLocation=false`, locations are `isLocation=true`. Item/location
 * placement (location, sub-item parent, sub-location parent) all go through
 * `parentId`. Entity types come from `/v1/entity-types`.
 */
interface HomeboxApiService {

    // Auth — must be form-urlencoded, NOT JSON
    @FormUrlEncoded
    @POST("v1/users/login")
    suspend fun login(
        @Field("username") email: String,
        @Field("password") password: String
    ): Response<HBAuthResponse>

    @GET("v1/users/self")
    suspend fun getMe(
        @Header("Authorization") token: String
    ): Response<HBUserSelfResponse>

    // Entity types (used to create locations — need the location typeId)
    @GET("v1/entity-types")
    suspend fun listEntityTypes(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?
    ): Response<JsonElement>

    // Items (entities with isLocation=false)
    @GET("v1/entities")
    suspend fun listItems(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Query("q") query: String?,
        @Query("parentIds") parentIds: List<String>?,
        @Query("tags") labelIds: List<String>?,
        @Query("includeArchived") includeArchived: Boolean?,
        @Query("isLocation") isLocation: Boolean,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): Response<HBItemListResponse>

    @GET("v1/entities/{id}")
    suspend fun getItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<HBItemDetail>

    @POST("v1/entities")
    suspend fun createItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Body item: HBItemCreate
    ): Response<HBItemDetail>

    @PUT("v1/entities/{id}")
    suspend fun updateItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body item: HBItemUpdate
    ): Response<HBItemDetail>

    @DELETE("v1/entities/{id}")
    suspend fun deleteItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>

    @Multipart
    @POST("v1/entities/{id}/attachments")
    suspend fun uploadAttachment(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Part file: MultipartBody.Part,
        @Part("type") type: RequestBody,
        @Part("primary") primary: RequestBody
    ): Response<HBAttachment>

    @DELETE("v1/entities/{id}/attachments/{attachId}")
    suspend fun deleteAttachment(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Path("attachId") attachId: String
    ): Response<Unit>

    // Maintenance
    @GET("v1/entities/{id}/maintenance")
    suspend fun listMaintenance(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<List<HBMaintenanceEntry>>

    @POST("v1/entities/{id}/maintenance")
    suspend fun createMaintenance(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body entry: HBMaintenanceCreate
    ): Response<HBMaintenanceEntry>

    @PUT("v1/maintenance/{id}")
    suspend fun updateMaintenance(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body entry: HBMaintenanceCreate
    ): Response<Unit>

    @DELETE("v1/maintenance/{id}")
    suspend fun deleteMaintenance(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>

    // Locations (entities with isLocation=true)
    @GET("v1/entities")
    suspend fun listLocations(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Query("isLocation") isLocation: Boolean,
        @Query("pageSize") pageSize: Int
    ): Response<HBLocationListResponse>

    @GET("v1/entities/tree")
    suspend fun getLocationTree(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Query("withItems") withItems: Boolean
    ): Response<List<HBLocationTreeItem>>

    @POST("v1/entities")
    suspend fun createLocation(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Body location: HBLocationCreate
    ): Response<HBLocation>

    @PUT("v1/entities/{id}")
    suspend fun updateLocation(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body location: HBLocationUpdate
    ): Response<HBLocation>

    @DELETE("v1/entities/{id}")
    suspend fun deleteLocation(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>

    // Tags — Homebox resource is /v1/tags. Returns [HBTag] OR { items: [HBTag] }, handled in client.
    @GET("v1/tags")
    suspend fun listTags(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?
    ): Response<JsonElement>

    @POST("v1/tags")
    suspend fun createTag(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Body tag: HBTagCreate
    ): Response<HBTag>

    @PUT("v1/tags/{id}")
    suspend fun updateTag(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body tag: HBTagUpdate
    ): Response<HBTag>

    @DELETE("v1/tags/{id}")
    suspend fun deleteTag(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>

    // Groups / collections (for X-Tenant switching)
    @GET("v1/groups/all")
    suspend fun listGroups(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?
    ): Response<JsonElement>
}

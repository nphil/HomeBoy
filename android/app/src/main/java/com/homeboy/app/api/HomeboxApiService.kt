package com.homeboy.app.api

import com.google.gson.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface HomeboxApiService {

    // Auth — must be form-urlencoded, NOT JSON
    @FormUrlEncoded
    @POST("v1/users/login")
    suspend fun login(
        @Field("username") email: String,
        @Field("password") password: String
    ): Response<HBAuthResponse>

    @GET("v1/users/me")
    suspend fun getMe(
        @Header("Authorization") token: String
    ): Response<HBUserInfo>

    // Items
    @GET("v1/items")
    suspend fun listItems(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Query("q") query: String?,
        @Query("locations") locationIds: List<String>?,
        @Query("labels") labelIds: List<String>?,
        @Query("parentIds") parentIds: List<String>?,
        @Query("includeArchived") includeArchived: Boolean?,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): Response<HBItemListResponse>

    @GET("v1/items/{id}")
    suspend fun getItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<HBItemDetail>

    @POST("v1/items")
    suspend fun createItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Body item: HBItemCreate
    ): Response<HBItemDetail>

    @PUT("v1/items/{id}")
    suspend fun updateItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body item: HBItemUpdate
    ): Response<HBItemDetail>

    @DELETE("v1/items/{id}")
    suspend fun deleteItem(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>

    @Multipart
    @POST("v1/items/{id}/attachments")
    suspend fun uploadAttachment(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Part file: MultipartBody.Part,
        @Part("type") type: RequestBody
    ): Response<HBAttachment>

    @DELETE("v1/items/{id}/attachments/{attachId}")
    suspend fun deleteAttachment(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Path("attachId") attachId: String
    ): Response<Unit>

    // Maintenance
    @GET("v1/items/{id}/maintenance")
    suspend fun listMaintenance(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<List<HBMaintenanceEntry>>

    @POST("v1/items/{id}/maintenance")
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

    // Locations
    @GET("v1/locations")
    suspend fun listLocations(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?
    ): Response<List<HBLocation>>

    @GET("v1/locations/tree")
    suspend fun getLocationTree(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?
    ): Response<List<HBLocationTreeItem>>

    @POST("v1/locations")
    suspend fun createLocation(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Body location: HBLocationCreate
    ): Response<HBLocation>

    @PUT("v1/locations/{id}")
    suspend fun updateLocation(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body location: HBLocationUpdate
    ): Response<HBLocation>

    @DELETE("v1/locations/{id}")
    suspend fun deleteLocation(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>

    // Tags (labels) — returns [HBTag] OR { items: [HBTag] }, handled in client
    @GET("v1/labels")
    suspend fun listTags(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?
    ): Response<JsonElement>

    @POST("v1/labels")
    suspend fun createTag(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Body tag: HBTagCreate
    ): Response<HBTag>

    @PUT("v1/labels/{id}")
    suspend fun updateTag(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String,
        @Body tag: HBTagUpdate
    ): Response<HBTag>

    @DELETE("v1/labels/{id}")
    suspend fun deleteTag(
        @Header("Authorization") token: String,
        @Header("X-Tenant") tenant: String?,
        @Path("id") id: String
    ): Response<Unit>
}

package com.homeboy.app.api

import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

data class HBAuthResponse(
    val token: String = ""
)

data class HBUserInfo(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val groupName: String = ""
)

/** Homebox wraps `GET /v1/users/self` in `{ "item": { ... } }`. */
data class HBUserSelfResponse(
    val item: HBUserInfo = HBUserInfo()
)

data class HBItemLabel(
    val id: String,
    val name: String,
    val color: String? = null
)

data class HBItemLocation(
    val id: String,
    val name: String
)

data class HBItemSummary(
    val id: String,
    val name: String
)

data class HBItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val quantity: Int = 1,
    val archived: Boolean = false,
    val location: HBItemLocation? = null,
    val parent: HBItemLocation? = null,
    val labels: List<HBItemLabel>? = null,
    val tags: List<HBItemLabel>? = null,
    val imageId: String? = null,
    val thumbnailId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val effectiveLabels: List<HBItemLabel> get() = labels ?: tags ?: emptyList()
    /** Entities API returns placement under `parent`; older payloads used `location`. */
    val effectiveLocation: HBItemLocation? get() = location ?: parent
    /** Attachment id to use for the list thumbnail, if Homebox provided one. */
    val previewAttachmentId: String? get() = thumbnailId ?: imageId
}

data class HBGroup(
    val id: String,
    val name: String,
    val description: String? = null
)

data class HBAttachment(
    val id: String,
    val fileName: String? = null,
    val type: String? = null,
    val document: HBAttachmentDoc? = null,
    val createdAt: String? = null
)

data class HBAttachmentDoc(
    val id: String,
    val title: String? = null,
    val path: String? = null
)

data class HBItemDetail(
    val id: String,
    val name: String,
    val description: String? = null,
    val quantity: Int = 1,
    val archived: Boolean = false,
    val insured: Boolean = false,
    val notes: String? = null,
    val serialNumber: String? = null,
    val modelNumber: String? = null,
    val manufacturer: String? = null,
    val warrantyExpires: String? = null,
    val warrantyDetails: String? = null,
    val purchasePrice: Double? = null,
    val purchaseFrom: String? = null,
    val purchaseDate: String? = null,
    val soldTo: String? = null,
    val soldPrice: Double? = null,
    val soldDate: String? = null,
    val soldNotes: String? = null,
    val lifetimeWarranty: Boolean = false,
    val assetId: String? = null,
    val location: HBItemLocation? = null,
    val labels: List<HBItemLabel>? = null,
    val tags: List<HBItemLabel>? = null,
    val parent: HBItemSummary? = null,
    val syncChildEntityLocations: Boolean = false,
    val attachments: List<HBAttachment>? = null,
    val fields: List<HBEntityField>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val effectiveLabels: List<HBItemLabel> get() = labels ?: tags ?: emptyList()
    /** Entities API returns placement under `parent`; older payloads used `location`. */
    val effectiveLocation: HBItemLocation? get() = location
        ?: parent?.let { HBItemLocation(it.id, it.name) }
}

data class HBItemListResponse(
    val items: List<HBItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 500
)

data class HBItemCreate(
    val name: String,
    val description: String = "",
    val quantity: Int = 1,
    val parentId: String? = null,  // location OR sub-item parent; never send ""
    val tagIds: List<String> = emptyList()
)

data class HBItemUpdate(
    val name: String,
    val description: String = "",
    val quantity: Int = 1,
    val archived: Boolean = false,
    val insured: Boolean = false,
    val notes: String = "",
    val serialNumber: String = "",
    val modelNumber: String = "",
    val manufacturer: String = "",
    val assetId: String = "0",
    val lifetimeWarranty: Boolean = false,
    val warrantyExpires: String = "",
    val warrantyDetails: String = "",
    val purchasePrice: Double = 0.0,
    val purchaseFrom: String = "",
    val purchaseDate: String = "",
    val soldTo: String = "",
    val soldPrice: Double = 0.0,
    val soldDate: String = "",
    val soldNotes: String = "",
    val parentId: String? = null,  // location OR sub-item parent; never send ""
    val tagIds: List<String> = emptyList(),
    val fields: List<HBEntityField> = emptyList(),
    val syncChildEntityLocations: Boolean = false
) {
    companion object {
        fun from(detail: HBItemDetail) = HBItemUpdate(
            name = detail.name,
            description = detail.description ?: "",
            quantity = detail.quantity,
            archived = detail.archived,
            insured = detail.insured,
            notes = detail.notes ?: "",
            serialNumber = detail.serialNumber ?: "",
            modelNumber = detail.modelNumber ?: "",
            manufacturer = detail.manufacturer ?: "",
            assetId = detail.assetId ?: "0",
            lifetimeWarranty = detail.lifetimeWarranty,
            warrantyExpires = detail.warrantyExpires ?: "",
            warrantyDetails = detail.warrantyDetails ?: "",
            purchasePrice = detail.purchasePrice ?: 0.0,
            purchaseFrom = detail.purchaseFrom ?: "",
            purchaseDate = detail.purchaseDate ?: "",
            soldTo = detail.soldTo ?: "",
            soldPrice = detail.soldPrice ?: 0.0,
            soldDate = detail.soldDate ?: "",
            soldNotes = detail.soldNotes ?: "",
            parentId = detail.effectiveLocation?.id,
            tagIds = detail.effectiveLabels.map { it.id },
            fields = detail.fields ?: emptyList(),
            syncChildEntityLocations = detail.syncChildEntityLocations
        )
    }
}

/**
 * Custom field on an entity (`fields` array on EntityOut/EntityUpdate).
 * `type` is one of: text, number, boolean. `id` is omitted when creating new ones.
 */
data class HBEntityField(
    val id: String? = null,
    val type: String = "text",
    val name: String = "",
    val textValue: String = "",
    val numberValue: Int = 0,
    val booleanValue: Boolean = false
) {
    /** Human-readable value for display, by type. */
    val displayValue: String
        get() = when (type) {
            "boolean" -> if (booleanValue) "Yes" else "No"
            "number" -> numberValue.toString()
            else -> textValue
        }
}

/** Entity type record from `/v1/entity-types`. Locations have isLocation=true. */
data class HBEntityType(
    val id: String,
    val name: String,
    val isLocation: Boolean = false
)

data class HBLocation(
    val id: String,
    val name: String,
    val description: String? = null,
    val parentId: String? = null,
    val itemCount: Int = 0
)

/** `GET /v1/entities?isLocation=true` returns a paginated envelope. */
data class HBLocationListResponse(
    val items: List<HBLocation> = emptyList(),
    val total: Int = 0
)

data class HBLocationCreate(
    val name: String,
    val description: String = "",
    val parentId: String? = null,
    val entityTypeId: String? = null
)

data class HBLocationUpdate(
    val name: String,
    val description: String = "",
    val parentId: String? = null
)

data class HBLocationTreeItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val children: List<HBLocationTreeItem> = emptyList(),
    val itemCount: Int = 0
)

data class HBTag(
    val id: String,
    val name: String,
    val color: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

class HBTagListDeserializer : JsonDeserializer<List<HBTag>> {
    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): List<HBTag> {
        val arr: JsonArray = if (json.isJsonArray) {
            json.asJsonArray
        } else {
            json.asJsonObject.getAsJsonArray("items") ?: JsonArray()
        }
        return arr.map { ctx.deserialize(it, HBTag::class.java) }
    }
}

data class HBTagCreate(
    val name: String,
    val description: String = "",
    val color: String = "#6366f1"
)

data class HBTagUpdate(
    val name: String,
    val description: String = "",
    val color: String = "#6366f1"
)

data class HBMaintenanceEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    // v0.25.x returns `date`; v0.26+ renamed it `completedDate`. Accept both.
    @SerializedName(value = "date", alternate = ["completedDate"]) val date: String? = null,
    val scheduledDate: String? = null,
    @SerializedName("cost") val cost: String? = null,  // server encodes cost as string
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val costDouble: Double get() = cost?.toDoubleOrNull() ?: 0.0
    val isCompleted: Boolean get() = !date.isNullOrBlank() && !date.startsWith("0001")
    val isScheduled: Boolean get() = !scheduledDate.isNullOrBlank() && !scheduledDate.startsWith("0001")
}

/** Global maintenance log (`GET /v1/maintenance`) — entries enriched with their item. */
data class HBMaintenanceWithDetails(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerializedName(value = "date", alternate = ["completedDate"]) val date: String? = null,
    val scheduledDate: String? = null,
    @SerializedName("cost") val cost: String? = null,
    @SerializedName(value = "itemID", alternate = ["itemId"]) val itemId: String? = null,
    val itemName: String? = null
) {
    val costDouble: Double get() = cost?.toDoubleOrNull() ?: 0.0
    val isCompleted: Boolean get() = !date.isNullOrBlank() && !date.startsWith("0001")
    val isScheduled: Boolean get() = !scheduledDate.isNullOrBlank() && !scheduledDate.startsWith("0001")
    fun toEntry() = HBMaintenanceEntry(id, name, description, date, scheduledDate, cost)
}

data class HBMaintenanceCreate(
    val name: String,
    val description: String = "",
    val date: String = "",           // "" = not completed
    val scheduledDate: String = "",  // "" = no schedule
    @SerializedName("cost") val cost: String = "0"  // must be JSON string per API
)

// ---------------------------------------------------------------------------
// Statistics (GET /v1/groups/statistics*)
// ---------------------------------------------------------------------------

data class HBGroupStatistics(
    val totalUsers: Int = 0,
    val totalItems: Int = 0,
    val totalLocations: Int = 0,
    val totalTags: Int = 0,
    val totalItemPrice: Double = 0.0,
    val totalWithWarranty: Int = 0
)

/** One row of the by-location / by-tag breakdowns. `total` is a value sum. */
data class HBTotalsByOrganizer(
    val id: String = "",
    val name: String = "",
    val total: Double = 0.0
)

data class HBValueOverTime(
    val valueAtStart: Double = 0.0,
    val valueAtEnd: Double = 0.0,
    val start: String? = null,
    val end: String? = null,
    val entries: List<HBValueOverTimeEntry> = emptyList()
)

data class HBValueOverTimeEntry(
    val date: String? = null,
    val value: Double = 0.0,
    val name: String? = null
)

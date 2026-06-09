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
    val labels: List<HBItemLabel>? = null,
    val tags: List<HBItemLabel>? = null,
    val imageUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val effectiveLabels: List<HBItemLabel> get() = labels ?: tags ?: emptyList()
}

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
    val purchaseTime: String? = null,
    val soldTo: String? = null,
    val soldPrice: Double? = null,
    val soldTime: String? = null,
    val soldNotes: String? = null,
    val location: HBItemLocation? = null,
    val labels: List<HBItemLabel>? = null,
    val tags: List<HBItemLabel>? = null,
    val parent: HBItemSummary? = null,
    val attachments: List<HBAttachment>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val effectiveLabels: List<HBItemLabel> get() = labels ?: tags ?: emptyList()
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
    val locationId: String? = null,
    val labelIds: List<String> = emptyList(),
    val parentId: String? = null  // never send ""
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
    val warrantyExpires: String = "",
    val warrantyDetails: String = "",
    val purchasePrice: Double = 0.0,
    val purchaseFrom: String = "",
    val purchaseTime: String = "",
    val soldTo: String = "",
    val soldPrice: Double = 0.0,
    val soldTime: String = "",
    val soldNotes: String = "",
    val locationId: String? = null,
    val labelIds: List<String> = emptyList(),
    val parentId: String? = null  // never send ""
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
            warrantyExpires = detail.warrantyExpires ?: "",
            warrantyDetails = detail.warrantyDetails ?: "",
            purchasePrice = detail.purchasePrice ?: 0.0,
            purchaseFrom = detail.purchaseFrom ?: "",
            purchaseTime = detail.purchaseTime ?: "",
            soldTo = detail.soldTo ?: "",
            soldPrice = detail.soldPrice ?: 0.0,
            soldTime = detail.soldTime ?: "",
            soldNotes = detail.soldNotes ?: "",
            locationId = detail.location?.id,
            labelIds = detail.effectiveLabels.map { it.id },
            parentId = detail.parent?.id
        )
    }
}

data class HBLocation(
    val id: String,
    val name: String,
    val description: String? = null,
    val parentId: String? = null,
    val itemCount: Int = 0
)

data class HBLocationCreate(
    val name: String,
    val description: String = "",
    val parentId: String? = null
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
    val color: String = "#6366f1"
)

data class HBTagUpdate(
    val name: String,
    val color: String = "#6366f1"
)

data class HBMaintenanceEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val date: String? = null,
    val scheduledDate: String? = null,
    @SerializedName("cost") val cost: String? = null,  // server encodes cost as string
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val costDouble: Double get() = cost?.toDoubleOrNull() ?: 0.0
    val isCompleted: Boolean get() = !date.isNullOrBlank()
    val isScheduled: Boolean get() = !scheduledDate.isNullOrBlank()
}

data class HBMaintenanceCreate(
    val name: String,
    val description: String = "",
    val date: String = "",           // "" = not completed
    val scheduledDate: String = "",  // "" = no schedule
    @SerializedName("cost") val cost: String = "0"  // must be JSON string per API
)

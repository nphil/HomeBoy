package com.homeboy.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Curated set of free Material (outlined) icons users can attach to a tag.
 * The tag's `icon` field stores the lowercase key; rendering maps it back to a
 * vector. Unknown/blank keys fall back to a default label icon.
 */
val TAG_ICONS: List<Pair<String, ImageVector>> = listOf(
    "home" to Icons.Outlined.Home,
    "apartment" to Icons.Outlined.Apartment,
    "cottage" to Icons.Outlined.Cottage,
    "garage" to Icons.Outlined.Garage,
    "store" to Icons.Outlined.Store,
    "warehouse" to Icons.Outlined.Warehouse,
    "bed" to Icons.Outlined.Bed,
    "couch" to Icons.Outlined.Weekend,
    "chair" to Icons.Outlined.Chair,
    "kitchen" to Icons.Outlined.Kitchen,
    "microwave" to Icons.Outlined.Microwave,
    "bathtub" to Icons.Outlined.Bathtub,
    "star" to Icons.Outlined.Star,
    "favorite" to Icons.Outlined.Favorite,
    "bookmark" to Icons.Outlined.Bookmark,
    "flag" to Icons.Outlined.Flag,
    "label" to Icons.Outlined.Label,
    "category" to Icons.Outlined.Category,
    "box" to Icons.Outlined.Inventory2,
    "work" to Icons.Outlined.Work,
    "school" to Icons.Outlined.School,
    "book" to Icons.Outlined.Book,
    "menubook" to Icons.Outlined.MenuBook,
    "science" to Icons.Outlined.Science,
    "shopping" to Icons.Outlined.ShoppingCart,
    "gift" to Icons.Outlined.CardGiftcard,
    "cake" to Icons.Outlined.Cake,
    "food" to Icons.Outlined.Restaurant,
    "coffee" to Icons.Outlined.LocalCafe,
    "build" to Icons.Outlined.Build,
    "hardware" to Icons.Outlined.Hardware,
    "tools" to Icons.Outlined.HomeRepairService,
    "computer" to Icons.Outlined.Computer,
    "laptop" to Icons.Outlined.Laptop,
    "phone" to Icons.Outlined.Smartphone,
    "tv" to Icons.Outlined.Tv,
    "watch" to Icons.Outlined.Watch,
    "camera" to Icons.Outlined.CameraAlt,
    "headphones" to Icons.Outlined.Headphones,
    "music" to Icons.Outlined.MusicNote,
    "games" to Icons.Outlined.SportsEsports,
    "toys" to Icons.Outlined.Toys,
    "fitness" to Icons.Outlined.FitnessCenter,
    "palette" to Icons.Outlined.Palette,
    "brush" to Icons.Outlined.Brush,
    "car" to Icons.Outlined.DirectionsCar,
    "flight" to Icons.Outlined.Flight,
    "backpack" to Icons.Outlined.Backpack,
    "pets" to Icons.Outlined.Pets,
    "flower" to Icons.Outlined.LocalFlorist,
    "park" to Icons.Outlined.Park,
    "beach" to Icons.Outlined.BeachAccess,
    "cold" to Icons.Outlined.AcUnit,
    "light" to Icons.Outlined.Lightbulb,
    "power" to Icons.Outlined.Bolt,
    "key" to Icons.Outlined.Key,
    "lock" to Icons.Outlined.Lock,
    "medical" to Icons.Outlined.MedicalServices,
    "spa" to Icons.Outlined.Spa,
    "clothes" to Icons.Outlined.Checkroom,
    "diamond" to Icons.Outlined.Diamond,
    "settings" to Icons.Outlined.Settings
)

private val TAG_ICON_MAP: Map<String, ImageVector> = TAG_ICONS.toMap()

/** Resolve a stored tag icon key to a vector, or null if unset/unknown. */
fun tagIcon(key: String?): ImageVector? =
    key?.takeIf { it.isNotBlank() }?.let { TAG_ICON_MAP[it] }

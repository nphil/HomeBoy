package com.homeboy.app.data

/**
 * In-memory snapshot of the current session, readable synchronously from
 * OkHttp interceptors (e.g. Coil image loading). Updated by HomeboxRepository.
 */
object SessionHolder {
    @Volatile var apiBase: String = ""   // e.g. "http://host:3100/api/"
    @Volatile var token: String = ""
    @Volatile var tenant: String? = null

    fun attachmentUrl(itemId: String, attachmentId: String): String =
        "${apiBase}v1/entities/$itemId/attachments/$attachmentId"
}

package net.yapson.mobile.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Notifications push via ntfy.sh (alertes d'échec d'auto-dépôt). */
object Ntfy {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS).build()

    fun push(topic: String, title: String, message: String) {
        val t = topic.trim()
        if (t.isBlank()) return
        try {
            val safeTitle = title.replace(Regex("[^\\x20-\\x7E]"), "").take(120) // en-tête ASCII
            val req = Request.Builder()
                .url("https://ntfy.sh/$t")
                .header("Title", safeTitle)
                .header("Priority", "high")
                .post(message.toRequestBody())
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {}
    }
}

package net.yapson.mobile.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.yapson.mobile.model.Operation
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "YapsonApi"
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var baseUrl: String = ""
    private var deviceToken: String = ""

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun configure(url: String, token: String) {
        baseUrl = url.trimEnd('/')
        deviceToken = token
    }

    // ─── Récupérer les opérations PENDING ───────────────────────────
    fun getPendingOperations(): List<Operation> {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/android/pending")
                .header("x-device-token", deviceToken)
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val type = object : TypeToken<List<Operation>>() {}.type
            gson.fromJson(body, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getPending error: ${e.message}")
            emptyList()
        }
    }

    // ─── Prendre en charge une opération ────────────────────────────
    fun takeOperation(operationId: String): Operation? {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/android/take/$operationId")
                .header("x-device-token", deviceToken)
                .post("{}".toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            gson.fromJson(body, Operation::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "takeOp error: ${e.message}")
            null
        }
    }

    // ─── Envoyer un SMS reçu ────────────────────────────────────────
    fun sendSms(operationId: String?, message: String, sender: String): Boolean {
        return try {
            val payload = mapOf(
                "operationId" to (operationId ?: ""),
                "message" to message,
                "sender" to sender,
                "receivedAt" to System.currentTimeMillis()
            )
            val req = Request.Builder()
                .url("$baseUrl/api/android/sms")
                .header("x-device-token", deviceToken)
                .post(gson.toJson(payload).toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "sendSms error: ${e.message}")
            false
        }
    }

    // ─── Envoyer une notification Wave ──────────────────────────────
    fun sendNotification(operationId: String?, packageName: String, title: String, text: String): Boolean {
        return try {
            val payload = mapOf(
                "operationId" to (operationId ?: ""),
                "packageName" to packageName,
                "title" to title,
                "text" to text,
                "receivedAt" to System.currentTimeMillis()
            )
            val req = Request.Builder()
                .url("$baseUrl/api/android/notification")
                .header("x-device-token", deviceToken)
                .post(gson.toJson(payload).toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "sendNotif error: ${e.message}")
            false
        }
    }

    // ─── Remonter le résultat d'une opération USSD ──────────────────
    fun report(operationId: String, success: Boolean, finalText: String, error: String?, operatorRef: String? = null): Boolean {
        return try {
            val payload = mapOf(
                "operationId" to operationId,
                "success" to success,
                "finalText" to finalText,
                "error" to (error ?: ""),
                "operatorRef" to (operatorRef ?: ""),
                "reportedAt" to System.currentTimeMillis()
            )
            val req = Request.Builder()
                .url("$baseUrl/api/android/report")
                .header("x-device-token", deviceToken)
                .post(gson.toJson(payload).toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "report error: ${e.message}")
            false
        }
    }

    // ─── Auto-dépôt : lire la config ────────────────────────────────
    fun getAutoConfig(): net.yapson.mobile.model.AutoConfig? {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/android/auto-config")
                .header("x-device-token", deviceToken)
                .get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            gson.fromJson(body, net.yapson.mobile.model.AutoConfig::class.java)
        } catch (e: Exception) { Log.e(TAG, "autoConfig err: ${e.message}"); null }
    }

    // ─── Auto-dépôt : créer une opération de dépôt côté serveur ──────
    fun createAutoDepot(amount: Int): Operation? {
        return try {
            val payload = mapOf("amount" to amount)
            val req = Request.Builder()
                .url("$baseUrl/api/android/auto-depot")
                .header("x-device-token", deviceToken)
                .post(gson.toJson(payload).toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { Log.e(TAG, "autoDepot http ${resp.code}"); return null }
            val body = resp.body?.string() ?: return null
            gson.fromJson(body, Operation::class.java)
        } catch (e: Exception) { Log.e(TAG, "autoDepot err: ${e.message}"); null }
    }

    // ─── Heartbeat ──────────────────────────────────────────────────
    fun heartbeat(): Boolean {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/android/heartbeat")
                .header("x-device-token", deviceToken)
                .post("{}".toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}

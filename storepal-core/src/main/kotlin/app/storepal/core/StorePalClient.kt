package app.storepal.core

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Coroutine-based client; no Android UI dependency. Never use an sp_user_ management token in an app. */
class StorePalClient @JvmOverloads constructor(
    private val apiKey: String,
    baseUrl: String = "https://storepal.app",
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
) : StorePalService {
    private val base = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // Mutating requests are not idempotent. Let the user decide after an uncertain failure.
        .retryOnConnectionFailure(false)
        .build()

    init {
        require(apiKey.startsWith("sp_live_") && apiKey.length > 8 && apiKey.none { it.isWhitespace() }) {
            "Use an app-scoped sp_live_ API key."
        }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "The base URL must not contain credentials, a query, or a fragment."
        }
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1")) {
            "Use HTTPS. HTTP is allowed only for local testing."
        }
    }

    override suspend fun submitFeedback(name: String, email: String, category: FeedbackCategory, message: String,
        metadata: Map<String, String>, attachmentIds: List<String>): FeedbackCreated {
        require(name.trim().length in 1..200) { "Enter a name of up to 200 characters." }
        validateEmail(email)
        require(message.trim().length in 1..5000) { "Enter a message of up to 5,000 characters." }
        validateIds(attachmentIds)
        val body = buildJsonObject {
            put("name", name.trim()); put("email", email.trim())
            put("category", category.name.lowercase()); put("message", message.trim())
            put("metadata", json.encodeToJsonElement(kotlinx.serialization.serializer<Map<String, String>>(), metadata))
            put("attachment_ids", json.encodeToJsonElement(kotlinx.serialization.serializer<List<String>>(), attachmentIds))
        }
        val result = request("feedback", body = body.toString().jsonBody())
        return decode<FeedbackCreated>(result)
            .copy(partialAttachmentFailure = result.error?.code == "PARTIAL_BIND")
    }

    override suspend fun uploadAttachment(data: ByteArray, fileName: String, mimeType: String): AttachmentUploaded {
        AttachmentLimits.validate(fileName, data.size.toLong())
        val canonicalType = AttachmentLimits.mimeType(fileName)!!
        require(mimeType.substringBefore(';') == canonicalType || mimeType == "application/octet-stream") {
            "The file type does not match its extension."
        }
        val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\\"\\x00]"), "_")
        val extension = baseName.substringAfterLast('.')
        val safeName = baseName.substringBeforeLast('.').take(110) + "." + extension
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", safeName, data.toRequestBody(canonicalType.toMediaType())).build()
        return decode(request("feedback/attachments", body = body))
    }

    override suspend fun listConversations(email: String, page: Int): ConversationsPage {
        validateEmail(email)
        require(page > 0) { "Page must be positive." }
        return decode(request("conversations", mapOf("email" to email.trim(), "page" to "$page", "per_page" to "20")))
    }

    override suspend fun getConversation(token: String): ConversationDetail =
        decode(request(listOf("conversations", validToken(token))))

    override suspend fun getUnreadCount(email: String): Int {
        validateEmail(email)
        return decode<Unread>(request("conversations/unread", mapOf("email" to email.trim()))).unreadCount
    }

    override suspend fun postReply(token: String, message: String, attachmentIds: List<String>): Reply {
        require(message.trim().length in 1..5000) { "Enter a reply of up to 5,000 characters." }
        validateIds(attachmentIds)
        val body = buildJsonObject {
            put("message", message.trim())
            put("attachment_ids", json.encodeToJsonElement(kotlinx.serialization.serializer<List<String>>(), attachmentIds))
        }
        val result = request(listOf("conversations", validToken(token), "reply"), body = body.toString().jsonBody())
        return decode<Reply>(result).copy(partialAttachmentFailure = result.error?.code == "PARTIAL_BIND")
    }

    override suspend fun getReleaseNote(version: String): ReleaseNote? {
        require(version.isNotBlank() && version.length <= 50) { "Enter an exact release version." }
        return try {
            decode(request("releases", mapOf("version" to version)))
        } catch (e: StorePalException) {
            if (e.statusCode == 404 && e.code in setOf("NOT_FOUND", "HTTP_404")) null else throw e
        }
    }

    private inline fun <reified T> decode(result: Envelope): T = try {
        json.decodeFromJsonElement(result.data!!)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw StorePalException("INVALID_RESPONSE", "StorePal returned unexpected data.", cause = e)
    }
    private suspend fun request(path: String, query: Map<String, String> = emptyMap(), body: RequestBody? = null) =
        request(path.split('/'), query, body)

    private suspend fun request(path: List<String>, query: Map<String, String> = emptyMap(), body: RequestBody? = null): Envelope =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().apply {
                // Retain a reverse proxy's base path, but avoid a trailing empty segment.
                if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1)
                addPathSegments("api/v1")
                path.forEach { addPathSegment(it) }
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .header("Accept-Language", language())
                .apply { if (body != null) post(body) }.build()
            val response = try {
                http.newCall(request).await()
            } catch (e: IOException) {
                throw StorePalException("NETWORK_ERROR", "Could not reach StorePal. Check your connection.", cause = e)
            }
            val envelope = try { json.decodeFromString<Envelope>(response.body) } catch (e: kotlinx.serialization.SerializationException) {
                throw StorePalException(if (response.code in 200..299) "INVALID_RESPONSE" else "HTTP_${response.code}",
                    "StorePal returned an unreadable response.", response.code, cause = e)
            }
            val error = envelope.error
            val partial = response.code == 207 && error?.code == "PARTIAL_BIND" && envelope.data != null
            if ((response.code !in 200..299 || error != null) && !partial) {
                throw StorePalException(error?.code ?: "HTTP_${response.code}", error?.message ?: "Request failed.",
                    response.code, response.retryAfterSeconds)
            }
            if (envelope.data == null) throw StorePalException("NO_DATA", "StorePal returned no data.", response.code)
            envelope
        }

    private fun String.jsonBody() = toRequestBody("application/json; charset=utf-8".toMediaType())
    private fun validateEmail(email: String) {
        require(email.trim().length <= 254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())) {
            "Enter a valid email address."
        }
    }
    private fun validateIds(ids: List<String>) {
        require(ids.size <= AttachmentLimits.MAX_FILES && ids.distinct().size == ids.size && ids.all { it.isNotBlank() }) {
            "Use up to 3 distinct attachment IDs."
        }
    }
    private fun validToken(token: String): String {
        require(token.isNotBlank()) { "A conversation token is required." }
        return token
    }

    private suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    // Keep the cancellation handler active while the body is still arriving.
                    val result = response.use { RawResponse(it.code, it.body?.string().orEmpty(), it.header("Retry-After")?.toLongOrNull()) }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

@Serializable private data class Envelope(val data: JsonElement? = null, val error: WireError? = null)
@Serializable private data class WireError(val code: String, val message: String)
@Serializable private data class Unread(@kotlinx.serialization.SerialName("unread_count") val unreadCount: Int)
private data class RawResponse(val code: Int, val body: String, val retryAfterSeconds: Long?)

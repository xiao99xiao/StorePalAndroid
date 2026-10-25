package app.storepal.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class FeedbackCategory {
    @SerialName("bug") BUG,
    @SerialName("feature") FEATURE,
    @SerialName("question") QUESTION,
    @SerialName("other") OTHER,
}

@Serializable
data class FeedbackCreated(
    val id: String,
    @SerialName("conversation_token") val conversationToken: String,
    val category: FeedbackCategory,
    @SerialName("created_at") val createdAt: String,
    /** The message was saved; do not resubmit it if some attachments failed to bind. */
    @Transient val partialAttachmentFailure: Boolean = false,
)

@Serializable
data class AttachmentUploaded(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

@Serializable
data class AttachmentInfo(
    val id: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

@Serializable
data class ConversationSummary(
    val id: String,
    @SerialName("conversation_token") val conversationToken: String,
    val category: FeedbackCategory,
    val message: String,
    @SerialName("reply_count") val replyCount: Int,
    @SerialName("last_reply_at") val lastReplyAt: String? = null,
    @SerialName("has_unread_reply") val hasUnreadReply: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ConversationsPage(
    val conversations: List<ConversationSummary>,
    val total: Int,
    val page: Int,
    @SerialName("per_page") val perPage: Int,
) {
    val hasMore: Boolean get() = page.toLong() * perPage < total
}

@Serializable
data class FeedbackDetail(
    val id: String,
    val name: String,
    val email: String,
    val category: FeedbackCategory,
    val message: String,
    val metadata: Map<String, String>? = null,
    @SerialName("reply_count") val replyCount: Int = 0,
    @SerialName("last_reply_at") val lastReplyAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    val attachments: List<AttachmentInfo>? = null,
)

@Serializable
data class Reply(
    val id: String,
    @SerialName("sender_type") val senderType: String,
    val message: String,
    @SerialName("created_at") val createdAt: String,
    val attachments: List<AttachmentInfo>? = null,
    @Transient val partialAttachmentFailure: Boolean = false,
)

@Serializable
data class ConversationDetail(val feedback: FeedbackDetail, val replies: List<Reply>)

@Serializable
data class ReleaseNote(
    val id: String,
    val version: String,
    val content: String,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("app_name") val appName: String? = null,
    @SerialName("releases_url") val releasesUrl: String? = null,
    @SerialName("release_note_url") val releaseNoteUrl: String? = null,
)

/** Implement this interface to use your own transport or deterministic previews. */
interface StorePalService {
    suspend fun submitFeedback(name: String, email: String, category: FeedbackCategory, message: String,
        metadata: Map<String, String> = emptyMap(), attachmentIds: List<String> = emptyList()): FeedbackCreated
    suspend fun uploadAttachment(data: ByteArray, fileName: String, mimeType: String): AttachmentUploaded
    suspend fun listConversations(email: String, page: Int = 1): ConversationsPage
    /** Opening a conversation also marks it read on the server. */
    suspend fun getConversation(token: String): ConversationDetail
    suspend fun getUnreadCount(email: String): Int
    suspend fun postReply(token: String, message: String, attachmentIds: List<String> = emptyList()): Reply
    suspend fun getReleaseNote(version: String): ReleaseNote?
}

class StorePalException(
    val code: String,
    override val message: String,
    val statusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

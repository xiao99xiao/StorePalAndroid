package app.storepal.sample

import android.content.Context
import app.storepal.core.*
import java.util.UUID
import kotlinx.coroutines.delay

/** Offline example data, only in the sample app. No feedback is transmitted in demo mode. */
internal class DemoService(private val context: Context) : StorePalService {
    private val conversations = mutableMapOf<String, ConversationDetail>()
    private val summaries = mutableListOf<ConversationSummary>()
    private val date = "2026-10-25T10:00:00Z"
    init {
        val token = "example-conversation"
        summaries += ConversationSummary("example", token, FeedbackCategory.FEATURE,
            context.getString(R.string.sample_feedback), 1, date, true, date)
        conversations[token] = ConversationDetail(FeedbackDetail("example", "Alex", "alex@example.com", FeedbackCategory.FEATURE,
            summaries.first().message, createdAt = date), listOf(Reply("reply-1", "developer",
            context.getString(R.string.sample_response), date)))
    }
    override suspend fun submitFeedback(name: String, email: String, category: FeedbackCategory, message: String,
        metadata: Map<String, String>, attachmentIds: List<String>): FeedbackCreated {
        delay(400)
        val id = UUID.randomUUID().toString()
        conversations[id] = ConversationDetail(FeedbackDetail(id, name, email, category, message, metadata, createdAt = date), emptyList())
        summaries.add(0, ConversationSummary(id, id, category, message, 0, null, false, date))
        return FeedbackCreated(id, id, category, date)
    }
    override suspend fun uploadAttachment(data: ByteArray, fileName: String, mimeType: String): AttachmentUploaded {
        delay(150)
        return AttachmentUploaded(UUID.randomUUID().toString(), fileName, mimeType, data.size.toLong())
    }
    override suspend fun listConversations(email: String, page: Int): ConversationsPage {
        delay(250)
        val items = summaries.filter { conversations[it.conversationToken]?.feedback?.email == email }.map {
            if (it.id == "example") it.copy(message = context.getString(R.string.sample_feedback)) else it
        }
        return ConversationsPage(if (page == 1) items else emptyList(), items.size, page, 20)
    }
    override suspend fun getConversation(token: String): ConversationDetail {
        delay(200)
        val detail = conversations[token] ?: throw StorePalException("NOT_FOUND", "No conversation.", 404)
        return if (token == "example-conversation") detail.copy(
            feedback = detail.feedback.copy(message = context.getString(R.string.sample_feedback)),
            replies = detail.replies.map { if (it.id == "reply-1") it.copy(message = context.getString(R.string.sample_response)) else it },
        ) else detail
    }
    override suspend fun getUnreadCount(email: String): Int = summaries.count { it.hasUnreadReply && conversations[it.conversationToken]?.feedback?.email == email }
    override suspend fun postReply(token: String, message: String, attachmentIds: List<String>): Reply {
        delay(300)
        val reply = Reply(UUID.randomUUID().toString(), "user", message, date)
        val detail = conversations[token] ?: throw StorePalException("NOT_FOUND", "No conversation.", 404)
        conversations[token] = detail.copy(replies = detail.replies + reply)
        return reply
    }
    override suspend fun getReleaseNote(version: String): ReleaseNote {
        delay(250)
        return ReleaseNote("demo-release", version, context.getString(R.string.sample_release), "2026-10-25", context.getString(R.string.sample_app_name))
    }
}

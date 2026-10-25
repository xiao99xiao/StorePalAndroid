package app.storepal.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.closeSoftKeyboard
import app.storepal.core.*
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StorePalUITest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val service = TestService()
    @Before fun setup() {
        StorePalFeedback.initialize(InstrumentationRegistry.getInstrumentation().targetContext, service,
            "test-${System.nanoTime()}", "alex@example.com", "Alex")
        StorePalFeedback.rememberConversation("00000000-0000-4000-8000-000000000001")
    }
    private fun feedback() { compose.setContent { StorePalTheme { StorePalFeedbackScreen({}) } } }
    private fun release() { compose.setContent { StorePalTheme { StorePalWhatsNewScreen({}, version = "1.1") } } }
    private fun waitText(text: String) { compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun tag(value: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(value))
        return compose.onNodeWithTag(value)
    }
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun typeMessage() {
        tag("feedback_message").performTextInput("A useful feedback message.")
        closeSoftKeyboard()
        compose.waitForIdle()
    }
    @Test fun feedbackSubmitsOnceAndShowsSuccess() {
        feedback(); typeMessage()
        tag("feedback_send").performClick()
        waitText("Thanks for your feedback")
        assertEquals(1, service.submissions)
        assertEquals("Android", service.lastMetadata["os_version"]?.substringBefore(' '))
    }
    @Test fun invalidFormCannotSubmit() {
        feedback()
        tag("feedback_send").assertIsNotEnabled()
        assertEquals(0, service.submissions)
    }
    @Test fun retryableSubmissionPreservesDraft() {
        service.submitError = StorePalException("RATE_LIMITED", "Wait", 429)
        feedback(); typeMessage()
        tag("feedback_send").performClick()
        text("Too many requests. Please wait a minute and try again.").assertIsDisplayed()
        tag("feedback_message").assertTextContains("A useful feedback message.")
        service.submitError = null
        tag("feedback_send").performClick()
        waitText("Thanks for your feedback")
    }
    @Test fun partialBindShowsSavedWarningWithoutRetrying() {
        service.partial = true
        feedback(); typeMessage()
        tag("feedback_send").performClick()
        waitText("Your message was saved, but some attachments could not be added. Please do not send the message again.")
        assertEquals(1, service.submissions)
    }
    @Test fun inboxOpensAndCanReply() {
        feedback()
        compose.onNodeWithText("My conversations").performClick()
        waitText("Original feedback")
        compose.onNodeWithText("Original feedback").performClick()
        waitText("Developer")
        tag("conversation_reply").performTextInput("Thanks, that helps!")
        closeSoftKeyboard()
        compose.waitForIdle()
        text("Send reply").performClick()
        text("Thanks, that helps!").assertIsDisplayed()
        assertEquals(1, service.replies)
    }
    @Test fun cooldownKeepsUnsentReply() {
        service.replyError = StorePalException("COOLDOWN", "Wait", 400)
        feedback()
        compose.onNodeWithText("My conversations").performClick()
        waitText("Original feedback")
        compose.onNodeWithText("Original feedback").performClick()
        waitText("Developer")
        tag("conversation_reply").performTextInput("My unsent reply")
        closeSoftKeyboard()
        compose.waitForIdle()
        text("Send reply").performClick()
        text("Please wait 30 seconds between replies.").assertIsDisplayed()
        tag("conversation_reply").assertTextContains("My unsent reply")
    }
    @Test fun missingReleaseCanBeRetried() {
        service.releaseMissing = true
        release()
        waitText("No update notes yet")
        service.releaseMissing = false
        compose.onNodeWithText("Try again").performClick()
        waitText("Version 1.1")
        compose.waitUntil(5000) { service.releaseCalls == 2 }
    }
    @Test fun releaseErrorHasRecovery() {
        service.releaseError = StorePalException("NETWORK_ERROR", "Offline")
        release()
        waitText("Try again")
        service.releaseError = null
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5000) { service.releaseCalls == 2 }
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
    @Test fun closingDraftRequiresExplicitDiscard() {
        feedback(); typeMessage()
        compose.onNodeWithText("Close").performClick()
        waitText("Discard this draft?")
        compose.onNodeWithText("Keep editing").performClick()
        tag("feedback_message").assertTextContains("A useful feedback message.")
    }
    @Test fun sheetClosesWithoutTrappingAnEmptyForm() {
        val visible = mutableStateOf(true)
        compose.setContent { StorePalTheme { if (visible.value) StorePalFeedbackSheet({ visible.value = false }) } }
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5000) { !visible.value }
    }
    @Test fun sheetProtectsDraftAndDiscardsOnConfirmation() {
        val visible = mutableStateOf(true)
        compose.setContent { StorePalTheme { if (visible.value) StorePalFeedbackSheet({ visible.value = false }) } }
        typeMessage()
        compose.onNodeWithText("Close").performClick()
        waitText("Discard this draft?")
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.waitUntil(5000) { !visible.value }
    }
}

internal class TestService : StorePalService {
    var submitError: Throwable? = null
    var replyError: Throwable? = null
    var releaseError: Throwable? = null
    var releaseMissing = false
    var partial = false
    var submissions = 0
    var replies = 0
    var releaseCalls = 0
    var lastMetadata = emptyMap<String, String>()
    override suspend fun submitFeedback(name: String, email: String, category: FeedbackCategory, message: String,
        metadata: Map<String, String>, attachmentIds: List<String>): FeedbackCreated {
        submissions++; lastMetadata = metadata
        submitError?.let { throw it }
        return FeedbackCreated("f1", "00000000-0000-4000-8000-000000000001", category, "today", partial)
    }
    override suspend fun uploadAttachment(data: ByteArray, fileName: String, mimeType: String) = AttachmentUploaded("a1", fileName, mimeType, data.size.toLong())
    override suspend fun listConversations(email: String, page: Int) = ConversationsPage(
        listOf(ConversationSummary("f1", "00000000-0000-4000-8000-000000000001", FeedbackCategory.BUG, "Original feedback", 1, "today", true, "today")), 1, 1, 20)
    override suspend fun listConversations(tokens: List<String>, page: Int) = ConversationsPage(
        if ("00000000-0000-4000-8000-000000000001" in tokens) listOf(ConversationSummary("f1", "00000000-0000-4000-8000-000000000001", FeedbackCategory.BUG, "Original feedback", 1, "today", true, "today")) else emptyList(),
        if (tokens.isEmpty()) 0 else 1, page, 20)
    override suspend fun getConversation(token: String) = ConversationDetail(
        FeedbackDetail("f1", "Alex", "alex@example.com", FeedbackCategory.BUG, "Original feedback", createdAt = "today"),
        listOf(Reply("r1", "developer", "Developer response", "today")))
    override suspend fun getUnreadCount(email: String) = 1
    override suspend fun postReply(token: String, message: String, attachmentIds: List<String>): Reply {
        replyError?.let { throw it }; replies++
        return Reply("r2", "user", message, "today")
    }
    override suspend fun getReleaseNote(version: String): ReleaseNote? {
        releaseCalls++; releaseError?.let { throw it }
        return if (releaseMissing) null else ReleaseNote("r1", version, "## New features\n\nA useful update.", "today", "Example")
    }
}

package app.storepal.android

import android.content.Context
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import app.storepal.core.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Test

private const val FIRST = "00000000-0000-4000-8000-000000000001"
private const val SECOND = "00000000-0000-4000-8000-000000000002"

class ConversationMigrationTest {
    private var lastScope = ""
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun runtime(service: MigrationService, legacy: Boolean = false): StorePalRuntime {
        val scope = "migration-test-${System.nanoTime()}"
        lastScope = scope
        if (legacy) {
            val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            context.getSharedPreferences("storepal_$hash", Context.MODE_PRIVATE).edit(commit = true) { putString("email", "old@example.test") }
        }
        StorePalFeedback.initialize(context, service, scope, "configured@example.test")
        return StorePalFeedback.requireRuntime()
    }

    @Test fun newInstallationDoesNotDiscoverByConfiguredEmail() = runBlocking {
        val service = MigrationService()
        val runtime = runtime(service)
        assertEquals(0, runtime.inbox(1).total)
        assertTrue(service.legacyPages.isEmpty())
        assertTrue(runtime.saveToken(FIRST))
        assertFalse(runtime.saveToken("invalid"))
        assertTrue(runtime.inbox(1).conversations.single().hasUnreadReply)
        assertTrue(service.legacyPages.isEmpty())
        assertEquals(0, service.threadReads)
    }

    @Test fun oldInstallationImportsOnceAndKeepsUnreadAcrossRestart() = runBlocking {
        val service = MigrationService()
        val runtime = runtime(service, legacy = true)
        assertEquals(2, runtime.inbox(1).total)
        assertEquals(listOf(1, 2), service.legacyPages)
        assertEquals(setOf(FIRST, SECOND), runtime.tokens().toSet())
        StorePalFeedback.initialize(context, service, lastScope, "configured@example.test")
        assertTrue(StorePalFeedback.requireRuntime().inbox(1).conversations.all { it.hasUnreadReply })
        assertEquals(listOf(1, 2), service.legacyPages)
        assertEquals(0, service.threadReads)
    }

    @Test fun transientMigrationFailureRetainsProgressAndRetriesMissingPage() = runBlocking {
        val service = MigrationService().apply { failSecondPage = true }
        val runtime = runtime(service, legacy = true)
        assertFailsWith<StorePalException> { runtime.inbox(1) }
        assertEquals(listOf(FIRST), runtime.tokens())
        service.failSecondPage = false
        StorePalFeedback.initialize(context, service, lastScope, "configured@example.test")
        assertEquals(2, StorePalFeedback.requireRuntime().inbox(1).total)
        assertEquals(listOf(1, 2, 2), service.legacyPages)
    }

    @Test fun cutoffKeepsExistingCapabilitiesAndDoesNotKeepTryingEmail() = runBlocking {
        val service = MigrationService().apply { cutoff = true }
        val runtime = runtime(service, legacy = true)
        runtime.saveToken(FIRST)
        assertEquals(1, runtime.inbox(1).total)
        assertEquals(1, runtime.inbox(1).total)
        assertEquals(listOf(1), service.legacyPages)
    }

    @Test fun signOutCannotResurrectInFlightCapabilities() = runBlocking {
        val service = MigrationService().apply { waitForImport = CompletableDeferred() }
        val runtime = runtime(service, legacy = true)
        val pending = async { runtime.inbox(1) }
        service.importStarted.await()
        StorePalFeedback.clearUserData()
        service.waitForImport!!.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(runtime.tokens().isEmpty())
        assertNull(runtime.preferences.getString("email", null))
    }

    private class MigrationService : StorePalService {
        val legacyPages = mutableListOf<Int>()
        var threadReads = 0
        var failSecondPage = false
        var cutoff = false
        var waitForImport: CompletableDeferred<Unit>? = null
        val importStarted = CompletableDeferred<Unit>()
        private fun summary(token: String) = ConversationSummary(token, token, FeedbackCategory.BUG, "Saved feedback", 1, "2026-10-25", true, "2026-10-24")
        override suspend fun listConversations(email: String, page: Int): ConversationsPage {
            legacyPages += page
            importStarted.complete(Unit)
            waitForImport?.await()
            if (cutoff) throw StorePalException("USER_TOKEN_REQUIRED", "Migration ended", 403)
            if (failSecondPage && page == 2) throw StorePalException("UNAVAILABLE", "Retry", 503)
            return ConversationsPage(listOf(summary(if (page == 1) FIRST else SECOND)), 2, page, 1)
        }
        override suspend fun listConversations(tokens: List<String>, page: Int) = ConversationsPage(tokens.map(::summary), tokens.size, page, 20)
        override suspend fun getUnreadCount(email: String) = error("Legacy unread discovery must not run")
        override suspend fun getConversation(token: String): ConversationDetail { threadReads++; error("Inbox must not mark threads seen") }
        override suspend fun submitFeedback(name: String, email: String, category: FeedbackCategory, message: String, metadata: Map<String, String>, attachmentIds: List<String>): FeedbackCreated = error("Unused")
        override suspend fun uploadAttachment(data: ByteArray, fileName: String, mimeType: String): AttachmentUploaded = error("Unused")
        override suspend fun postReply(token: String, message: String, attachmentIds: List<String>): Reply = error("Unused")
        override suspend fun getReleaseNote(version: String): ReleaseNote? = null
    }
}

package app.storepal.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.storepal.core.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PendingFile(val name: String, val bytes: ByteArray,
    val uploaded: AttachmentUploaded? = null, val uploadedAt: Long = 0)

internal class FeedbackViewModel(private val runtime: StorePalRuntime, private val saved: SavedStateHandle) : ViewModel() {
    var name by mutableStateOf(saved.get<String>("name") ?: runtime.userName ?: runtime.preferences.getString("name", "").orEmpty()); private set
    var email by mutableStateOf(saved.get<String>("email") ?: runtime.userEmail ?: runtime.preferences.getString("email", "").orEmpty()); private set
    var message by mutableStateOf(saved.get<String>("message").orEmpty()); private set
    var category by mutableStateOf(FeedbackCategory.valueOf(saved.get<String>("category") ?: "BUG")); private set
    var replyText by mutableStateOf(saved.get<String>("reply").orEmpty()); private set
    var files by mutableStateOf<List<PendingFile>>(emptyList()); private set
    var replyFiles by mutableStateOf<List<PendingFile>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var readingFiles by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var sent by mutableStateOf(false); private set
    var partial by mutableStateOf(false); private set
    var conversations by mutableStateOf<List<ConversationSummary>>(emptyList()); private set
    var page by mutableIntStateOf(0); private set
    var hasMore by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var inboxError by mutableStateOf<Throwable?>(null); private set
    var loadedEmail: String? = null; private set
    var token by mutableStateOf<String?>(saved["token"]); private set
    var detail by mutableStateOf<ConversationDetail?>(null); private set
    var detailLoading by mutableStateOf(false); private set
    var detailError by mutableStateOf<Throwable?>(null); private set

    init { token?.let { openConversation(it) } }
    fun name(value: String) { name = value; saved["name"] = value }
    fun email(value: String) { email = value; saved["email"] = value }
    fun message(value: String) { message = value; saved["message"] = value }
    fun category(value: FeedbackCategory) { category = value; saved["category"] = value.name }
    fun reply(value: String) { replyText = value; saved["reply"] = value }
    fun startAgain() { sent = false; partial = false; error = null }
    fun discardDraft() { message(""); files = emptyList(); error = null }
    fun removeFile(index: Int, reply: Boolean) {
        if (busy || readingFiles) return
        if (reply) replyFiles = replyFiles.filterIndexed { i, _ -> i != index }
        else files = files.filterIndexed { i, _ -> i != index }
    }

    fun addFiles(uris: List<Uri>, reply: Boolean) {
        if (busy || readingFiles || uris.isEmpty()) return
        readingFiles = true; error = null
        viewModelScope.launch {
            try {
                val existing = if (reply) replyFiles else files
                require(existing.size + uris.size <= AttachmentLimits.MAX_FILES)
                val added = withContext(Dispatchers.IO) { uris.map { uri ->
                    val resolver = runtime.context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "attachment"
                    require(AttachmentLimits.mimeType(name) != null)
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= AttachmentLimits.MAX_FILE_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Could not read file")
                    AttachmentLimits.validate(name, bytes.size.toLong())
                    PendingFile(name, bytes)
                } }
                val selection = existing + added
                AttachmentLimits.validateSelection(selection.map { it.bytes.size.toLong() })
                if (reply) replyFiles = selection else files = selection
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = StorePalException("ATTACHMENT_SELECTION", "Could not add files.", cause = e) }
            finally { readingFiles = false }
        }
    }

    private suspend fun upload(reply: Boolean): List<String> {
        val selected = if (reply) replyFiles else files
        return selected.mapIndexed { index, file ->
            val now = System.currentTimeMillis()
            val uploaded = if (file.uploaded != null && now - file.uploadedAt < 50 * 60 * 1000L) file.uploaded else {
                val result = runtime.service.uploadAttachment(file.bytes, file.name, AttachmentLimits.mimeType(file.name)!!)
                val updated = file.copy(uploaded = result, uploadedAt = now)
                if (reply) replyFiles = replyFiles.toMutableList().also { it[index] = updated }
                else files = files.toMutableList().also { it[index] = updated }
                result
            }
            uploaded.attachmentId
        }
    }

    fun submit() {
        if (busy || readingFiles) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val result = runtime.service.submitFeedback(name, email, category, message, runtime.metadata(), upload(false))
                runtime.saveIdentity(name, email)
                files = emptyList(); message(""); partial = result.partialAttachmentFailure; sent = true
                loadedEmail = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e }
            finally { busy = false }
        }
    }

    fun loadConversations(reset: Boolean = true) {
        if (loading) return
        val address = email.trim()
        loading = true; inboxError = null
        val next = if (reset || loadedEmail != address) 1 else page + 1
        if (loadedEmail != address) { conversations = emptyList(); hasMore = false; page = 0 }
        viewModelScope.launch {
            try {
                val result = runtime.service.listConversations(address, next)
                conversations = if (next == 1) result.conversations else (conversations + result.conversations).distinctBy { it.id }
                loadedEmail = address; page = result.page; hasMore = result.hasMore
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { inboxError = e }
            finally { loading = false }
        }
    }

    fun openConversation(value: String) {
        if (busy) return
        if (token != value) { detail = null; reply(""); replyFiles = emptyList() }
        token = value; saved["token"] = value; detailLoading = true; detailError = null; error = null; partial = false
        viewModelScope.launch {
            try {
                val result = runtime.service.getConversation(value)
                if (token == value) {
                    detail = result
                    conversations = conversations.map { if (it.conversationToken == value) it.copy(hasUnreadReply = false) else it }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == value) detailError = e }
            finally { if (token == value) detailLoading = false }
        }
    }

    fun closeConversation() { if (!busy) { token = null; saved["token"] = null; detail = null; error = null; reply(""); replyFiles = emptyList() } }
    fun sendReply() {
        val selectedToken = token ?: return
        if (busy || readingFiles) return
        busy = true; error = null; partial = false
        viewModelScope.launch {
            try {
                val result = runtime.service.postReply(selectedToken, replyText, upload(true))
                detail = detail?.let { it.copy(replies = it.replies + result) }
                reply(""); replyFiles = emptyList(); partial = result.partialAttachmentFailure
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e }
            finally { busy = false }
        }
    }
}

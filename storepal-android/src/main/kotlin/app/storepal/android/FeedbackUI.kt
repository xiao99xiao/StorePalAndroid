package app.storepal.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.storepal.core.FeedbackCategory
import app.storepal.core.StorePalException
import kotlinx.coroutines.launch

@Composable
fun FeedbackButton(modifier: Modifier = Modifier, text: String? = null) {
    StorePalLocalizedContent {
        val activity = LocalContext.current.findActivity()
        Button(onClick = { activity?.let(StorePalFeedback::show) }, modifier = modifier, enabled = activity != null) {
            Text(text ?: stringResource(R.string.sp_send_feedback))
        }
    }
}

/** Inherits the host's MaterialTheme. Use inside your navigation destination or an SDK sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePalFeedbackScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    StorePalLocalizedContent { FeedbackScreenContent(onClose, modifier) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackScreenContent(onClose: () -> Unit, modifier: Modifier) {
    val runtime = StorePalFeedback.requireRuntime()
    val model = viewModel<FeedbackViewModel>(key = "storepal-feedback") { FeedbackViewModel(runtime, createSavedStateHandle()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    fun close() {
        if (model.busy || model.readingFiles) return
        if (model.token != null) {
            if (model.replyText.isNotBlank() || model.replyFiles.isNotEmpty()) confirm = true else model.closeConversation()
        } else if (model.message.isNotBlank() || model.files.isNotEmpty()) confirm = true else onClose()
    }
    BackHandler { close() }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.sp_discard_title)) },
        text = { Text(stringResource(R.string.sp_discard_detail)) },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (model.token != null) model.closeConversation() else { model.discardDraft(); onClose() }
        }) { Text(stringResource(R.string.sp_discard)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.sp_keep_editing)) } },
    )
    Scaffold(modifier = modifier.imePadding(), topBar = {
        TopAppBar(title = { Text(stringResource(if (model.token != null) R.string.sp_conversations else R.string.sp_feedback)) },
            navigationIcon = { TextButton(onClick = { close() }, enabled = !model.busy && !model.readingFiles) {
                Text(stringResource(if (model.token != null) R.string.sp_back else R.string.sp_close))
            } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            if (model.token == null) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(R.string.sp_feedback_tab, R.string.sp_conversations).forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = {
                            tab = index
                            if (index == 1 && model.email.isNotBlank() && model.loadedEmail != model.email.trim()) model.loadConversations()
                        }, enabled = !model.busy && !model.readingFiles, text = { Text(stringResource(label)) })
                    }
                }
            }
            Box(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()) {
                when {
                    model.token != null -> Conversation(model)
                    tab == 1 -> Inbox(model)
                    model.sent -> Sent(model) { tab = 1; model.loadConversations() }
                    else -> FeedbackForm(model)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePalFeedbackSheet(onDismissRequest: () -> Unit) {
    StorePalLocalizedContent { FeedbackSheetContent(onDismissRequest) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackSheetContent(onDismissRequest: () -> Unit) {
    val runtime = StorePalFeedback.requireRuntime()
    val model = viewModel<FeedbackViewModel>(key = "storepal-feedback") { FeedbackViewModel(runtime, createSavedStateHandle()) }
    var confirm by remember { mutableStateOf(false) }
    var allowDismiss by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        when {
            target != SheetValue.Hidden || allowDismiss -> true
            model.busy || model.readingFiles -> false
            model.message.isNotBlank() || model.files.isNotEmpty() || model.replyText.isNotBlank() || model.replyFiles.isNotEmpty() -> {
                confirm = true; false
            }
            else -> true
        }
    })
    fun dismiss() {
        allowDismiss = true
        scope.launch { state.hide(); onDismissRequest() }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.sp_discard_title)) },
        text = { Text(stringResource(R.string.sp_discard_detail)) },
        confirmButton = { TextButton(onClick = { confirm = false; model.discardDraft(); model.closeConversation(); dismiss() }) {
            Text(stringResource(R.string.sp_discard))
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.sp_keep_editing)) } })
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = state, dragHandle = null) {
        StorePalFeedbackScreen({ dismiss() }, Modifier.fillMaxHeight(0.95f))
    }
}

@Composable
private fun FeedbackForm(model: FeedbackViewModel) {
    val locale = LocalConfiguration.current.primaryLocale()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.sp_intro), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.sp_intro_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Categories(model) }
        item { OutlinedTextField(model.name, model::name, Modifier.fillMaxWidth().testTag("feedback_name"), enabled = !model.busy,
            label = { Text(stringResource(R.string.sp_name)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            isError = model.name.length > 200) }
        item { EmailField(model) }
        item { OutlinedTextField(model.message, model::message, Modifier.fillMaxWidth().testTag("feedback_message"), enabled = !model.busy,
            label = { Text(stringResource(R.string.sp_message)) }, minLines = 4, maxLines = 10,
            supportingText = { Text(stringResource(R.string.sp_message_hint,
                formatCount(10, locale), formatCount(5000, locale), formatCount(model.message.length, locale))) },
            isError = model.message.length > 5000,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) }
        item { Attachments(model, false) }
        model.error?.let { item { ErrorMessage(it) } }
        item { Button(onClick = model::submit, enabled = !model.busy && !model.readingFiles &&
            model.name.trim().length in 1..200 && validEmail(model.email) && model.message.trim().length in 10..5000,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("feedback_send")) {
            Text(stringResource(if (model.busy) R.string.sp_sending else R.string.sp_send_feedback))
        } }
        item { Text(stringResource(R.string.sp_diagnostics), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Categories(model: FeedbackViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedbackCategory.entries.forEach { category ->
            FilterChip(selected = model.category == category, onClick = { model.category(category) }, enabled = !model.busy,
                label = { Text(categoryLabel(category)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun EmailField(model: FeedbackViewModel) {
    OutlinedTextField(model.email, model::email, Modifier.fillMaxWidth().testTag("feedback_email"), enabled = !model.busy && !model.loading,
        label = { Text(stringResource(R.string.sp_email)) }, singleLine = true,
        isError = model.email.isNotEmpty() && !validEmail(model.email),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
}

@Composable
private fun Attachments(model: FeedbackViewModel, reply: Boolean) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.addFiles(it, reply) }
    val files = if (reply) model.replyFiles else model.files
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEachIndexed { index, file ->
            val removeLabel = stringResource(R.string.sp_remove_file, file.name)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(file.name, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = { model.removeFile(index, reply) }, enabled = !model.busy && !model.readingFiles,
                    modifier = Modifier.semantics { contentDescription = removeLabel }) {
                    Text(stringResource(R.string.sp_remove))
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/*", "application/octet-stream")) },
            enabled = !model.busy && !model.readingFiles && files.size < 3) {
            Text(stringResource(if (model.readingFiles) R.string.sp_reading_files else R.string.sp_attach))
        }
        Text(stringResource(R.string.sp_attachment_limits), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Sent(model: FeedbackViewModel, inbox: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.sp_sent), style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(R.string.sp_sent_detail)) }
        if (model.partial) item { Text(stringResource(R.string.sp_partial), color = MaterialTheme.colorScheme.error) }
        item { Button(inbox) { Text(stringResource(R.string.sp_conversations)) } }
        item { TextButton(model::startAgain) { Text(stringResource(R.string.sp_another)) } }
    }
}

@Composable
private fun Inbox(model: FeedbackViewModel) {
    val locale = LocalConfiguration.current.primaryLocale()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sp_inbox_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            EmailField(model)
            OutlinedButton(onClick = { model.loadConversations() }, enabled = validEmail(model.email) && !model.loading) {
                Text(stringResource(if (model.page == 0) R.string.sp_load else R.string.sp_refresh))
            }
        } }
        model.inboxError?.let { item { Box(Modifier.padding(horizontal = 24.dp)) { ErrorMessage(it) } } }
        if (model.loadedEmail == model.email.trim()) {
            if (model.conversations.isEmpty() && !model.loading && model.inboxError == null) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sp_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.sp_empty_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(model.conversations, key = { it.id }) { conversation ->
                ListItem(modifier = Modifier.clickable { model.openConversation(conversation.conversationToken) },
                    headlineContent = { Text(conversation.message, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    overlineContent = { Text(categoryLabel(conversation.category)) },
                    supportingContent = { Text(if (conversation.hasUnreadReply) stringResource(R.string.sp_unread)
                        else pluralStringResource(R.plurals.sp_replies, conversation.replyCount, formatCount(conversation.replyCount, locale))) })
                HorizontalDivider()
            }
        }
        if (model.loading) item { Loading(Modifier.padding(24.dp)) }
        if (model.hasMore && model.loadedEmail == model.email.trim()) item {
            TextButton(onClick = { model.loadConversations(false) }, enabled = !model.loading, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.sp_load_more))
            }
        }
    }
}

@Composable
private fun Conversation(model: FeedbackViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OutlinedButton(onClick = { model.token?.let(model::openConversation) }, enabled = !model.busy && !model.detailLoading) {
            Text(stringResource(R.string.sp_refresh))
        } }
        if (model.detailLoading) item { Loading() }
        model.detailError?.let { item { ErrorMessage(it) } }
        model.detail?.let { detail ->
            item { Message(stringResource(R.string.sp_you), detail.feedback.message, detail.feedback.createdAt) }
            items(detail.replies, key = { it.id }) { reply ->
                Message(stringResource(if (reply.senderType == "developer") R.string.sp_developer else R.string.sp_you), reply.message, reply.createdAt)
            }
            item { HorizontalDivider() }
            item { OutlinedTextField(model.replyText, model::reply, Modifier.fillMaxWidth().testTag("conversation_reply"), enabled = !model.busy,
                label = { Text(stringResource(R.string.sp_reply)) }, minLines = 3, maxLines = 8,
                isError = model.replyText.length > 5000,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) }
            item { Attachments(model, true) }
            model.error?.let { item { ErrorMessage(it) } }
            if (model.partial) item { Text(stringResource(R.string.sp_partial), color = MaterialTheme.colorScheme.error) }
            item { Button(onClick = model::sendReply, enabled = !model.busy && !model.readingFiles && model.replyText.trim().length in 1..5000,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (model.busy) R.string.sp_sending else R.string.sp_send_reply))
            } }
        }
    }
}

@Composable
private fun Message(sender: String, message: String, date: String) {
    val locale = LocalConfiguration.current.primaryLocale()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(sender, style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(message) }
        Text(formatConversationDate(date, locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Loading(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.sp_loading))
    }
}

@Composable
internal fun ErrorMessage(error: Throwable) {
    val id = when ((error as? StorePalException)?.code) {
        "NETWORK_ERROR" -> R.string.sp_network_error
        "ATTACHMENT_SELECTION" -> R.string.sp_attachment_error
        "COOLDOWN" -> R.string.sp_cooldown
        "REPLY_LIMIT" -> R.string.sp_reply_limit
        "RATE_LIMITED" -> R.string.sp_rate_limit
        "UNAUTHORIZED", "INVALID_API_KEY", "INVALID_TOKEN", "HTTP_401", "HTTP_403", "FORBIDDEN" -> R.string.sp_auth_error
        "VALIDATION_ERROR" -> R.string.sp_validation
        else -> if (error is IllegalArgumentException) R.string.sp_validation else R.string.sp_error
    }
    Text(stringResource(id), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun categoryLabel(category: FeedbackCategory): String = stringResource(when (category) {
    FeedbackCategory.BUG -> R.string.sp_bug
    FeedbackCategory.FEATURE -> R.string.sp_feature
    FeedbackCategory.QUESTION -> R.string.sp_question
    FeedbackCategory.OTHER -> R.string.sp_other
})

private fun validEmail(value: String): Boolean = value.trim().length <= 254 && Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

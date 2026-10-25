package app.storepal.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import app.storepal.core.ReleaseNote
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Place once near your root UI. Uses the same Activity entry point as View-based apps. */
@Composable
fun StorePalWhatsNewOnUpdate() {
    val activity = LocalContext.current.findActivity()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(activity, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) StorePalWhatsNew.check(activity)
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity != null) StorePalWhatsNew.check(activity)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePalWhatsNewScreen(onClose: () -> Unit, modifier: Modifier = Modifier,
    version: String = StorePalFeedback.requireRuntime().appVersion) {
    StorePalLocalizedContent { WhatsNewScreenContent(onClose, modifier, version) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewScreenContent(onClose: () -> Unit, modifier: Modifier, version: String) {
    val runtime = StorePalFeedback.requireRuntime()
    val model = viewModel<ReleaseViewModel>(key = "storepal-release-$version") { ReleaseViewModel(runtime, version) }
    val configuration = LocalConfiguration.current
    LaunchedEffect(model, configuration.languageTags()) {
        runtime.languages.use(configuration)
        model.loadForLanguage(configuration.languageTags())
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(model.note, owner) {
        val note = model.note ?: return@LaunchedEffect
        if (note.version == runtime.appVersion && note.content.isNotBlank()) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                withFrameNanos { }
                runtime.tracker.markPresented(note.version)
            }
        }
    }
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.sp_whats_new)) },
            navigationIcon = { TextButton(onClose) { Text(stringResource(R.string.sp_close)) } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(model.note?.appName ?: stringResource(R.string.sp_whats_new), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sp_version, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    model.loading || !model.requested -> item { Loading() }
                    model.error != null -> item {
                        ErrorMessage(model.error!!)
                        TextButton(model::load) { Text(stringResource(R.string.sp_retry)) }
                    }
                    model.note == null -> item {
                        Text(stringResource(R.string.sp_no_release), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.sp_no_release_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(model::load) { Text(stringResource(R.string.sp_retry)) }
                    }
                    else -> item { ReleaseMarkdown(model.note!!.content) }
                }
                item { Button(onClose, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.sp_done)) } }
            }
        }
    }
}

@Composable
private fun ReleaseMarkdown(content: String) {
    val context = LocalContext.current
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
    val markwon = remember(context) {
        Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val uri = link.toUri()
                    if (uri.scheme?.lowercase() in setOf("https", "http", "mailto")) {
                        try { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        catch (_: ActivityNotFoundException) { /* No compatible browser or mail app. */ }
                    }
                }
            }
        }).build()
    }
    AndroidView(factory = { TextView(it).apply { setTextIsSelectable(true) } }, modifier = Modifier.fillMaxWidth(), update = {
        it.setTextColor(foreground)
        it.setLinkTextColor(linkColor)
        it.textSize = textSize
        markwon.setMarkdown(it, content)
    })
}

internal class ReleaseViewModel(private val runtime: StorePalRuntime, private val version: String) : ViewModel() {
    var loading by mutableStateOf(false); private set
    var requested by mutableStateOf(false); private set
    var note by mutableStateOf<ReleaseNote?>(null); private set
    var error by mutableStateOf<Throwable?>(null); private set
    private var language: String? = null
    private var request: Job? = null
    private var generation = 0
    fun loadForLanguage(value: String) {
        if (language == value) return
        language = value
        startRequest(force = true)
    }
    fun load() { startRequest(force = false) }
    private fun startRequest(force: Boolean) {
        if (loading && !force) return
        val current = ++generation
        request?.cancel()
        loading = true; requested = true; error = null
        request = viewModelScope.launch {
            try {
                val result = runtime.service.getReleaseNote(version)?.takeIf { it.version == version && it.content.isNotBlank() }
                if (current == generation) note = result
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) error = e }
            finally { if (current == generation) loading = false }
        }
    }
}

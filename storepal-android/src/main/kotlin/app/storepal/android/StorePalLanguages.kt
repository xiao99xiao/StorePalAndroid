package app.storepal.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal fun Configuration.languageTags(): String =
    if (Build.VERSION.SDK_INT >= 24) locales.toLanguageTags() else primaryLocale().toLanguageTag()

/** Resolve SDK languages independently of unrelated translations included by host dependencies. */
internal fun storePalLocale(tags: String): Locale {
    val preferred = tags.split(',').asSequence().map { Locale.forLanguageTag(it.trim()) }
        .firstOrNull { it.language in setOf("en", "zh", "ja", "ko", "fr", "de", "es", "pt") } ?: Locale.ENGLISH
    if (preferred.language != "zh") return preferred
    // Make script selection explicit for Android 6 as well as newer locale matchers.
    val script = when {
        preferred.script == "Hant" -> "Hant"
        preferred.script == "Hans" -> "Hans"
        preferred.country in setOf("TW", "HK", "MO") -> "Hant"
        else -> "Hans"
    }
    return Locale.Builder().setLocale(preferred).setScript(script).build()
}

internal fun Configuration.withStorePalLocale(tags: String = languageTags()): Configuration =
    Configuration(this).apply { setLocale(storePalLocale(tags)) }

internal class StorePalLanguages(private val application: Context) {
    private data class Choice(val presentedTags: String, val applicationTags: String)
    @Volatile private var choice: Choice? = null
    fun use(configuration: Configuration) {
        choice = Choice(configuration.languageTags(), application.resources.configuration.languageTags())
    }
    fun acceptLanguage(): String {
        val current = application.resources.configuration.languageTags()
        val previous = choice
        // A later system/per-app setting must supersede the last screen's language.
        val tags = if (previous?.applicationTags == current) previous.presentedTags else current
        return storePalLocale(tags).toLanguageTag()
    }
}

// Before Android 13, AppCompat's per-app override belongs to the presenting Activity.
internal val LocalStorePalLanguageTags = staticCompositionLocalOf<String?> { null }

@Composable
internal fun StorePalLocalizedContent(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val original = LocalConfiguration.current
    val tags = LocalStorePalLanguageTags.current ?: original.languageTags()
    val configuration = remember(original, tags) { original.withStorePalLocale(tags) }
    val localized = remember(base, configuration) {
        // Keep the Activity in the context chain for file pickers and other ActivityResult APIs.
        ContextThemeWrapper(base, base.theme).apply { applyOverrideConfiguration(configuration) }
    }
    val runtime = StorePalFeedback.runtime
    SideEffect { runtime?.languages?.use(configuration) }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, content = content)
}

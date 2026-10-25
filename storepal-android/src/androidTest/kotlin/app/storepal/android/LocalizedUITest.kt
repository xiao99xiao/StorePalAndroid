package app.storepal.android

import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import app.storepal.core.ReleaseNote
import app.storepal.core.StorePalService
import kotlinx.coroutines.delay
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalizedUITest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchingLanguagesUpdatesTheScreenWithoutLosingTheDraft() {
        StorePalFeedback.initialize(compose.activity, TestService(), "localized-ui", "alex@example.com", "Alex")
        val tag = mutableStateOf("en")
        compose.setContent {
            WithLanguage(tag.value) {
                StorePalTheme { StorePalFeedbackScreen({}) }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("feedback_message"))
        compose.onNodeWithTag("feedback_message").performTextInput("A draft kept across language changes.")
        closeSoftKeyboard()
        compose.waitForIdle()
        listOf("zh-Hans", "zh-Hant", "ja", "ko", "fr", "de", "es", "pt", "en").forEach { language ->
            compose.runOnIdle { tag.value = language }
            val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = compose.activity.createConfigurationContext(configuration)
            compose.onNodeWithText(localized.getString(R.string.sp_close)).assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.sp_conversations)).assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("feedback_message"))
            compose.onNodeWithTag("feedback_message").assertTextContains("A draft kept across language changes.")
        }
    }

    @Test fun changingReleaseLanguageCancelsThePreviousRequestAndFetchesTheTranslation() {
        val requests = CopyOnWriteArrayList<String>()
        val service = object : StorePalService by TestService() {
            override suspend fun getReleaseNote(version: String): ReleaseNote {
                val language = StorePalFeedback.requireRuntime().languages.acceptLanguage()
                requests += language
                delay(if (language == "en") 1000 else 50)
                return ReleaseNote("r1", version, "Translated content", "2026-10-25", "Localized $language")
            }
        }
        StorePalFeedback.initialize(compose.activity, service, "localized-release")
        val tag = mutableStateOf("en")
        compose.setContent { WithLanguage(tag.value) { StorePalTheme { StorePalWhatsNewScreen({}, version = "1.1") } } }
        compose.waitUntil(5000) { requests.contains("en") }
        compose.runOnIdle { tag.value = "fr" }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Localized fr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nouveautés").assertIsDisplayed()
        compose.onNodeWithText("Localized en").assertDoesNotExist()
        assertEquals(listOf("en", "fr"), requests.toList())
    }

    @Test fun feedbackButtonUsesTheSupportedSecondaryLanguageAndKeepsItsActivity() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 24)
        StorePalFeedback.initialize(compose.activity, TestService(), "localized-button")
        compose.setContent { WithLanguage("it-IT,fr-CA") { StorePalTheme { FeedbackButton() } } }
        compose.onNodeWithText("Envoyer un avis").assertIsDisplayed().assertIsEnabled()
    }
}

@Composable
private fun WithLanguage(tags: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val original = LocalConfiguration.current
    val configuration = remember(original, tags) { Configuration(original).apply {
        if (Build.VERSION.SDK_INT >= 24) setLocales(LocaleList.forLanguageTags(tags))
        else setLocale(Locale.forLanguageTag(tags.substringBefore(',')))
    } }
    val localized = remember(configuration) { ContextThemeWrapper(base, base.theme).apply { applyOverrideConfiguration(configuration) } }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, content = content)
}

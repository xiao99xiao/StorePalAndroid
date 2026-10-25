package app.storepal.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlin.test.*
import org.junit.Test

class LocalizationTest {
    private fun context(tags: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            if (Build.VERSION.SDK_INT >= 24) setLocales(LocaleList.forLanguageTags(tags))
            else setLocale(Locale.forLanguageTag(tags.substringBefore(',')))
        }
        return base.createConfigurationContext(configuration.withStorePalLocale())
    }

    @Test fun allNineLanguagesResolveCompiledResources() {
        val expected = mapOf("en" to "Close", "zh-Hans" to "关闭", "zh-Hant" to "關閉", "ja" to "閉じる",
            "ko" to "닫기", "fr" to "Fermer", "de" to "Schließen", "es" to "Cerrar", "pt" to "Fechar")
        expected.forEach { (tag, close) ->
            val localized = context(tag)
            assertEquals(close, localized.getString(R.string.sp_close), tag)
            R.string::class.java.fields.filter { it.name.startsWith("sp_") }.forEach { field ->
                val text = localized.getString(field.getInt(null), "10", "5,000", "123")
                assertTrue(text.isNotBlank(), "$tag/${field.name}")
                assertFalse(text.contains(Regex("%\\d+\\$[a-zA-Z]")), "$tag/${field.name}")
            }
        }
    }

    @Test fun regionalVariantsAndChineseScriptsUseTheRightTranslations() {
        mapOf("fr-CA" to "Fermer", "de-AT" to "Schließen", "es-MX" to "Cerrar", "pt-PT" to "Fechar",
            "pt-BR" to "Fechar", "zh-TW" to "關閉", "zh-HK" to "關閉", "zh-Hant-HK" to "關閉",
            "zh-CN" to "关闭", "zh-SG" to "关闭", "zh-Hans-HK" to "关闭").forEach { (tag, expected) ->
            assertEquals(expected, context(tag).getString(R.string.sp_close), tag)
        }
    }

    @Test fun unsupportedLanguagesFallBackAndRespectSecondaryPreferences() {
        assertEquals("Close", context("it-IT").getString(R.string.sp_close))
        if (Build.VERSION.SDK_INT >= 24) assertEquals("Fermer", context("it-IT,fr-CA").getString(R.string.sp_close))
    }

    @Test fun replyCountsUseLocalePluralRules() {
        val expected = mapOf(
            "en" to listOf("0 replies", "1 reply", "2 replies"),
            "fr" to listOf("0 réponse", "1 réponse", "2 réponses"),
            "de" to listOf("0 Antworten", "1 Antwort", "2 Antworten"),
            "es" to listOf("0 respuestas", "1 respuesta", "2 respuestas"),
            "pt-BR" to listOf("0 resposta", "1 resposta", "2 respostas"),
            "pt-PT" to listOf("0 respostas", "1 resposta", "2 respostas"),
            "ko" to listOf("답변 0개", "답변 1개", "답변 2개"),
        )
        expected.forEach { (tag, variants) ->
            val resources = context(tag).resources
            variants.forEachIndexed { count, value -> assertEquals(value, resources.getQuantityString(R.plurals.sp_replies, count, "$count"), "$tag/$count") }
        }
    }

    @Test fun datesAndNumbersUseTheUILocale() {
        assertEquals("12,345", formatCount(12345, context("en-US").resources.configuration.primaryLocale()))
        assertEquals("12.345", formatCount(12345, context("de-DE").resources.configuration.primaryLocale()))
        val timestamp = "2026-10-25T23:59:59.123456Z"
        assertEquals("Oct 25, 2026", formatConversationDate(timestamp, Locale.US))
        assertEquals("25.10.2026", formatConversationDate(timestamp, Locale.GERMANY))
        assertEquals("2026-02-30", formatConversationDate("2026-02-30", Locale.GERMANY))
        assertEquals("", formatConversationDate("", Locale.US))
    }

    @Test fun requestLanguageUsesTheSameSupportedLocaleAsTheUI() {
        val languages = StorePalLanguages(context("en"))
        val configuration = Configuration(context("en").resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) configuration.setLocales(LocaleList.forLanguageTags("it-IT,fr-CA"))
        else configuration.setLocale(Locale.CANADA_FRENCH)
        languages.use(configuration)
        assertEquals("fr-CA", languages.acceptLanguage())
        configuration.setLocale(Locale.forLanguageTag("zh-Hant-HK"))
        languages.use(configuration)
        assertEquals("zh-Hant-HK", languages.acceptLanguage())
        assertEquals("zh-Hant-TW", storePalLocale("zh-TW").toLanguageTag())
        assertEquals("zh-Hant-MO", storePalLocale("zh-MO").toLanguageTag())
        assertEquals("zh-Hans-HK", storePalLocale("zh-Hans-HK").toLanguageTag())
    }
}

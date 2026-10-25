package app.storepal.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*
import org.junit.Test
import org.w3c.dom.Element

/** Keep translations complete as UI strings and format arguments evolve. */
class LocalizationResourcesTest {
    private val qualifiers = listOf("values", "values-zh", "values-b+zh+Hant", "values-ja", "values-ko", "values-fr", "values-de", "values-es", "values-pt")
    private val placeholders = Regex("%(\\d+)\\$([a-zA-Z])")
    private fun document(file: File) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)

    private fun resources(file: File): Map<String, List<String>> {
        val nodes = document(file).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.associate { element ->
            val values = if (element.tagName == "plurals") {
                val items = element.getElementsByTagName("item")
                assertTrue((0 until items.length).any { (items.item(it) as Element).getAttribute("quantity") == "other" }, "Missing other: $file")
                (0 until items.length).map { items.item(it).textContent }
            } else listOf(element.textContent)
            "${element.tagName}/${element.getAttribute("name")}" to values
        }
    }

    private fun checkModule(directory: File) {
        val base = resources(File(directory, "values/strings.xml"))
        qualifiers.forEach { qualifier ->
            val file = File(directory, "$qualifier/strings.xml")
            val translated = resources(file)
            assertEquals(base.keys, translated.keys, "Incomplete translation: $file")
            translated.forEach { (key, variants) ->
                val expected = placeholders.findAll(base.getValue(key).first()).map { it.value }.toSet()
                variants.forEach { value ->
                    assertTrue(value.isNotBlank(), "Empty translation: $file/$key")
                    assertEquals(expected, placeholders.findAll(value).map { it.value }.toSet(), "Format arguments differ: $file/$key")
                }
            }
        }
    }

    @Test fun `every SDK language has all strings and compatible placeholders`() { checkModule(File("src/main/res")) }
    @Test fun `the offline sample is fully translated in the same languages`() { checkModule(File("../sample/src/main/res")) }
    @Test fun `the sample advertises only its nine supported languages`() {
        val nodes = document(File("../sample/src/main/res/xml/sample_locales.xml")).getElementsByTagName("locale")
        val tags = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }.toSet()
        assertEquals(setOf("en", "zh-Hans", "zh-Hant", "ja", "ko", "fr", "de", "es", "pt"), tags)
    }
}

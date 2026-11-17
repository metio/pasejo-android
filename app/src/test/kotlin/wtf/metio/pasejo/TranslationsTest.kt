// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every language carries exactly the English strings with their placeholders. */
class TranslationsTest {
    private val res = File("src/main/res")
    private val languages = listOf("de", "es", "fr", "it", "ja", "ko", "zh")
    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    private fun strings(dir: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun everyLanguageHasTheEnglishKeys() {
        val english = strings("values")
        assertTrue(english.isNotEmpty())
        for (language in languages) {
            assertEquals(language, english.keys, strings("values-$language").keys)
        }
    }

    @Test
    fun placeholdersMatchTheEnglishOnes() {
        val english = strings("values")
        assertTrue(english.values.any { placeholder.containsMatchIn(it) })
        for (language in languages) {
            for ((key, text) in strings("values-$language")) {
                val expected = placeholder.findAll(english.getValue(key)).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(text).map { it.value }.sorted().toList()
                assertEquals("$language $key", expected, actual)
            }
        }
    }

    @Test
    fun noLanguageFolderIsLeftUntested() {
        val folders = res.listFiles().orEmpty()
            .filter { File(it, "strings.xml").exists() && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-") }
            .sorted()
        assertEquals(languages.sorted(), folders)
    }
}

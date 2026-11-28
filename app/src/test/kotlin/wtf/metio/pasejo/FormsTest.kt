// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormsTest {
    private val username = FieldInfo(hints = listOf("username"))
    private val password = FieldInfo(hints = listOf("password"))

    private fun page(domain: String?, vararg children: Node<String>) = Node(webDomain = domain, children = children.toList())

    @Test
    fun fieldsTakeTheDomainOfThePage() {
        val found = Forms.find(listOf(page("bank.example", Node("u", username), Node("p", password))))
        assertEquals(Found(Targets("u", "p", null), "bank.example"), found)
    }

    @Test
    fun aFrameFromAnotherSiteKeepsItsOwnDomain() {
        val frame = page("ads.example", Node("u", username), Node("p", password))
        val found = Forms.find(listOf(page("bank.example", frame)))
        assertEquals("ads.example", found?.webDomain)
    }

    @Test
    fun aFormSpanningTwoSitesIsNotFilled() {
        val frame = page("ads.example", Node("p", password))
        assertNull(Forms.find(listOf(page("bank.example", Node("u", username), frame))))
    }

    @Test
    fun anAppFormHasNoDomain() {
        val found = Forms.find(listOf(page(null, Node("u", username), Node("p", password))))
        assertEquals(Found(Targets("u", "p", null), null), found)
    }

    @Test
    fun fieldsInLaterWindowsAreFound() {
        val found = Forms.find(listOf(page("bank.example"), page("bank.example", Node("p", password))))
        assertEquals(Targets(null, "p", null), found?.targets)
    }

    @Test
    fun aStructureWithoutLoginFieldsGivesNothing() {
        assertNull(Forms.find(listOf(page("bank.example", Node("q", FieldInfo(hint = "search"))))))
    }

    @Test
    fun viewsThatAreNotTextFieldsAreSkipped() {
        val found = Forms.find(listOf(page("bank.example", Node(null, password), Node("u", username))))
        assertEquals(Targets("u", null, null), found?.targets)
    }
}

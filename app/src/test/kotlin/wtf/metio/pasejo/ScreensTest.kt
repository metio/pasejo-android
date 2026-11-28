// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Test
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.StoreView

class ScreensTest {
    private val store = StoreView(
        id = "s",
        label = "Team",
        secrets = listOf("mail/work", "bank", "Mail/home"),
        otp = listOf("bank", "github"),
    )

    private fun match(username: String, store: String) =
        FillMatch(store = "s", storeLabel = store, name = "n", username = username, url = null, exact = true, confirm = false, score = 1.0)

    @Test
    fun anEntryThatIsBothSecretAndCodeIsListedOnce() =
        assertEquals(listOf("Mail/home", "bank", "github", "mail/work"), entryNames(store, ""))

    @Test
    fun theFilterIgnoresCaseAndSurroundingSpaces() =
        assertEquals(listOf("Mail/home", "mail/work"), entryNames(store, "  MAIL "))

    @Test
    fun aFilterWithoutMatchesListsNothing() = assertEquals(emptyList<String>(), entryNames(store, "nothing"))

    @Test
    fun aMatchNamesItsUserAndStore() = assertEquals("alice · Team", matchDetail(match("alice", "Team")))

    @Test
    fun aMatchWithoutUserNamesOnlyItsStore() = assertEquals("Team", matchDetail(match("", "Team")))
}

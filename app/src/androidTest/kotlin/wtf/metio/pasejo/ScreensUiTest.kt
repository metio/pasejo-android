// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.StoreView

@RunWith(AndroidJUnit4::class)
class ScreensUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val store = StoreView(id = "s", label = "Team", secrets = listOf("mail", "bank"), otp = listOf("bank", "github"))

    @Test
    fun continuingNeedsTheServerTheOrganizationAndItsFingerprint() {
        var entered: SetupValues? = null
        compose.setContent {
            PasejoTheme {
                SetupScreen(SetupField(""), SetupField(""), SetupField(""), SetupField(""), Tier.HARDWARE) { entered = it }
            }
        }
        val proceed = compose.onNodeWithText(text(R.string.action_continue))
        proceed.assertIsNotEnabled()
        compose.onNodeWithTag("setup-server").performTextInput(" https://pasejo.example.com ")
        compose.onNodeWithTag("setup-org").performTextInput("0123")
        proceed.assertIsNotEnabled()
        compose.onNodeWithTag("setup-genesis").performTextInput("abcd")
        proceed.assertIsEnabled().performScrollTo().performClick()
        assertEquals(SetupValues("https://pasejo.example.com", "0123", "abcd", ""), entered)
    }

    @Test
    fun aShortScreenScrollsTheSetupToItsButton() {
        compose.setContent {
            PasejoTheme {
                Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                    SetupScreen(SetupField(""), SetupField(""), SetupField(""), SetupField(""), Tier.HARDWARE) {}
                }
            }
        }
        // Typing focuses a field below the fold, which the column scrolls into view.
        compose.onNodeWithTag("setup-genesis").performTextInput("abcd")
        // The keyboard's padding applies to the window's bottom, below this short box.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText(text(R.string.action_continue)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aManagedFieldIsLockedAndSaysWhy() {
        compose.setContent {
            PasejoTheme {
                SetupScreen(SetupField("https://pasejo.example.com", managed = true), SetupField(""), SetupField(""), SetupField(""), Tier.SEALED) {}
            }
        }
        compose.onNodeWithTag("setup-server").assertIsNotEnabled().assertTextContains("https://pasejo.example.com")
        compose.onNodeWithTag("setup-org").assertIsEnabled()
        compose.onNodeWithText(text(R.string.setup_managed)).assertExists()
        compose.onNodeWithText(text(R.string.tier_sealed)).assertExists()
    }

    @Test
    fun onlyAnActiveDeviceCanUnlock() {
        var status: LockStatus? by mutableStateOf(null)
        val fingerprint = "0123abcd 4567ef01 89abcdef 01234567 89abcdef"
        val command = approveCommand("0f0f", fingerprint, "a1b2")
        compose.setContent { PasejoTheme { LockedScreen(fingerprint, command, Tier.SEALED, status, onUnlock = {}, onReset = {}) } }
        val unlock = compose.onNodeWithText(text(R.string.action_unlock))
        unlock.assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.locked_checking)).assertExists()
        compose.onNodeWithTag("fingerprint").assertTextEquals(fingerprint)
        compose.onNodeWithTag("approve").assertTextEquals(command)
        compose.onNodeWithText(text(R.string.tier_sealed)).assertExists()
        status = LockStatus(text(R.string.locked_pending, "metio"), canUnlock = false)
        compose.onNodeWithText(text(R.string.locked_pending, "metio")).assertExists()
        unlock.assertIsNotEnabled()
        status = LockStatus(text(R.string.locked_active, "metio"), canUnlock = true)
        unlock.assertIsEnabled()
    }

    @Test
    fun theUnlockPeriodIsChosenAmongThePolicysChoices() {
        var chosen: Int? = null
        compose.setContent {
            PasejoTheme {
                StoresScreen(
                    listOf(store), offline = false, offerAutofill = false,
                    unlockChoices = ManagedConfig(maxUnlockSeconds = 600).unlockChoices(), unlockSeconds = 300,
                    onChooseUnlock = { chosen = it }, onOpen = {}, onAutofill = {}, onLock = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.unlock_minutes, 5)).assertIsSelected()
        compose.onNodeWithText(text(R.string.unlock_minutes, 15)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.unlock_minutes, 1)).performClick()
        assertEquals(60, chosen)
    }

    @Test
    fun theFilterNarrowsTheEntries() {
        var opened: String? = null
        compose.setContent { PasejoTheme { EntriesScreen(store, onOpen = { opened = it }, onBack = {}) } }
        compose.onNodeWithText("github").assertExists()
        compose.onNodeWithTag("filter").performTextInput("BA")
        compose.onNodeWithText("github").assertDoesNotExist()
        compose.onNodeWithText("bank").performClick()
        assertEquals("bank", opened)
    }

    @Test
    fun valuesStayHiddenAndCopyingNeedsThePolicy() {
        var value: Shown by mutableStateOf(Shown.Hidden)
        var revealed = 0
        compose.setContent {
            PasejoTheme {
                EntryScreen(
                    name = "bank", secret = true, otp = true, allowCopy = false,
                    value = value, code = Shown.Hidden,
                    onReveal = { revealed++ }, onCopyPassword = {}, onShowCode = {}, onCopyCode = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithTag("value").assertTextEquals(text(R.string.entry_hidden))
        compose.onNodeWithTag("code").assertTextEquals(text(R.string.entry_hidden))
        compose.onNodeWithText(text(R.string.action_copy_password)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_copy_code)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_reveal)).performClick()
        assertEquals(1, revealed)
        value = Shown.Value("hunter2")
        compose.onNodeWithTag("value").assertTextEquals("hunter2")
    }

    @Test
    fun copyingIsOfferedWhenThePolicyAllowsIt() {
        compose.setContent {
            PasejoTheme {
                EntryScreen(
                    name = "bank", secret = true, otp = true, allowCopy = true,
                    value = Shown.Hidden, code = Shown.Hidden,
                    onReveal = {}, onCopyPassword = {}, onShowCode = {}, onCopyCode = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.action_copy_password)).assertExists()
        compose.onNodeWithText(text(R.string.action_copy_code)).assertExists()
    }

    @Test
    fun aCodeCountsDownAndAsksForTheNextOne() {
        var expired = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { PasejoTheme { Text(countdown("123456", 2, generation = 1) { expired++ }, Modifier.testTag("countdown")) } }
        compose.onNodeWithTag("countdown").assertTextEquals(text(R.string.entry_code, "123 456", 2))
        // The first tick follows the first frame, so each step allows that frame.
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("countdown").assertTextEquals(text(R.string.entry_code, "123 456", 1))
        assertEquals(0, expired)
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        assertEquals(1, expired)
    }

    @Test
    fun aFormWithoutMatchesWarnsAboutLookalikes() {
        compose.setContent {
            PasejoTheme {
                FillPickerScreen("For examp1e.com", offline = false, matches = emptyList(), lookalikes = listOf("example.com"), onChoose = {}, onCancel = {})
            }
        }
        compose.onNodeWithText(text(R.string.fill_lookalikes, "example.com")).assertExists()
        compose.onNodeWithText(text(R.string.fill_no_matches)).assertExists()
    }

    @Test
    fun choosingAMatchHandsItOn() {
        val match = FillMatch(store = "s", storeLabel = "Team", name = "mail", username = "alice", url = null, exact = true, confirm = false, score = 1.0)
        var chosen: FillMatch? = null
        compose.setContent {
            PasejoTheme {
                FillPickerScreen("For example.com", offline = true, matches = listOf(match), lookalikes = listOf("examp1e.com"), onChoose = { chosen = it }, onCancel = {})
            }
        }
        compose.onNodeWithText(text(R.string.offline_notice)).assertExists()
        compose.onNodeWithText(text(R.string.fill_lookalikes, "examp1e.com")).assertDoesNotExist()
        assertNull(chosen)
        compose.onNodeWithText("alice · Team").performClick()
        assertEquals(match, chosen)
    }

    @Test
    fun withoutWallpaperColoursThePasejoPaletteApplies() {
        var light: Color? = null
        var dark: Color? = null
        compose.setContent {
            PasejoTheme(dark = false, wallpaperColours = false) { light = MaterialTheme.colorScheme.primary }
            PasejoTheme(dark = true, wallpaperColours = false) { dark = MaterialTheme.colorScheme.primary }
        }
        compose.waitForIdle()
        assertEquals(PasejoLight.primary, light)
        assertEquals(PasejoDark.primary, dark)
    }

}

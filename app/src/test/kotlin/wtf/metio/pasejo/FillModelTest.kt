// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.metio.pasejo.core.DeviceInterface
import wtf.metio.pasejo.core.FillCredential

class FillModelTest {
    private class FakeFillEnvironment(device: DeviceInterface?, override val browser: Boolean) :
        FakePlatform(device), FillEnvironment<String> {
        val delivered = mutableListOf<List<Pair<String, String>>>()
        var closed = false

        override fun verified(domain: String) = false
        override fun deliver(values: List<Pair<String, String>>) {
            delivered += values
        }
        override fun close() {
            closed = true
        }
    }

    private val targets = Targets("u", "p", "o")
    private val site = Caller("com.android.chrome", listOf("AB"), "bank.example")

    private fun model(
        device: FakeDevice? = FakeDevice(),
        caller: Caller = site,
        browser: Boolean = true,
    ): Pair<FillModel<String>, FakeFillEnvironment> {
        val env = FakeFillEnvironment(device, browser)
        return FillModel(env, targets, caller) to env
    }

    @Test
    fun withoutADeviceTheScreenCloses() {
        val (model, env) = model(device = null)
        model.start()
        assertTrue(env.closed)
    }

    @Test
    fun aBrowserWithoutADomainHasNothingToFill() {
        val (model, env) = model(caller = site.copy(webDomain = null))
        model.start()
        env.finishAll()
        assertEquals("text ${R.string.fill_nothing} []", model.ui.text)
        assertEquals(emptyList<Any>(), env.fake.queries)
    }

    @Test
    fun anOpenDeviceListsTheMatchesForTheSite() {
        val (model, env) = model()
        model.start()
        env.finishAll()
        assertEquals(listOf("bank.example"), model.ui.found?.request?.domains)
        assertEquals(listOf("mail"), model.ui.found?.matches?.map { it.name })
        assertEquals(0, env.authentications)
        assertEquals(emptyList<String>(), env.fake.outsideCalls)
    }

    @Test
    fun aClosedDeviceAuthenticatesBeforeTheQuery() {
        val (model, env) = model()
        env.periodOpen = false
        model.start()
        env.finishAll()
        assertEquals(1, env.authentications)
        assertTrue(env.periodOpen)
        assertEquals(1, env.fake.queries.size)
    }

    @Test
    fun aCancelledPromptShowsTheError() {
        val (model, env) = model()
        env.periodOpen = false
        env.authentication = Result.failure(SecurityException("cancelled"))
        model.start()
        env.finishAll()
        assertEquals("error cancelled", model.ui.text)
        assertEquals(emptyList<Any>(), env.fake.queries)
    }

    @Test
    fun choosingAMatchDeliversItsValues() {
        val (model, env) = model()
        model.start()
        env.finishAll()
        model.choose(match("mail"))
        env.finishAll()
        assertEquals(listOf(listOf("u" to "me", "p" to "hunter2", "o" to "123456")), env.delivered)
        assertEquals(1, env.saves)
    }

    @Test
    fun aSecretThatAsksIsConfirmedFirst() {
        val (model, env) = model()
        model.start()
        env.finishAll()
        model.choose(match("bank", confirm = true))
        assertEquals("bank", model.ui.confirming?.name)
        assertTrue(env.queue.isEmpty())
        model.confirm()
        env.finishAll()
        assertNull(model.ui.confirming)
        assertEquals(listOf("bank"), env.fake.fills)
    }

    @Test
    fun aDismissedConfirmationFillsNothing() {
        val (model, env) = model()
        model.start()
        env.finishAll()
        model.choose(match("bank", confirm = true))
        model.dismiss()
        model.confirm()
        env.finishAll()
        assertEquals(emptyList<String>(), env.fake.fills)
    }

    @Test
    fun storesThatClosedMeanwhileFillNothing() {
        val (model, env) = model()
        model.start()
        env.finishAll()
        env.periodOpen = false
        model.choose(match("mail"))
        env.finishAll()
        assertEquals("error Locked", model.ui.text)
        assertEquals(emptyList<String>(), env.fake.fills)
    }

    @Test
    fun aSecondTapWhileFillingFillsOnce() {
        val (model, env) = model()
        model.start()
        env.finishAll()
        model.choose(match("mail"))
        model.choose(match("mail"))
        env.finishAll()
        assertEquals(1, env.delivered.size)
    }

    @Test
    fun aCredentialWithNothingForTheFormSaysSo() {
        val (model, env) = model()
        env.fake.credential = FillCredential("", "", null)
        model.start()
        env.finishAll()
        model.choose(match("mail"))
        env.finishAll()
        assertEquals("text ${R.string.fill_empty} []", model.ui.text)
        assertEquals(emptyList<Any>(), env.delivered)
    }

    @Test
    fun theStoresAreCheckedInsideTheRequestWhileOthersRun() {
        val (model, env) = model()
        model.start()
        // Another screen's request, still running when the build finishes.
        env.background({ }) { }
        env.finish()
        env.finishAll()
        assertEquals(emptyList<String>(), env.fake.outsideCalls)
    }

    @Test
    fun valuesGoOnlyToFieldsTheFormHas() =
        assertEquals(
            listOf("p" to "hunter2"),
            fillValues(Targets(null, "p", "o"), FillCredential("me", "hunter2", null)),
        )
}

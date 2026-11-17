// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetsTest {
    @Test
    fun theFirstFieldOfEachKindIsPicked() {
        val targets = Targets.pick(
            listOf(
                "search" to FieldInfo(idEntry = "search"),
                "user" to FieldInfo(hints = listOf("username")),
                "pass" to FieldInfo(hints = listOf("password")),
                "pass2" to FieldInfo(hints = listOf("password")),
                "otp" to FieldInfo(hints = listOf("one-time-code")),
            ),
        )
        assertEquals(Targets("user", "pass", "otp"), targets)
        assertEquals(listOf("user", "pass", "otp"), targets!!.ids)
    }

    @Test
    fun aUsernameStepAloneIsAForm() {
        assertEquals(Targets("user", null, null), Targets.pick(listOf("user" to FieldInfo(htmlType = "email"))))
    }

    @Test
    fun aFormWithoutLoginFieldsIsNone() {
        assertNull(Targets.pick(listOf("q" to FieldInfo(idEntry = "query"))))
        assertNull(Targets.pick(emptyList<Pair<String, FieldInfo>>()))
    }
}

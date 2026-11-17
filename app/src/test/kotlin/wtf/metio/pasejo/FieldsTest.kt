// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldsTest {
    private val textPassword = 0x00000001 or 0x00000080
    private val webPassword = 0x00000001 or 0x000000e0
    private val visiblePassword = 0x00000001 or 0x00000090
    private val numberPassword = 0x00000002 or 0x00000010
    private val email = 0x00000001 or 0x00000020
    private val plainText = 0x00000001

    @Test
    fun declaredHintsWin() {
        assertEquals(FieldKind.PASSWORD, Fields.classify(FieldInfo(hints = listOf("password"), inputType = email)))
        assertEquals(FieldKind.USERNAME, Fields.classify(FieldInfo(hints = listOf("username"))))
        assertEquals(FieldKind.USERNAME, Fields.classify(FieldInfo(hints = listOf("emailAddress"))))
        assertEquals(FieldKind.OTP, Fields.classify(FieldInfo(hints = listOf("smsOTPCode"))))
    }

    @Test
    fun htmlAutocompleteIsRead() {
        assertEquals(FieldKind.PASSWORD, Fields.classify(FieldInfo(htmlAutocomplete = "current-password")))
        assertEquals(FieldKind.USERNAME, Fields.classify(FieldInfo(htmlAutocomplete = "section-login username")))
        assertEquals(FieldKind.OTP, Fields.classify(FieldInfo(htmlAutocomplete = "one-time-code")))
    }

    @Test
    fun newPasswordFieldsAreNeverFilled() {
        assertNull(Fields.classify(FieldInfo(htmlAutocomplete = "new-password", htmlType = "password")))
    }

    @Test
    fun passwordInputTypesArePasswords() {
        for (type in listOf(textPassword, webPassword, visiblePassword, numberPassword)) {
            assertEquals("input type $type", FieldKind.PASSWORD, Fields.classify(FieldInfo(inputType = type)))
        }
        assertEquals(FieldKind.PASSWORD, Fields.classify(FieldInfo(htmlType = "password")))
    }

    @Test
    fun emailInputsAreUsernames() {
        assertEquals(FieldKind.USERNAME, Fields.classify(FieldInfo(inputType = email)))
        assertEquals(FieldKind.USERNAME, Fields.classify(FieldInfo(htmlType = "email")))
    }

    @Test
    fun namesAreTheLastResort() {
        assertEquals(FieldKind.USERNAME, Fields.classify(FieldInfo(inputType = plainText, idEntry = "login_user")))
        assertEquals(FieldKind.PASSWORD, Fields.classify(FieldInfo(inputType = plainText, hint = "Passphrase")))
        assertEquals(FieldKind.OTP, Fields.classify(FieldInfo(inputType = plainText, hint = "Verification code")))
    }

    @Test
    fun unrelatedFieldsAreIgnored() {
        assertNull(Fields.classify(FieldInfo(inputType = plainText)))
        assertNull(Fields.classify(FieldInfo(inputType = plainText, idEntry = "search", hint = "Coupon code")))
        assertNull(Fields.classify(FieldInfo(hints = listOf("postalCode"))))
    }
}

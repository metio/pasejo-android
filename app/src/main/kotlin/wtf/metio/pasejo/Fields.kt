// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import java.util.Locale

/** What a form field asks for. */
enum class FieldKind { USERNAME, PASSWORD, OTP }

/** What the autofill framework reports about one view, without Android types. */
data class FieldInfo(
    val hints: List<String> = emptyList(),
    val inputType: Int = 0,
    val htmlType: String? = null,
    val htmlAutocomplete: String? = null,
    val idEntry: String? = null,
    val hint: String? = null,
)

/** Recognizes login fields, preferring declared hints over guesses from names. */
object Fields {
    private const val CLASS_MASK = 0x0000000f
    private const val VARIATION_MASK = 0x00000ff0
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private val TEXT_PASSWORDS = setOf(0x00000080, 0x00000090, 0x000000e0)
    private const val NUMBER_PASSWORD = 0x00000010
    private val TEXT_EMAILS = setOf(0x00000020, 0x000000d0)

    private val PASSWORD_HINTS = setOf("password", "current-password")
    private val USERNAME_HINTS = setOf("username", "emailaddress", "email", "phone")
    private val OTP_HINTS = setOf("smsotpcode", "one-time-code", "2faappotpcode")
    private val USERNAME_WORDS = listOf("user", "login", "email", "e-mail", "account")
    private val OTP_WORDS = listOf("otp", "totp", "2fa", "one-time", "onetime", "verification code", "authenticator")
    private val PASSWORD_WORDS = listOf("password", "passwd", "passphrase")

    fun classify(field: FieldInfo): FieldKind? {
        val hints = field.hints.map { it.lowercase(Locale.ROOT) }
        val autocomplete = field.htmlAutocomplete?.lowercase(Locale.ROOT)?.split(' ').orEmpty()
        if ("new-password" in autocomplete) return null
        if (hints.any { it in OTP_HINTS } || "one-time-code" in autocomplete) return FieldKind.OTP
        if (hints.any { it in PASSWORD_HINTS } || "current-password" in autocomplete) return FieldKind.PASSWORD
        if (hints.any { it in USERNAME_HINTS } || autocomplete.any { it == "username" || it == "email" }) {
            return FieldKind.USERNAME
        }
        val inputClass = field.inputType and CLASS_MASK
        val variation = field.inputType and VARIATION_MASK
        val htmlType = field.htmlType?.lowercase(Locale.ROOT)
        if (htmlType == "password" || inputClass == CLASS_TEXT && variation in TEXT_PASSWORDS ||
            inputClass == CLASS_NUMBER && variation == NUMBER_PASSWORD
        ) {
            return FieldKind.PASSWORD
        }
        if (htmlType == "email" || inputClass == CLASS_TEXT && variation in TEXT_EMAILS) return FieldKind.USERNAME
        val words = listOfNotNull(field.idEntry, field.hint).joinToString(" ").lowercase(Locale.ROOT)
        return when {
            words.isEmpty() -> null
            OTP_WORDS.any { it in words } -> FieldKind.OTP
            PASSWORD_WORDS.any { it in words } -> FieldKind.PASSWORD
            USERNAME_WORDS.any { it in words } -> FieldKind.USERNAME
            else -> null
        }
    }
}

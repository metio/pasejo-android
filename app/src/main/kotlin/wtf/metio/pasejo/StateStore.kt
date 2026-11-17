// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device state, encrypted under an AES-GCM key of Android's key store.
 * The state holds no private key, but it carries the session and the pins
 * that detect a rolled-back server, so it must not be readable or
 * replaceable by anyone but the app.
 */
class StateStore(private val file: File, private val alias: String = "pasejo.state") {
    private val store: KeyStore = KeyStore.getInstance(DeviceKeys.ANDROID_KEY_STORE).apply { load(null) }

    fun save(state: String) {
        val plain = state.toByteArray()
        try {
            val cipher = Cipher.getInstance(GCM).apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(AAD)
            }
            val sealed = cipher.iv + cipher.doFinal(plain)
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(sealed)
            check(temporary.renameTo(file)) { "cannot replace ${file.name}" }
        } finally {
            plain.fill(0)
        }
    }

    /** The saved state, or `null` when there is none. */
    fun load(): String? {
        if (!file.exists()) return null
        val sealed = file.readBytes()
        require(sealed.size > IV_BYTES) { "the device state is truncated" }
        val key = store.getKey(alias, null) as? SecretKey ?: error("the device state key is gone")
        val cipher = Cipher.getInstance(GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
            updateAAD(AAD)
        }
        val plain = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        return try {
            String(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun delete() {
        file.delete()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun key(): SecretKey = store.getKey(alias, null) as? SecretKey
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, DeviceKeys.ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

    private companion object {
        const val GCM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val AAD = "pasejo device state v1".toByteArray()
    }
}

/**
 * The certificate authorities the phone trusts: the ones Android ships and
 * the ones the person or a device management system installed, which is
 * how a company authority reaches a managed phone.
 */
object TrustAnchors {
    fun system(): List<ByteArray> {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return store.aliases().toList().mapNotNull { store.getCertificate(it)?.encoded }
    }
}

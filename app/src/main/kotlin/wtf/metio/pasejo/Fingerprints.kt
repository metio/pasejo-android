// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import java.security.MessageDigest

/** SHA-256 fingerprints of signing certificates, in one spelling. */
object Fingerprints {
    /** Upper-case hex without separators. */
    fun normalize(fingerprint: String): String =
        fingerprint.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.uppercase()

    fun sha256(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02X".format(it) }
}

/** Raw Curve25519 keys and their SubjectPublicKeyInfo encodings (RFC 8410). */
object Spki {
    private val X25519_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    )

    /**
     * The raw 32-byte key at the end of an encoded Ed25519 or X25519 public
     * key. `XDHPublicKey.getU()` cannot be used: it reads the little-endian
     * bytes as a big-endian integer.
     */
    fun raw(encoded: ByteArray): ByteArray {
        require(encoded.size >= 32) { "not a Curve25519 public key" }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    /** The SubjectPublicKeyInfo of a raw X25519 public key. */
    fun x25519(raw: ByteArray): ByteArray {
        require(raw.size == 32) { "not a raw X25519 public key" }
        return X25519_PREFIX + raw
    }
}

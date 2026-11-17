// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.io.File
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import wtf.metio.pasejo.core.DeviceSigner
import wtf.metio.pasejo.core.KeyAgreement
import wtf.metio.pasejo.core.MobileException
import wtf.metio.pasejo.core.generateSecret
import wtf.metio.pasejo.core.softwareAgreement
import wtf.metio.pasejo.core.softwareSigner

/** Where the device keys live. */
enum class Tier {
    /** Both private keys are generated and used inside KeyMint. */
    HARDWARE,

    /** The keys are generated in memory and sealed under KeyMint keys. */
    SEALED,
}

/** A key store that keeps keys in software, which the app refuses. */
class InsecureKeyStoreException(message: String) : GeneralSecurityException(message)

/** The phone has no secure lock screen, which the age identity needs. */
class NoScreenLockException : GeneralSecurityException("the phone has no secure lock screen")

/**
 * The device's Ed25519 signing key and X25519 age identity in Android's key
 * store. Every key must report a trusted execution environment or StrongBox;
 * keys are excluded from backup, so a new phone is a new device.
 */
class DeviceKeys(
    private val dir: File,
    private val requireAuthentication: Boolean = true,
    private val requireHardware: Boolean = true,
) {
    private val store: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    private val tierFile = File(dir, "tier")
    private val agreementPublicFile = File(dir, "agreement.pub")
    private val sealedSigningFile = File(dir, "signing.sealed")
    private val sealedAgreementFile = File(dir, "agreement.sealed")

    val tier: Tier?
        get() = tierFile.takeIf(File::exists)?.readText()?.let { runCatching { Tier.valueOf(it) }.getOrNull() }

    /** Creates both keys in `tier`, refusing a key store without secure hardware. */
    fun create(tier: Tier) {
        destroy()
        dir.mkdirs()
        try {
            when (tier) {
                Tier.HARDWARE -> createHardware()
                Tier.SEALED -> createSealed()
            }
            if (requireHardware) requireSecureHardware(aliases(tier))
            tierFile.writeText(tier.name)
        } catch (error: Exception) {
            destroy()
            throw error
        }
    }

    /** Deletes every key and sealed blob. */
    fun destroy() {
        for (alias in ALL_ALIASES) {
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
        listOf(tierFile, agreementPublicFile, sealedSigningFile, sealedAgreementFile).forEach(File::delete)
    }

    /** The signing key, usable without authentication. */
    fun signer(): DeviceSigner = when (tier) {
        Tier.HARDWARE -> KeyStoreSigner(privateKey(SIGNING), Spki.raw(store.getCertificate(SIGNING).publicKey.encoded))
        Tier.SEALED -> {
            val seed = unsealGcm(secretKey(SEAL_SIGNING), sealedSigningFile.readBytes())
            try {
                softwareSigner(seed)
            } finally {
                seed.fill(0)
            }
        }
        null -> throw IllegalStateException("no device keys")
    }

    /** The raw X25519 public key of the age identity. */
    fun agreementPublicKey(): ByteArray = when (tier) {
        Tier.HARDWARE -> Spki.raw(store.getCertificate(AGREEMENT).publicKey.encoded)
        Tier.SEALED -> agreementPublicFile.readBytes()
        null -> throw IllegalStateException("no device keys")
    }

    /**
     * The cipher the person authenticates for in the sealed tier, passed to
     * `BiometricPrompt` as its crypto object. The hardware tier
     * authenticates for a time window instead and returns `null`.
     */
    fun unsealCipher(): Cipher? = when (tier) {
        Tier.SEALED -> Cipher.getInstance(RSA_OAEP).apply {
            init(Cipher.DECRYPT_MODE, privateKey(SEAL_AGREEMENT), OAEP)
        }
        else -> null
    }

    /** The age identity, after the person authenticated (with `cipher` in the sealed tier). */
    fun agreement(cipher: Cipher?): KeyAgreement = when (tier) {
        Tier.HARDWARE -> KeyStoreAgreement(privateKey(AGREEMENT), agreementPublicKey())
        Tier.SEALED -> {
            val unsealer = requireNotNull(cipher) { "the sealed tier needs the authenticated cipher" }
            val secret = unsealer.doFinal(sealedAgreementFile.readBytes())
            try {
                softwareAgreement(secret)
            } finally {
                secret.fill(0)
            }
        }
        null -> throw IllegalStateException("no device keys")
    }

    /** The security level of every key, for display and for tests. */
    fun securityLevels(): Map<String, Int> {
        val current = tier ?: return emptyMap()
        return aliases(current).associateWith { securityLevel(store.getKey(it, null)) }
    }

    private fun aliases(tier: Tier) = when (tier) {
        Tier.HARDWARE -> listOf(SIGNING, AGREEMENT)
        Tier.SEALED -> listOf(SEAL_SIGNING, SEAL_AGREEMENT)
    }

    private fun createHardware() {
        generatePair(
            "EC",
            KeyGenParameterSpec.Builder(SIGNING, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                .setDigests(KeyProperties.DIGEST_NONE)
                .unlockedDeviceRequired(),
        )
        generatePair(
            "XDH",
            KeyGenParameterSpec.Builder(AGREEMENT, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("x25519"))
                .authenticated(AUTHENTICATION_WINDOW_SECONDS),
        )
    }

    private fun createSealed() {
        val sealing = generateSecretKey(
            KeyGenParameterSpec.Builder(SEAL_SIGNING, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .unlockedDeviceRequired(),
        )
        val seed = generateSecret()
        try {
            sealedSigningFile.writeBytes(sealGcm(sealing, seed))
        } finally {
            seed.fill(0)
        }
        // The age identity is sealed to an RSA key whose private half needs
        // an authentication per use; sealing needs only the public half, so
        // enrollment asks for no authentication.
        generatePair(
            "RSA",
            KeyGenParameterSpec.Builder(SEAL_AGREEMENT, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .authenticated(0),
            strongBox = false,
        )
        val secret = generateSecret()
        try {
            agreementPublicFile.writeBytes(softwareAgreement(secret.copyOf()).publicKey())
            val sealer = Cipher.getInstance(RSA_OAEP)
            val public = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(store.getCertificate(SEAL_AGREEMENT).publicKey.encoded))
            sealer.init(Cipher.ENCRYPT_MODE, public, OAEP)
            sealedAgreementFile.writeBytes(sealer.doFinal(secret))
        } finally {
            secret.fill(0)
        }
    }

    private fun KeyGenParameterSpec.Builder.unlockedDeviceRequired() = apply {
        // The flag misbehaves on Android 12 to 14: keys become unusable
        // without a secure lock screen and are deleted when it is removed.
        if (Build.VERSION.SDK_INT >= 35) setUnlockedDeviceRequired(true)
    }

    private fun KeyGenParameterSpec.Builder.authenticated(seconds: Int) = apply {
        if (requireAuthentication) {
            setUserAuthenticationRequired(true)
            setUserAuthenticationParameters(
                seconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            setInvalidatedByBiometricEnrollment(true)
        }
    }

    private fun generatePair(algorithm: String, spec: KeyGenParameterSpec.Builder, strongBox: Boolean = false) {
        val generator = KeyPairGenerator.getInstance(algorithm, ANDROID_KEY_STORE)
        if (strongBox) {
            try {
                generator.initialize(spec.setIsStrongBoxBacked(true).build())
                generator.generateKeyPair()
                return
            } catch (_: StrongBoxUnavailableException) {
                spec.setIsStrongBoxBacked(false)
            }
        }
        generator.initialize(spec.build())
        generator.generateKeyPair()
    }

    private fun generateSecretKey(spec: KeyGenParameterSpec.Builder): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        return try {
            generator.init(spec.setIsStrongBoxBacked(true).build())
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec.setIsStrongBoxBacked(false).build())
            generator.generateKey()
        }
    }

    private fun requireSecureHardware(aliases: List<String>) {
        for (alias in aliases) {
            val level = securityLevel(store.getKey(alias, null))
            if (level != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT &&
                level != KeyProperties.SECURITY_LEVEL_STRONGBOX
            ) {
                throw InsecureKeyStoreException("the key store keeps $alias in software (security level $level)")
            }
        }
    }

    private fun privateKey(alias: String) = store.getKey(alias, null) as PrivateKey

    private fun secretKey(alias: String) = store.getKey(alias, null) as SecretKey

    companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val SIGNING = "pasejo.signing"
        const val AGREEMENT = "pasejo.agreement"
        const val SEAL_SIGNING = "pasejo.seal.signing"
        const val SEAL_AGREEMENT = "pasejo.seal.agreement"
        private val ALL_ALIASES = listOf(SIGNING, AGREEMENT, SEAL_SIGNING, SEAL_AGREEMENT)

        /** How long one authentication opens the hardware tier's age identity. */
        const val AUTHENTICATION_WINDOW_SECONDS = 15

        private const val RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        private const val GCM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        /** The tier this phone supports: Curve25519 in KeyMint needs KeyMint 2. */
        fun supportedTier(hasHardwareKeystore200: Boolean): Tier =
            if (hasHardwareKeystore200) Tier.HARDWARE else Tier.SEALED

        fun securityLevel(key: Key): Int {
            val info = when (key) {
                is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE)
                    .getKeySpec(key, KeyInfo::class.java) as KeyInfo
                // Curve25519 keys report EdDSA or XDH, which the key store's
                // key factory does not answer to; its EC factory reads them.
                else -> KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                    .takeIf { key.algorithm != KeyProperties.KEY_ALGORITHM_RSA }
                    ?.getKeySpec(key, KeyInfo::class.java)
                    ?: KeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE).getKeySpec(key, KeyInfo::class.java)
            }
            return info.securityLevel
        }

        /** IV and ciphertext under an AES-GCM key of the key store. */
        fun sealGcm(key: SecretKey, plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(plain)
        }

        fun unsealGcm(key: SecretKey, sealed: ByteArray): ByteArray {
            require(sealed.size > IV_BYTES) { "sealed data is truncated" }
            val cipher = Cipher.getInstance(GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
            return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    }
}

/** Signs inside KeyMint. */
class KeyStoreSigner(private val key: PrivateKey, private val public: ByteArray) : DeviceSigner {
    override fun publicKey(): ByteArray = public.copyOf()

    override fun sign(message: ByteArray): ByteArray = try {
        Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            sign()
        }
    } catch (error: GeneralSecurityException) {
        throw MobileException.Key(error.message ?: error.javaClass.simpleName)
    }
}

/** Agrees inside KeyMint; only the shared secret leaves it. */
class KeyStoreAgreement(private val key: PrivateKey, private val public: ByteArray) : KeyAgreement {
    override fun publicKey(): ByteArray = public.copyOf()

    override fun agree(share: ByteArray): ByteArray = try {
        val peer = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(Spki.x25519(share)))
        javax.crypto.KeyAgreement.getInstance("XDH", DeviceKeys.ANDROID_KEY_STORE).run {
            init(key)
            doPhase(peer, true)
            generateSecret()
        }
    } catch (_: UserNotAuthenticatedException) {
        throw MobileException.Key("authentication required")
    } catch (error: GeneralSecurityException) {
        throw MobileException.Key(error.message ?: error.javaClass.simpleName)
    }
}

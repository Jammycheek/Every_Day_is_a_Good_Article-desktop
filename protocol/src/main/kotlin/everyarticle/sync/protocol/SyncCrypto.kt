package everyarticle.sync.protocol

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives available on JDK 17 and Android API 26 without extra providers. */
object SyncCrypto {
    private val random = SecureRandom()
    // NIST P-256 group order: rejects keys on any other curve even if the provider accepts them.
    private val P256_ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    fun randomHex(size: Int): String = randomBytes(size).hex()

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(inputKey: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, inputKey)
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous + CanonicalWriter.utf8(info) + byteArrayOf(counter.toByte()))
            val count = minOf(previous.size, length - offset)
            previous.copyInto(result, offset, 0, count)
            offset += count; counter++
        }
        return result
    }

    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad); doFinal(plaintext)
        }
    }

    /** @throws ProtocolFormatException when authentication fails; never returns unauthenticated bytes. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32)
        if (nonce.size != 12) throw ProtocolFormatException("Invalid nonce")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad); doFinal(ciphertext)
            }
        } catch (_: javax.crypto.AEADBadTagException) { throw ProtocolFormatException("Message authentication failed") }
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        val key = try { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) }
            catch (_: Exception) { throw ProtocolFormatException("Invalid public key") }
        if (key !is ECPublicKey || key.params.order != P256_ORDER) throw ProtocolFormatException("Public key is not P-256")
        return key
    }

    fun agree(private: PrivateKey, public: PublicKey): ByteArray = try {
        KeyAgreement.getInstance("ECDH").run { init(private); doPhase(public, true); generateSecret() }
    } catch (_: java.security.GeneralSecurityException) { throw ProtocolFormatException("Key agreement failed") }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

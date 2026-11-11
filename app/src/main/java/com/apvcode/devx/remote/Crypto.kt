package com.apvcode.devx.remote

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of the link to the computer: P-256 (ECDH to agree on a key, ECDSA to prove who is who),
 * HKDF-SHA256 and AES-256-GCM. Only what Android has built in. The wire format is in devx/docs/remote-protocol.md
 * and is the same as src/remote/crypto.ts on the computer.
 */
interface Signer {
    /** The public key as a raw uncompressed point: 65 bytes. */
    val publicKey: ByteArray
    /** ECDSA with SHA-256, signature in DER. */
    fun sign(data: ByteArray): ByteArray
}

/** The phone's identity: a key that lives in the Android Keystore and never leaves it. */
class KeystoreSigner(private val alias: String = "devx_remote_identity") : Signer {
    private val priv: PrivateKey
    override val publicKey: ByteArray

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val gen = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            gen.initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            gen.generateKeyPair()
        }
        priv = ks.getKey(alias, null) as PrivateKey
        publicKey = RemoteCrypto.rawPoint(ks.getCertificate(alias).publicKey as ECPublicKey)
    }

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }
}

/** A key in memory (tests). */
class SoftSigner(pair: KeyPair = RemoteCrypto.newKeyPair()) : Signer {
    private val priv = pair.private
    override val publicKey: ByteArray = RemoteCrypto.rawPoint(pair.public as ECPublicKey)
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }
}

class Ephemeral(private val pair: KeyPair) {
    val publicKey: ByteArray = RemoteCrypto.rawPoint(pair.public as ECPublicKey)
    fun agree(peer: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(RemoteCrypto.publicFromRaw(peer), true); generateSecret() }
}

object RemoteCrypto {
    private val random = SecureRandom()
    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run { init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java) }
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }

    fun newEphemeral(): Ephemeral = Ephemeral(newKeyPair())

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun rawPoint(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    fun publicFromRaw(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "bad public key" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(publicFromRaw(pub)); update(data); verify(sig) }
    } catch (_: Exception) {
        false
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    class Keys(val c2s: ByteArray, val s2c: ByteArray, val sas: String)

    fun deriveKeys(shared: ByteArray, salt: ByteArray): Keys {
        val okm = hkdf(shared, salt, "devx1 keys".toByteArray(), 68)
        val n = ((okm[64].toLong() and 0xff) shl 24) or ((okm[65].toLong() and 0xff) shl 16) or ((okm[66].toLong() and 0xff) shl 8) or (okm[67].toLong() and 0xff)
        return Keys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), (n % 1_000_000L).toString().padStart(6, '0'))
    }

    val HOST_LABEL = "devx1-host".toByteArray()
    val CLIENT_LABEL = "devx1-client".toByteArray()
}

/** One direction of the encrypted channel: `seq (8 bytes) || ciphertext || tag (16)`, the nonce is the sequence number. */
class Sealer(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private var seq = 0L

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val s = ByteArray(8).also { java.nio.ByteBuffer.wrap(it).putLong(seq++) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(4) + s))
        c.updateAAD(s)
        return s + c.doFinal(plain)
    }
}

class Opener(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private var last = -1L

    /** The plain bytes, or null when the frame is forged, damaged, or a repeat of an older one. */
    @Synchronized
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 8 + 16) return null
        val s = frame.copyOfRange(0, 8)
        val n = java.nio.ByteBuffer.wrap(s).long
        if (n <= last) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ByteArray(4) + s))
            c.updateAAD(s)
            val plain = c.doFinal(frame, 8, frame.size - 8)
            last = n
            plain
        } catch (_: Exception) {
            null
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.fromHex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

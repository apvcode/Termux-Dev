package com.apvcode.devx.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/** The numbers below were made by the TypeScript side (src/remote/crypto.ts): both ends must agree on every byte. */
class RemoteCryptoTest {
    private val key1 = ByteArray(32) { 1 }

    @Test fun framesMatchTheComputersBytes() {
        val s = Sealer(key1)
        assertEquals("0000000000000000d750f4b01a9ec2ef24ad3c4af996831c20f352cdfb", s.seal("hello".toByteArray()).toHex())
        assertEquals("0000000000000001c0f8fba5e61c010074caebc5c8a058b83b4d1d3899c6324be1", s.seal("{\"k\":\"x\"}".toByteArray()).toHex())
    }

    @Test fun opensFramesOfTheComputerAndRefusesRepeatsAndForgeries() {
        val o = Opener(key1)
        val f0 = "0000000000000000d750f4b01a9ec2ef24ad3c4af996831c20f352cdfb".fromHex()
        assertEquals("hello", String(o.open(f0)!!))
        assertNull(o.open(f0)) // a repeat
        val bad = "0000000000000001c0f8fba5e61c010074caebc5c8a058b83b4d1d3899c6324be1".fromHex().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertNull(o.open(bad))
        assertNull(Opener(ByteArray(32) { 2 }).open(f0))
    }

    @Test fun derivesTheSameKeysAndDigits() {
        val k = RemoteCrypto.deriveKeys(ByteArray(32) { 7 }, ByteArray(32) { 9 })
        assertEquals("bc4468134399104227c684c245bcb396c051906dec5b32e172957b71a1881cf1", k.c2s.toHex())
        assertEquals("0bbde9152a4cea14bfee768abb92ad0306c3ca946fc31cbb30344482d95b0d27", k.s2c.toHex())
        assertEquals("380073", k.sas)
    }

    @Test fun hkdfMatchesRfc5869() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".fromHex()
        val info = "f0f1f2f3f4f5f6f7f8f9".fromHex()
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", RemoteCrypto.hkdf(ikm, salt, info, 42).toHex())
    }

    @Test fun signsAndVerifiesWithRawKeys() {
        val s = SoftSigner()
        assertEquals(65, s.publicKey.size)
        val sig = s.sign("data".toByteArray())
        assertTrue(RemoteCrypto.verify(s.publicKey, "data".toByteArray(), sig))
        assertTrue(!RemoteCrypto.verify(s.publicKey, "other".toByteArray(), sig))
        assertTrue(!RemoteCrypto.verify(SoftSigner().publicKey, "data".toByteArray(), sig))
    }

    @Test fun agreesOnASecretWithAnotherParty() {
        val a = RemoteCrypto.newEphemeral()
        val b = RemoteCrypto.newEphemeral()
        assertArrayEquals(a.agree(b.publicKey), b.agree(a.publicKey))
    }
}

class RelayCodecTest {
    @Test fun mqttPacketsMatchTheLibraryOfTheComputer() {
        assertEquals("101400044d5154540402001e0008646576782d616263", MqttConn.connect("devx-abc", 30).toHex())
        assertEquals("82130001000e646576782f76312f782f646e2f7900", MqttConn.subscribe(1, "devx/v1/x/dn/y").toHex())
        assertEquals("3011000c646576782f76312f782f7570616263", MqttConn.publish("devx/v1/x/up", "abc".toByteArray()).toHex())
    }

    @Test fun readsPacketsThatAreSplitOrGlued() {
        val one = MqttConn.publish("t", ByteArray(300) { 5 })
        assertNull(MqttConn.parse(one.copyOfRange(0, 10)))
        val p = MqttConn.parse(one + byteArrayOf(0xC0.toByte(), 0))!!
        assertEquals(0x30, p.first)
        assertEquals(one.size, p.third)
    }

    @Test fun cutsAndJoinsBigMessagesInAnyOrder() {
        val c = Chunker(ByteArray(8) { it.toByte() })
        val data = ByteArray(50_000) { (it * 7).toByte() }
        val parts = c.split(data)
        assertTrue(parts.size > 3)
        var whole: ByteArray? = null
        for (p in parts.reversed()) whole = c.join(p) ?: whole
        assertArrayEquals(data, whole)
        assertNull(c.join(byteArrayOf(9, 9, 9)))
    }

    @Test fun readsThePairingCode() {
        val pub = ByteArray(65) { if (it == 0) 4 else 1 }
        val json = JSONObject().put("v", 1).put("hp", Base64.getUrlEncoder().withoutPadding().encodeToString(pub)).put("t", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 3 }))
            .put("n", "PC").put("a", org.json.JSONArray(listOf("192.168.1.5:47821"))).put("r", org.json.JSONArray(listOf(0, 1)))
        val code = "devx1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        val p = Pairing.parse(code)!!
        assertEquals("PC", p.name)
        assertEquals(listOf("192.168.1.5:47821"), p.addresses)
        assertEquals(listOf(0, 1), p.brokers)
        assertEquals(24, p.hostId.length)
        assertNull(Pairing.parse("devx1:garbage"))
        assertNull(Pairing.parse("https://example.com"))
    }
}

/**
 * Talks to a real host: `devx remote start` with DEVX_E2E_* set (see the script in the notes). Skipped when
 * the variables are missing, so the normal test run does not need a computer.
 */
class RemoteHostE2ETest {
    @Test fun pairsAndChatsWithTheRealHost() = runBlocking {
        val uri = System.getenv("DEVX_E2E_URI")
        assumeTrue(uri != null)
        val pairing = Pairing.parse(uri!!)!!
        val signer = SoftSigner()
        val did = RemoteCrypto.randomBytes(16).toHex()
        var sas = ""
        val ch = raceConnect(pairing.hostPub, signer, did, pairing.addresses.map { it.replace(Regex("^[^:]+"), "127.0.0.1") }, emptyList(), PairRequest(pairing.token, "JVM test"), { sas = it })
        assertTrue(ch.ready.optBoolean("paired"))
        assertEquals(sas, ch.sas)
        println("E2E paired, sas=$sas, host=" + ch.ready.optJSONObject("host"))
        ch.close()
        // back in without a token
        val again = raceConnect(pairing.hostPub, signer, did, pairing.addresses.map { it.replace(Regex("^[^:]+"), "127.0.0.1") }, emptyList())
        assertTrue(!again.ready.optBoolean("paired"))
        assertNotNull(again.ready.optJSONObject("host"))
        again.close()
    }
}

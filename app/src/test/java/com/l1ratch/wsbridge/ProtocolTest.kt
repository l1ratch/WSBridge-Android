package com.l1ratch.wsbridge

import com.l1ratch.wsbridge.tunnel.AESCTR
import com.l1ratch.wsbridge.tunnel.InitParser
import com.l1ratch.wsbridge.tunnel.MsgSplitter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/// Валидация протокольного ядра: то, что на iOS проверялось живым Telegram.
class ProtocolTest {

    /// Алгоритм отправителя MTProto obfuscated init (Telegram desktop):
    /// 1. случайные 64 байта, proto_tag в [56:60], dc_idx в [60:64]
    /// 2. key = P[8:40], iv = P[40:56]; C = AES-CTR(key, iv)(P)
    /// 3. в пакет КЛАДУТСЯ key/iv обратно в [8:56] (позиции шифруются как есть)
    private fun buildInit(dcIdx: Short, protoTag: Int, seed: Long = 42): ByteArray {
        val rnd = Random(seed)
        val p = ByteArray(64).also { rnd.nextBytes(it) }
        p[0] = 0x73 // первый байт не должен быть 0xef/0xdd/0xee/0x00
        val tag = protoTag.toLong() and 0xFFFFFFFFL
        p[56] = (tag and 0xFF).toByte()
        p[57] = ((tag shr 8) and 0xFF).toByte()
        p[58] = ((tag shr 16) and 0xFF).toByte()
        p[59] = ((tag shr 24) and 0xFF).toByte()
        p[60] = (dcIdx.toInt() and 0xFF).toByte()
        p[61] = ((dcIdx.toInt() shr 8) and 0xFF).toByte()
        p[62] = 0; p[63] = 0

        val key = p.copyOfRange(8, 40)
        val iv = p.copyOfRange(40, 56)
        val c = AESCTR(key, iv).update(p)
        key.copyInto(c, 8)
        iv.copyInto(c, 40)
        return c
    }

    @Test
    fun `init round-trip - production DC, padded intermediate`() {
        val init = buildInit(dcIdx = 2, protoTag = 0xDDDDDDDD.toInt())
        val parsed = InitParser.parse(init)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.dcId)
        assertEquals(false, parsed.isMedia)
        assertEquals(false, parsed.isTestDC)
        assertEquals(0xDDDDDDDDL, parsed.protoTag)
    }

    @Test
    fun `init round-trip - media DC negative idx`() {
        val init = buildInit(dcIdx = -4, protoTag = 0xEEEEEEEE.toInt(), seed = 7)
        val parsed = InitParser.parse(init)!!
        assertEquals(4, parsed.dcId)
        assertTrue(parsed.isMedia)
    }

    @Test
    fun `init round-trip - test DC`() {
        val init = buildInit(dcIdx = 10002, protoTag = 0xEFEFEFEF.toInt(), seed = 9)
        val parsed = InitParser.parse(init)!!
        assertEquals(10002, parsed.dcId)
        assertTrue(parsed.isTestDC)
    }

    @Test
    fun `init rejects garbage`() {
        assertNull(InitParser.parse(ByteArray(64) { 0x55 }))
        assertNull(InitParser.parse(ByteArray(63)))
    }

    /// Сплиттер: шифрпоток intermediate-пакетов режется ровно по границам.
    @Test
    fun `splitter frames intermediate packets`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        val protoTag = 0xEEEEEEEE

        // plain-пакеты: [len32][payload]
        val payloads = listOf(
            ByteArray(16) { 1 }, ByteArray(300) { 2 }, ByteArray(4) { 3 },
        )
        val plain = java.io.ByteArrayOutputStream()
        for (p in payloads) {
            plain.write(p.size.toLE())
            plain.write(p)
        }
        val plainBytes = plain.toByteArray()

        // Шифруем ПОСЛЕ init (сплиттер делает fastForward(64) сам):
        // на проводе — init(64) + cipher(plainBytes).
        val sender = AESCTR(key, iv)
        sender.fastForward(64)
        val cipherBytes = sender.update(plainBytes)

        val splitter = MsgSplitter(key, iv, protoTag.toLong() and 0xFFFFFFFFL)

        // Кормим разными кусками (TCP-поток не выровнен по пакетам)
        val parts = splitter.split(cipherBytes.copyOfRange(0, 10)) +
            splitter.split(cipherBytes.copyOfRange(10, 64)) +
            splitter.split(cipherBytes.copyOfRange(64, cipherBytes.size))

        assertEquals(3, parts.size)
        // Части должны в сумме дать весь шифртекст, по границам пакетов: 4+16, 4+300, 4+4
        assertEquals(20, parts[0].size)
        assertEquals(304, parts[1].size)
        assertEquals(8, parts[2].size)
        val joined = parts.fold(ByteArray(0)) { a, b -> a + b }
        assertArrayEquals(cipherBytes, joined)
    }

    /// Abridged: 1-байтовая длина (×4) и 0x7F-эскапация на 3 байта.
    @Test
    fun `splitter frames abridged packets`() {
        val key = ByteArray(32) { (it + 9).toByte() }
        val iv = ByteArray(16) { (it + 5).toByte() }

        val small = ByteArray(40) { 7 }           // len byte = 10 (40/4)
        val big = ByteArray(4 * 0x100) { 8 }      // 0x7F + 3-byte len = 0x100
        val plain = java.io.ByteArrayOutputStream()
        plain.write(small.size / 4)
        plain.write(small)
        plain.write(0x7F)
        plain.write((big.size / 4) and 0xFF)
        plain.write(((big.size / 4) shr 8) and 0xFF)
        plain.write(((big.size / 4) shr 16) and 0xFF)
        plain.write(big)

        val sender = AESCTR(key, iv)
        sender.fastForward(64)
        val cipherBytes = sender.update(plain.toByteArray())

        val splitter = MsgSplitter(key, iv, 0xEFEFEFEFL)
        val parts = splitter.split(cipherBytes)
        assertEquals(2, parts.size)
        assertEquals(1 + 40, parts[0].size)
        assertEquals(4 + 1024, parts[1].size)
    }

    /// Частичный пакет не отдаётся, пока не докачан (иначе WS-фрейм порвёт MTProto).
    @Test
    fun `splitter holds partial packet`() {
        val key = ByteArray(32) { 1 }
        val iv = ByteArray(16) { 2 }
        val payload = ByteArray(100) { 5 }
        val plain = payload.size.toLE() + payload

        val sender = AESCTR(key, iv)
        sender.fastForward(64)
        val cipher = sender.update(plain)

        val splitter = MsgSplitter(key, iv, 0xEEEEEEEE)
        assertEquals(0, splitter.split(cipher.copyOfRange(0, 50)).size)
        val rest = splitter.split(cipher.copyOfRange(50, cipher.size))
        assertEquals(1, rest.size)
        assertArrayEquals(cipher, rest[0])
    }

    private fun Int.toLE(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte(),
    )
}

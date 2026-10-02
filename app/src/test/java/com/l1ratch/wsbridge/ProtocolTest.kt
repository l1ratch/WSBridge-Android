package com.l1ratch.wsbridge

import com.l1ratch.wsbridge.tunnel.AESCTR
import com.l1ratch.wsbridge.tunnel.InitParser
import com.l1ratch.wsbridge.tunnel.MsgSplitter
import com.l1ratch.wsbridge.tunnel.TelegramDCs
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
        val init = buildInit(dcIdx = 2, protoTag = 0xDDDDDDDDL.toInt())
        val parsed = InitParser.parse(init)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.dcId)
        assertEquals(false, parsed.isMedia)
        assertEquals(false, parsed.isTestDC)
        assertEquals(0xDDDDDDDDL, parsed.protoTag)
    }

    @Test
    fun `init round-trip - media DC negative idx`() {
        val init = buildInit(dcIdx = -4, protoTag = 0xEEEEEEEEL.toInt(), seed = 7)
        val parsed = InitParser.parse(init)!!
        assertEquals(4, parsed.dcId)
        assertTrue(parsed.isMedia)
    }

    @Test
    fun `init round-trip - test DC`() {
        val init = buildInit(dcIdx = 10002, protoTag = 0xEFEFEFEFL.toInt(), seed = 9)
        val parsed = InitParser.parse(init)!!
        assertEquals(10002, parsed.dcId)
        assertTrue(parsed.isTestDC)
    }

    @Test
    fun `init rejects garbage`() {
        assertNull(InitParser.parse(ByteArray(64) { 0x55 }))
        assertNull(InitParser.parse(ByteArray(63)))
    }

    /// Причина отказа должна отличать TLS-клиента (Telegram Web, браузер) от
    /// прочего мусора: иначе в журнале сотня одинаковых bad_init без причины.
    @Test
    fun `describe names the rejected transport`() {
        assertEquals("tls", InitParser.describe(byteArrayOf(0x16, 0x03, 0x01, 0x02)))
        assertEquals("tls", InitParser.describe(byteArrayOf(0x16, 0x03)))
        assertEquals("short", InitParser.describe(byteArrayOf(0x16)))
        assertEquals("raw:efef", InitParser.describe(byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())))
    }

    /// Сплиттер: шифрпоток intermediate-пакетов режется ровно по границам.
    @Test
    fun `splitter frames intermediate packets`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        val protoTag = 0xEEEEEEEEL

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

        val splitter = MsgSplitter(key, iv, protoTag)

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

        val splitter = MsgSplitter(key, iv, 0xEEEEEEEEL)
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

    // --- Определение DC ---
    // Telegram Android не пишет dc_idx в init при прямом соединении (только при
    // MTProxy с секретом): в логах тестера DC26369, DC307 — случайные байты.
    // Поэтому DC берётся из адреса, который набирал клиент.

    @Test
    fun `resolve takes DC from destination address`() {
        // 149.154.167.51 — DC2 из BuiltInDc официального клиента.
        val d = TelegramDCs.resolve("149.154.167.51", parsedDc = 26369, parsedMedia = true)
        assertEquals(2, d.dc)
        assertTrue(d.fromIp)
        // Гейтвей ждёт kws2, а не kws26369 — media-флаг из мусорного init не берём.
        assertEquals(false, d.isMedia)
    }

    @Test
    fun `resolve covers all five production DCs`() {
        val expected = mapOf(
            "149.154.175.50" to 1, "149.154.167.51" to 2, "149.154.175.100" to 3,
            "149.154.167.91" to 4, "149.154.171.5" to 5,
        )
        for ((ip, dc) in expected) {
            val d = TelegramDCs.resolve(ip, parsedDc = 0, parsedMedia = false)
            assertEquals("DC for $ip", dc, d.dc)
            assertTrue(d.fromIp)
        }
    }

    @Test
    fun `resolve marks test DCs`() {
        val d = TelegramDCs.resolve("149.154.167.40", parsedDc = 0, parsedMedia = false)
        assertEquals(2, d.dc)
        assertTrue("test IP must route to /apiws_test", d.isTest)
    }

    /// Адреса, на которые Telegram реально ходит по журналу тестера. Без записи
    /// в таблице они угадывались как DC2, и сессия DC4 попадала на kws2.
    @Test
    fun `resolve knows addresses seen in the field`() {
        assertEquals(4, TelegramDCs.resolve("149.154.165.136", 0, false).dc) // DC4
        assertEquals(1, TelegramDCs.resolve("149.154.175.58", 0, false).dc)  // DC1
        assertEquals(2, TelegramDCs.resolve("149.154.167.151", 0, false).dc) // DC2
        assertEquals(1, TelegramDCs.resolve("149.154.175.53", 0, false).dc)  // DC1
        assertEquals(4, TelegramDCs.resolve("149.154.167.91", 0, false).dc)  // DC4
    }

    /// DC203 (CDN) не имеет kws-хоста: kws203.web.telegram.org не существует.
    /// Апстрим приводит его к DC2 (tg-ws-proxy utils.ws_domains).
    @Test
    fun `normalize maps CDN dc to dc2`() {
        assertEquals(2, TelegramDCs.normalize(203))
        assertEquals(3, TelegramDCs.normalize(3))
        val cdn = TelegramDCs.resolve("91.105.192.100", 0, false)
        assertEquals(2, cdn.dc)
        assertEquals(false, cdn.isTest)
    }

    @Test
    fun `resolve falls back to sane parsed DC then default`() {
        // Неизвестный адрес + правдоподобный DC из init — берём init.
        val ok = TelegramDCs.resolve("10.0.0.1", parsedDc = 3, parsedMedia = false)
        assertEquals(3, ok.dc)
        assertEquals(false, ok.fromIp)

        // Неизвестный адрес + мусорный DC (как в логе тестера) — дефолт DC2,
        // а не kws26369.web.telegram.org с NXDOMAIN.
        val junk = TelegramDCs.resolve("10.0.0.1", parsedDc = 26369, parsedMedia = true)
        assertEquals(TelegramDCs.DEFAULT_DC, junk.dc)
        assertEquals(2, junk.dc)
    }

    /// rewriteDc правит dc_idx прямо в шифртексте: XOR с тем же байтом
    /// keystream. Ошибка здесь молча ломает шифрпоток MTProto, поэтому
    /// проверяем и сам факт подмены, и что key/iv остались нетронутыми.
    @Test
    fun `rewriteDc changes only dc bytes and keeps key iv`() {
        val init = buildInit(dcIdx = 26369.toShort(), protoTag = 0xDDDDDDDDL.toInt(), seed = 11)
        val parsed = InitParser.parse(init)!!
        assertEquals(26369, parsed.dcId)

        val fixed = InitParser.rewriteDc(init, parsed.key, parsed.iv, dc = 2)

        // Размер и всё, кроме [60:62], не изменились — включая key и iv.
        assertEquals(init.size, fixed.size)
        assertArrayEquals(init.copyOfRange(8, 56), fixed.copyOfRange(8, 56))
        for (i in init.indices) {
            if (i == 60 || i == 61) continue
            assertEquals("byte $i must not change", init[i], fixed[i])
        }
        assertEquals(2, InitParser.parse(fixed)!!.dcId)

        // Исходный массив не мутирован — сплиттер строится из того же initBuffer.
        assertEquals(26369, InitParser.parse(init)!!.dcId)
    }

    @Test
    fun `rewriteDc round-trips every DC and prototag`() {
        val tags = listOf(0xEFEFEFEFL.toInt(), 0xEEEEEEEEL.toInt(), 0xDDDDDDDDL.toInt())
        for (tag in tags) {
            for (dc in 1..5) {
                val init = buildInit(dcIdx = 7.toShort(), protoTag = tag, seed = dc.toLong())
                val parsed = InitParser.parse(init)!!
                val fixed = InitParser.rewriteDc(init, parsed.key, parsed.iv, dc)
                val back = InitParser.parse(fixed)!!
                assertEquals("dc=$dc tag=$tag", dc, back.dcId)
                assertEquals("protoTag must survive", parsed.protoTag, back.protoTag)
                assertEquals(false, back.isMedia)
            }
        }
    }
}

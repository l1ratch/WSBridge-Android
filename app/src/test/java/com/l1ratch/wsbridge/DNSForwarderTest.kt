package com.l1ratch.wsbridge

import com.l1ratch.wsbridge.tunnel.DNSForwarder
import org.junit.Assert.assertEquals
import org.junit.Test

/// DNS-ответ уходит в туннель как собранный с нуля IP/UDP пакет. Ошибка в любом
/// поле заголовка не даёт исключения — ядро просто отбрасывает пакет, и в журнале
/// не появляется ничего. Поэтому каждое поле проверяется явно.
class DNSForwarderTest {

    private val ihl = 20

    /// UDP:53 запрос клиента 10.0.0.2:34567 -> 8.8.8.8:53 с корректной
    /// контрольной суммой IP (как его приносит TUN).
    private fun request(): ByteArray {
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01) // id=0x1234, qdcount=1
        val udpLen = 8 + dns.size
        val p = ByteArray(ihl + udpLen)

        p[0] = 0x45
        p[2] = (p.size shr 8).toByte(); p[3] = (p.size and 0xFF).toByte()
        p[8] = 64
        p[9] = 17 // UDP
        p[12] = 10; p[13] = 0; p[14] = 0; p[15] = 2   // src 10.0.0.2
        p[16] = 8; p[17] = 8; p[18] = 8; p[19] = 8     // dst 8.8.8.8
        p[20] = (34567 shr 8).toByte(); p[21] = (34567 and 0xFF).toByte()
        p[22] = 0; p[23] = 53
        p[24] = (udpLen shr 8).toByte(); p[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(dns, 0, p, ihl + 8, dns.size)

        p[10] = 0; p[11] = 0
        val csum = DNSForwarder.checksum(p, 0, ihl)
        p[10] = (csum shr 8).toByte(); p[11] = (csum and 0xFF).toByte()
        return p
    }

    private fun wrap(response: ByteArray): ByteArray {
        val fwd = DNSForwarder(listOf("8.8.8.8")) { }
        return fwd.wrapResponse(response, request(), ihl)
    }

    private fun be16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    @Test
    fun `response swaps addresses and ports`() {
        val body = ByteArray(16) { 0x42 }
        val out = wrap(body)

        assertEquals(ihl + 8 + body.size, out.size)
        // src = адрес DNS-сервера, dst = клиент (симметрично исходному запросу).
        assertEquals(8, out[12].toInt()); assertEquals(8, out[13].toInt())
        assertEquals(8, out[14].toInt()); assertEquals(8, out[15].toInt())
        assertEquals(10, out[16].toInt()); assertEquals(0, out[17].toInt())
        assertEquals(0, out[18].toInt()); assertEquals(2, out[19].toInt())
        // Ответ идёт с 53 на клиентский порт.
        assertEquals(53, be16(out, 20))
        assertEquals(34567, be16(out, 22))
        // Полезная нагрузка DNS неповреждённа.
        assertArrayEqualsBytes(body, out.copyOfRange(28, out.size))
    }

    @Test
    fun `ip header length and checksum are valid`() {
        val out = wrap(ByteArray(16) { 0x7F })
        assertEquals(0x45, out[0].toInt() and 0xFF)   // IPv4, IHL=5
        assertEquals(17, out[9].toInt())             // протокол UDP
        assertEquals(64, out[8].toInt())             // TTL
        assertEquals(out.size, be16(out, 2))         // total length
        assertEquals(8 + 16, be16(out, 24))          // UDP length
        // Свойство IP-заголовка: пересчёт контрольной суммы по пакету как есть
        // даёт ноль. (Обнулять поле checksum нужно только при ВЫЧИСЛЕНИИ суммы —
        // проверка идёт по собранному пакету, иначе всегда возвращается сама сумма.)
        assertEquals(0, DNSForwarder.checksum(out, 0, ihl))
    }

    @Test
    fun `udp checksum is zero which ipv4 permits`() {
        val out = wrap(ByteArray(8) { 1 })
        assertEquals(0, be16(out, 26))
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("byte $i", expected[i], actual[i])
    }
}
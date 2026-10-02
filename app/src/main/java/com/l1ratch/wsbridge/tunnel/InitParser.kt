package com.l1ratch.wsbridge.tunnel

/// Парсинг 64-байтного obfuscated init пакета MTProto. Порт InitParser.swift.
/// Ключи: key = init[8:40], iv = init[40:56] (standard obfuscation, без secret).
/// После расшифровки: proto_tag at [56:60], dc_idx at [60:62].
object InitParser {
    const val handshakeLen = 64
    private const val skipLen = 8
    private const val prekeyLen = 32
    private const val ivLen = 16
    private const val protoTagPos = 56
    private const val dcIdxPos = 60

    data class ParsedInit(
        val dcId: Int,
        val isMedia: Boolean,
        val isTestDC: Boolean,
        val protoTag: Long,
        val key: ByteArray,   // init[8:40] — для MsgSplitter
        val iv: ByteArray,    // init[40:56] — для MsgSplitter
    )

    /// Парсит init пакет. Возвращает null если это не валидный MTProto init.
    fun parse(data: ByteArray): ParsedInit? {
        if (data.size < handshakeLen) return null

        val key = data.copyOfRange(skipLen, skipLen + prekeyLen)
        val iv = data.copyOfRange(skipLen + prekeyLen, skipLen + prekeyLen + ivLen)

        val dec = AESCTR(key, iv).update(data.copyOfRange(0, handshakeLen))

        val protoTag = (dec[protoTagPos].toLong() and 0xFF) or
            ((dec[protoTagPos + 1].toLong() and 0xFF) shl 8) or
            ((dec[protoTagPos + 2].toLong() and 0xFF) shl 16) or
            ((dec[protoTagPos + 3].toLong() and 0xFF) shl 24)

        val abridged = 0xEFEFEFEFL
        val intermediate = 0xEEEEEEEEL
        val paddedIntermediate = 0xDDDDDDDDL
        if (protoTag != abridged && protoTag != intermediate && protoTag != paddedIntermediate) {
            return null
        }

        // dc_idx at offset 60 (2 bytes, signed little-endian)
        val dcIdxRaw = ((dec[dcIdxPos].toInt() and 0xFF) or ((dec[dcIdxPos + 1].toInt() and 0xFF) shl 8)).toShort()
        val dcId = kotlin.math.abs(dcIdxRaw.toInt())
        val isMedia = dcIdxRaw < 0
        val isTestDC = dcId >= 10000

        return ParsedInit(dcId, isMedia, isTestDC, protoTag, key, iv)
    }

    /// Что реально пришло вместо MTProto init. TLS-клиент (0x16 0x03 в начале) —
    /// это WebSocket-транспорт Telegram Web: сплайсинг MTProto его не понимает,
    /// и без этой метки в журнале остаётся только голый bad_init.
    fun describe(head: ByteArray): String {
        if (head.size < 2) return "short"
        val a = head[0].toInt() and 0xFF
        val b = head[1].toInt() and 0xFF
        return if (a == 0x16 && b == 0x03) "tls" else String.format("raw:%02x%02x", a, b)
    }

    /// Переписывает dc_idx в init, не ломая шифр потока.
    /// Хвост [56:64) зашифрован CTR-потоком с позиции 56, поэтому правка —
    /// это XOR с тем же байтом keystream: enc = plain ^ ks, значит
    /// new_enc = new_plain ^ ks = new_plain ^ plain ^ enc.
    /// Ключ и IV лежат в [8:56) и не трогаются — шифры клиента не страдают.
    /// Нужно потому, что Telegram Android оставляет в [60:62) случайные байты
    /// (dc_idx пишется только при MTProxy с секретом), а гейтвей ждёт
    /// согласованный с hostname дата-центр.
    fun rewriteDc(init: ByteArray, key: ByteArray, iv: ByteArray, dc: Int): ByteArray {
        val out = init.copyOf()
        val dec = AESCTR(key, iv).update(init.copyOfRange(0, handshakeLen))
        val value = dc.toShort().toInt()
        for (i in 0 until 2) {
            val plain = dec[dcIdxPos + i].toInt() and 0xFF
            val enc = out[dcIdxPos + i].toInt() and 0xFF
            val next = (value shr (8 * i)) and 0xFF
            out[dcIdxPos + i] = (next xor plain xor enc).toByte()
        }
        return out
    }
}

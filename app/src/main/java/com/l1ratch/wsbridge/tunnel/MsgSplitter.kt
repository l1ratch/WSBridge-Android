package com.l1ratch.wsbridge.tunnel

import java.io.ByteArrayOutputStream

/// Разбивает TCP-поток на отдельные MTProto-пакеты для WS-фреймов.
/// Порт MsgSplitter.swift: ключи из init клиента (без secret), fast-forward
/// на 64 байта (init уже отправлен как первый фрейм).
/// ponytail: ByteArrayOutputStream + copyOfRange на каждый chunk — O(остатка)
/// на вызов, но остаток почти всегда мал (пакет выгребается целиком).
/// Если на больших catch-up батчах появится деградация — заменить на
/// кольцевой буфер с курсором.
class MsgSplitter(key: ByteArray, iv: ByteArray, private val protoTag: Long) {
    private val cipher = AESCTR(key, iv).apply { fastForward(64) } // skip init
    private val cipherBuf = ByteArrayOutputStream()
    private val plainBuf = ByteArrayOutputStream()
    private var disabled = false

    private val abridged = 0xEFEFEFEFL
    private val intermediate = 0xEEEEEEEEL
    private val paddedIntermediate = 0xDDDDDDDDL

    /// Принимает шифртекст, возвращает массив шифртекст-пакетов для WS-фреймов.
    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf.write(chunk)
        plainBuf.write(cipher.update(chunk))

        val cbuf = cipherBuf.toByteArray()
        val pbuf = plainBuf.toByteArray()
        val parts = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < cbuf.size) {
            val packetLen = nextPacketLen(pbuf, offset, cbuf.size - offset) ?: break
            if (packetLen <= 0) {
                parts.add(cbuf.copyOfRange(offset, cbuf.size))
                offset = cbuf.size
                disabled = true
                break
            }
            parts.add(cbuf.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        cipherBuf.reset(); plainBuf.reset()
        if (offset < cbuf.size) {
            cipherBuf.write(cbuf, offset, cbuf.size - offset)
            plainBuf.write(pbuf, offset, pbuf.size - offset)
        }
        return parts
    }

    /// Возвращает остаток буфера при закрытии соединения.
    fun flush(): List<ByteArray> {
        val tail = cipherBuf.toByteArray()
        cipherBuf.reset(); plainBuf.reset()
        return if (tail.isEmpty()) emptyList() else listOf(tail)
    }

    /// null = нужно больше данных; 0 = поток рассинхронизирован; иначе длина пакета.
    private fun nextPacketLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (protoTag) {
            abridged -> nextAbridgedLen(plain, offset, avail)
            intermediate, paddedIntermediate -> nextIntermediateLen(plain, offset, avail)
            else -> 0
        }
    }

    private fun nextAbridgedLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        val first = plain[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            payloadLen = ((plain[offset + 1].toInt() and 0xFF) or
                ((plain[offset + 2].toInt() and 0xFF) shl 8) or
                ((plain[offset + 3].toInt() and 0xFF) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val payloadLen = ((plain[offset].toInt() and 0xFF) or
            ((plain[offset + 1].toInt() and 0xFF) shl 8) or
            ((plain[offset + 2].toInt() and 0xFF) shl 16) or
            ((plain[offset + 3].toInt() and 0xFF) shl 24)) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}

package com.l1ratch.wsbridge.tunnel

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/// AES-256-CTR. CTR симметричен: encrypt == decrypt.
/// javax.crypto "AES/CTR/NoPadding" инкрементирует весь 128-битный блок
/// big-endian — ровно то, что делал CommonCrypto kCCModeOptionCTR_BE на iOS
/// (стандарт MTProto obfuscation).
class AESCTR(key: ByteArray, iv: ByteArray) {
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun update(data: ByteArray): ByteArray = cipher.update(data) ?: ByteArray(0)

    /// Прогоняет N нулевых байт через шифр (fast-forward keystream).
    fun fastForward(count: Int) {
        update(ByteArray(count))
    }
}

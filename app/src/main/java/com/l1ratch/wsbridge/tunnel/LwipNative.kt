package com.l1ratch.wsbridge.tunnel

/// JNI-обвязка над lwip_bridge.c (чистый C, без изменений из iOS).
/// Все native-вызовы и все колбэки C происходят на ОДНОМ потоке (single-thread
/// executor TunnelService) — lwIP NO_SYS=1 однопоточный, как и на iOS.
object LwipNative {
    interface Handler {
        fun onOutput(packet: ByteArray)          // lwIP → клиент (в TUN fd)
        fun onAccept(connId: Long, dcIp: Long)
        fun onRecv(connId: Long, data: ByteArray)
        fun onClose(connId: Long, reason: Int)
        fun onSent(connId: Long)
    }

    @Volatile
    var handler: Handler? = null

    init {
        System.loadLibrary("wsbridge")
    }

    // @JvmStatic: без него методы object'а — instance-методы, и JNI-символ
    // получит jobject this вместо jclass (C-сторона объявлена под static).
    @JvmStatic external fun nativeInit()
    @JvmStatic external fun nativeInput(data: ByteArray, len: Int)
    @JvmStatic external fun nativePoll()
    @JvmStatic external fun nativeWrite(connId: Long, data: ByteArray, len: Int): Int
    @JvmStatic external fun nativeClose(connId: Long)
    @JvmStatic external fun nativeGetDstIp(connId: Long): Long
    @JvmStatic external fun nativeInmemDrops(): Long

    // Вызываются из C (wsbridge_jni.c)
    @JvmStatic fun onOutput(data: ByteArray) { handler?.onOutput(data) }
    @JvmStatic fun onAccept(connId: Long, dcIp: Long) { handler?.onAccept(connId, dcIp) }
    @JvmStatic fun onRecv(connId: Long, data: ByteArray) { handler?.onRecv(connId, data) }
    @JvmStatic fun onClose(connId: Long, reason: Int) { handler?.onClose(connId, reason) }
    @JvmStatic fun onSent(connId: Long) { handler?.onSent(connId) }
}

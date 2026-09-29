package com.l1ratch.wsbridge.tunnel

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// Журнал событий туннеля. На Android сервис и UI живут в ОДНОМ процессе,
/// поэтому весь IPC-слой iOS (Darwin notifications, JournalServer на
/// loopback TCP, JournalReader, персист в файл контейнера) не нужен:
/// приложение читает журнал напрямую.
///
/// Каждое событие зеркалится в logcat (tag WSBridge) — удалённый тестер
/// снимает всю диагностику через `adb logcat -s WSBridge` или bugreport,
/// даже если UI недоступен (краш, процесс убит).
/// ponytail: только память. Персист предыдущего прогона (iOS loadPrevious)
/// добавим, если понадобится post-mortem после убийства процесса системой.
object EventLog {
    private const val TAG = "WSBridge"
    private val entries = ArrayDeque<Pair<String, Long>>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Счётчики io-строки журнала (пишутся в основном с lwip-потока)
    @Volatile var outPkts = 0L
    @Volatile var wsDown = 0L
    @Volatile var writeFails = 0L
    @Volatile var upBytes = 0L
    @Volatile var rxBytes = 0L
    @Volatile var pendCur = 0L
    @Volatile var sentCb = 0L
    @Volatile var inV6 = 0L
    @Volatile var inOther = 0L

    @Synchronized
    fun append(name: String) {
        entries.addLast(name to System.currentTimeMillis())
        while (entries.size > 200) entries.removeFirst()
        Log.i(TAG, name)
    }

    @Synchronized
    fun reset() {
        entries.clear()
        outPkts = 0; wsDown = 0; writeFails = 0; upBytes = 0
        rxBytes = 0; pendCur = 0; sentCb = 0; inV6 = 0; inOther = 0
    }

    @Synchronized
    fun journal(): String =
        entries.joinToString("\n") { "${fmt.format(Date(it.second))} ${it.first}" }
}

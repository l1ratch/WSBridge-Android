package com.l1ratch.wsbridge

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.l1ratch.wsbridge.tunnel.EventLog
import org.json.JSONArray
import org.json.JSONObject

/// Хранит конфиг (worker, DNS) и статус туннеля. Порт TunnelManager.swift.
///
/// ponytail: на Android сервис и UI в одном процессе — никакого IPC:
/// статус берётся из флага, который ставит TunnelService, журнал —
/// напрямую из EventLog. SharedPreferences вместо UserDefaults, JSON
/// для своих DNS-конфигов (stdlib org.json, без kotlinx-serialization).
object TunnelManager {

    data class DNSConfig(
        val id: String,
        val name: String,
        val description: String,
        val servers: List<String>,   // IP-адреса
        val isPreset: Boolean,
    )

    /// Пресеты — не удаляются, не редактируются.
    /// DoH/DoT iOS-пресетов на Android не нужны: запросы идут из туннеля
    /// plain UDP:53 на server IP, шифрование DNS — задача отдельного VPN-режима.
    val dnsPresets = listOf(
        DNSConfig("system", "Системный", "DNS устройства (туннель не перехватывает DNS)", emptyList(), true),
        DNSConfig("google", "Google", "Быстрый, надёжный, без фильтрации", listOf("8.8.8.8", "8.8.4.4"), true),
        DNSConfig("cloudflare", "Cloudflare", "Быстрый, приватный, без фильтрации", listOf("1.1.1.1", "1.0.0.1"), true),
        DNSConfig("comss", "Comss.one", "Доступ к ИИ, блокировка рекламы, счетчиков, вредоносных сайтов и фишинга", listOf("83.220.169.155", "212.109.195.93"), true),
        DNSConfig("malw", "Malw.link", "Разблокирует недоступные сайты, блокирует мусор.", listOf("95.216.204.218", "80.253.249.40"), true),
    )

    private lateinit var prefs: SharedPreferences

    /// Статус туннеля: mutableStateOf — Compose перечитывает сам, без IPC и слушателей.
    var running by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("wsbridge", Context.MODE_PRIVATE)
    }

    fun updateRunning(value: Boolean) {
        running = value
    }

    /// Домен собственного CF worker'а пользователя (pipe-режим). пустой = kws-каскад.
    /// «host:port» = прямое реле на VPS.
    var workerDomain: String
        get() = prefs.getString("workerDomain", "") ?: ""
        set(v) = prefs.edit().putString("workerDomain", v).apply()

    var customDNS: List<DNSConfig>
        get() {
            val json = prefs.getString("customDNS", null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DNSConfig(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        description = o.optString("description"),
                        servers = o.getJSONArray("servers").let { s -> (0 until s.length()).map { s.getString(it) } },
                        isPreset = false,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(configs) {
            val arr = JSONArray()
            for (c in configs) {
                arr.put(JSONObject().apply {
                    put("id", c.id); put("name", c.name); put("description", c.description)
                    put("servers", JSONArray(c.servers))
                })
            }
            prefs.edit().putString("customDNS", arr.toString()).apply()
        }

    var selectedDNSId: String
        get() = prefs.getString("selectedDNSId", "comss") ?: "comss"
        set(v) = prefs.edit().putString("selectedDNSId", v).apply()

    val allDNS: List<DNSConfig> get() = dnsPresets + customDNS

    val selectedDNS: DNSConfig get() = allDNS.firstOrNull { it.id == selectedDNSId } ?: dnsPresets[0]

    /// Журнал туннеля — напрямую из EventLog (один процесс).
    fun journal(): String = EventLog.journal().ifEmpty { "журнал пуст — туннель ещё не запускался" }
}

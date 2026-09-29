package com.l1ratch.wsbridge.tunnel

object TelegramDCs {
    // ponytail: полные диапазоны Telegram вместо /32 — Telegram может ходить
    // на любой IP из этих подсетей. IPv6 не маршрутизируем: lwIP у нас v4-only
    // (как и на iOS — анонсированный v6-маршрут был бы чёрной дырой).
    // Пары (адрес, префикс) для VpnService.Builder.addRoute.
    val routes = listOf(
        "149.154.0.0" to 16,
        "91.108.0.0" to 16,
        "91.105.192.0" to 24,
    )
}

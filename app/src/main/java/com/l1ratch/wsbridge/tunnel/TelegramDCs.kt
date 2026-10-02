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

    /// Официальные IPv4 дата-центров Telegram — единственный надёжный источник
    /// номера DC на Android.
    ///
    /// Почему нельзя читать dc_idx из init: официальный клиент Android пишет
    /// байты [60:62] только при подключении через MTProxy с секретом
    /// (DrKLO/Telegram, Connection.cpp: `if (useSecret != 0)`). При прямом
    /// соединении там остаются случайные байты — в журнале это DC26369, DC307
    /// и т.п., откуда получались несуществующие kws26369.web.telegram.org
    /// (NXDOMAIN) и провал проверки сертификата. iOS (MtProtoKit) пишет
    /// dc_idx всегда, поэтому iOS-порт работал без этой таблицы.
    ///
    /// Источники: BuiltInDc в DrKLO/Telegram (ConnectionsManager.cpp),
    /// core.telegram.org и dc_probe.py из iOS-репы (.41 — «мастер телефона»).
    private val dcByIp = mapOf(
        "149.154.175.50" to 1,
        "149.154.175.53" to 1,
        "149.154.167.51" to 2,
        "149.154.167.41" to 2,
        "149.154.167.151" to 2,
        "95.161.76.100" to 2,
        "149.154.175.100" to 3,
        "149.154.167.91" to 4,
        "149.154.171.5" to 5,
        "91.108.56.130" to 5,
    )

    /// Тестовый контур: другой путь (/apiws_test) и другие DC.
    private val testDcByIp = mapOf(
        "149.154.175.40" to 1,
        "149.154.167.40" to 2,
        "149.154.175.117" to 3,
    )

    /// DC по адресу назначения или null, если IP неизвестен (тогда в журнале
    /// появится dc_unknown:IP — таблицу нужно дополнить этим адресом).
    fun dcForIp(ip: String): Int? = dcByIp[ip] ?: testDcByIp[ip]

    fun isTestIp(ip: String): Boolean = testDcByIp.containsKey(ip)

    /// DC по умолчанию, когда адрес не опознан. 2 — не догадка наугад: в
    /// официальном конфиге Telegram (`core.telegram.org/getProxyConfig`) первой
    /// строкой идёт `default 2;`, и на DC2 приходится бутстрап большинства
    /// аккаунтов. Неверный DC не рвёт соединение (resPQ отвечает любой DC) —
    /// ломается только привязка сессии, поэтому опознанный IP всегда важнее.
    const val DEFAULT_DC = 2

    /// Итог определения DC для одного соединения.
    data class DcChoice(
        val dc: Int,
        val isTest: Boolean,
        val isMedia: Boolean,
        val fromIp: Boolean,
    )

    /// Определяет DC. Приоритет — адрес назначения (`dstIp`), потому что init
    /// на Android его не несёт; `parsedDc` из init используется только как
    /// резерв, когда IP не опознан, и только если он правдоподобен (1..5).
    ///
    /// ponytail: media-флаг по IP не выводится — media-DC живут на тех же
    /// адресах, а знак dc_idx в init случайный. Media-сессия уйдёт обычным
    /// путём (kws{dc}, не kws{dc}-1): загрузки медленнее, но не ломаются.
    /// Разделять media стоит только если упрёмся в скорость загрузок.
    fun resolve(dstIp: String, parsedDc: Int, parsedMedia: Boolean): DcChoice {
        val ipDc = dcForIp(dstIp)
        if (ipDc != null) {
            return DcChoice(ipDc, isTestIp(dstIp), isMedia = false, fromIp = true)
        }
        val saneParsed = parsedDc.takeIf { it in 1..5 } ?: DEFAULT_DC
        return DcChoice(saneParsed, isTest = false, isMedia = parsedMedia, fromIp = false)
    }
}

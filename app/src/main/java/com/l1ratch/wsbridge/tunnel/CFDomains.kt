package com.l1ratch.wsbridge.tunnel

/// Ротационные CF-домены из апстрима tg-ws-proxy (.github/cfproxy-domains.txt).
/// kws{dc}.{base_domain} фронтирует WS-гейтвей Telegram через Cloudflare.
object CFDomains {
    private val bases = listOf(
        "pclead.co.uk",
        "offshor.co.uk",
        "cakeisalie.co.uk",
        "noskomnadzor.co.uk",
        "lovetrue.co.uk",
        "sorokdva.co.uk",
        "pyatdesyatdva.co.uk",
        "kartoshka.co.uk",
        "sorokodin.co.uk",
        "pyatdesyatodin.co.uk",
        "notelega.co.uk",
        "ebally.co.uk",
        "nebally.co.uk",
        "havegreatday.co.uk",
        "pomogite.co.uk",
        "fixtelega.co.uk",
        "sadnews.co.uk",
        "onedaychamp.co.uk",
        "stopblocking.co.uk",
        "nothingthere.co.uk",
    )

    /// Возвращает список доменов для WS-подключения: kws{dc}.{base}
    fun domains(dc: Int): List<String> = bases.map { "kws$dc.$it" }
}

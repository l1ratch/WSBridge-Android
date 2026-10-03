package com.l1ratch.wsbridge.tunnel

/// Ротационные CF-домены из апстрима tg-ws-proxy (.github/cfproxy-domains.txt).
/// kws{dc}.{base_domain} фронтирует WS-гейтвей Telegram через Cloudflare.
///
/// Hot-обновление: список может быть дополнен скачанным (FrontsUpdater),
/// НЕ заменён. Новые домены апстрима часто не резолвятся сразу после
/// публикации — полная замена выкинула бы живые встроенные фронты и
/// оставила каскад с пустышками. Мердж, встроенные — в начале (каскад
/// берёт prefix).
object CFDomains {
    /// Встроенный fallback — те же 20 баз десктопа.
    val builtinBases = listOf(
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

    @Volatile
    var bases: List<String> = builtinBases
        private set

    /// Мердж скачанного списка: встроенные первыми, новые дописываются
    /// в конец. Меньше 3 валидных доменов — игнор (битый файл).
    fun update(new: List<String>) {
        if (new.size < 3) return
        val merged = builtinBases.toMutableList()
        for (d in new) if (d !in merged) merged += d
        bases = merged
    }

    /// Текущее число доменов (для статуса в UI).
    val size: Int get() = bases.size

    /// Возвращает список доменов для WS-подключения: kws{dc}.{base}
    fun domains(dc: Int): List<String> = bases.map { "kws$dc.$it" }
}

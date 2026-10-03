# WSBridge-Android

Telegram на Android без ручной настройки прокси.

WSBridge — это VPN-туннель, который перехватывает трафик Telegram и перенаправляет его через WebSocket-соединение к серверам Telegram, минуя сетевые блокировки. Включил туннель — Telegram работает. Выключил — обычный режим. Настройка прокси внутри Telegram не нужна.

Android-порт [WSBridge-iOS](https://github.com/l1ratch/WSBridge-iOS): тот же lwIP-мост, тот же протокол обхода, тот же каскад эндпоинтов.

## Установка

Скачай APK из [Releases](https://github.com/l1ratch/WSBridge-Android/releases) и поставь напрямую. Дев-сборка с последнего пуша в main — в prerelease `preview`.

Все сборки подписаны одним ключом, `versionCode` растёт с каждым прогоном CI — новая версия ставится **поверх** установленной, удалять старую не нужно. (Первый переход со старых debug-сборок потребует одного удаления — их подпись была случайной.)

## Как это работает

Приложение поднимает локальный VPN (VpnService) с маршрутами только на диапазоны Telegram (`149.154.0.0/16`, `91.108.0.0/16`, `91.105.192.0/24`). TCP-трафик восстанавливается в поток через lwIP (NO_SYS=1, вендор 2.2.0), парсится 64-байтный MTProto init, и каждый MTProto-пакет уходит отдельным WS-фреймом через каскад: ротационные CF-фронты `kws{dc}.*` → прямые IP гейтвеев → `kws{dc}.web.telegram.org`.

Дополнительные режимы (поле «CF Worker» в меню):
- **pipe** — собственный Cloudflare Worker: сырой TCP-поток в WS, воркер сам коннектится к DC (`worker-pipe.js` из iOS-репо, без изменений);
- **relay** — прямое реле на своём VPS: `host:port`, сырой TCP без CF и WS.

Весь остальной трафик устройства туннель не трогает. DNS из туннеля форвардится на выбранный сервер (пресеты Google / Cloudflare / Comss.one / Malw.link или свой).

## Сборка

```sh
./gradlew testDebugUnitTest assembleDebug
```

Нужны: JDK 17+, Android SDK 36, NDK 27+, CMake. Нативная часть — lwIP 2.2.0 + JNI-мост (`app/src/main/cpp/`), собирается через externalNativeBuild.

CI при пуше в `main` собирает подписанный APK (prerelease `preview`) и релизный APK при пуше тега `v*` (GitHub Releases). Подпись — секреты `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; стабильный ключ гарантирует, что обновление ставится поверх без удаления. `versionCode` = номер прогона CI (`BUILD_NUMBER`).

## Отличия от iOS-версии

- `PacketTunnelProvider` → `VpnService` (TUN fd вместо NEPacketFlow);
- `URLSessionWebSocketTask` → OkHttp WebSocket; прямые IP гейтвеев — через кастомный DNS-резолвер (SNI/Host/сертификат остаются доменными, TCP уходит на IP);
- нет IPC-слоя (Darwin notifications, JournalServer): сервис и UI в одном процессе, журнал читается напрямую;
- DNS-форвардер живой (на iOS его заменял системный NEDNSSettings, там он лежал мёртвым кодом);
- DoH/DoT-пресеты не перенесены: запросы идут plain UDP:53 из туннеля, шифрованный DNS — задача системного private DNS.

## Лицензия

MIT. Портируемая логика — [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT), lwIP — BSD-подобная.

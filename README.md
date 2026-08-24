# MsgCrypt

MsgCrypt — единый текстовый клиент WhatsApp и Telegram для Android и iOS с
обязательным дополнительным end-to-end шифрованием на устройстве.

## Возможности

- до 2 аккаунтов WhatsApp и до 2 аккаунтов Telegram одновременно;
- вход WhatsApp по QR-коду;
- вход Telegram по номеру, коду и паролю 2FA;
- общий список аккаунтов, чатов и локальная история;
- только текст: отправка и получение медиа намеренно не реализованы;
- новые исходящие сообщения передаются только после установки защищённой
  MsgCrypt-сессии и подтверждения отпечатка собеседника;
- алгоритмы перенесены из
  [CryptoLayer](https://github.com/igmunv/cryptolayer): P-256 ECDH, P-256 ECDSA,
  AES-256-GCM и WordCoder. Формат MsgCrypt v1 описан в
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

Оба собеседника должны использовать MsgCrypt. Официальные клиенты WhatsApp и
Telegram увидят служебные WordCoder-пакеты и не смогут расшифровать их.

## Структура

```text
android/                  Java-приложение Android
ios/                      SwiftUI-приложение iOS
bridge/whatsmeow/         Go/Gomobile bridge для WhatsMeow
protocol/                 общие тестовые векторы
docs/                     архитектура, безопасность и wire protocol
```

## Сборка

Для Telegram пользователь вводит собственные `api_id` и `api_hash`, полученные
на <https://my.telegram.org>, непосредственно на экране входа. В исходники,
GitHub Secrets и артефакты сборки они не попадают.

Локальная сборка для проекта не требуется и не используется. Workflow
[`Android APK`](.github/workflows/android.yml) скачивает готовый Android AAR
TDLib `1.8.66-022d602` с JNI для четырёх ABI, проверяет его SHA-256, собирает
только WhatsMeow Gomobile bridge и публикует APK. Официальные Maven-артефакты
TDLight Java содержат desktop JNI, но не Android `libtdjni.so`, поэтому при
запрете локальной сборки TDLib используется совместимый prebuilt Android AAR.
Workflow [`iOS unsigned IPA`](.github/workflows/ios.yml) подключает готовый
`TDLibFramework` `1.8.66-022d6020` (checksum закреплён upstream), собирает
WhatsMeow XCFramework и публикует **неподписанный IPA**. Оба запускаются на
каждый push и вручную через `workflow_dispatch`.

## Важное предупреждение

WhatsMeow использует неофициальный WhatsApp Web API. Meta может изменить
протокол или ограничить аккаунт. MsgCrypt не связан с WhatsApp, Meta, Telegram
или авторами используемых библиотек. Перед реальным использованием нужна
независимая криптографическая проверка реализации.

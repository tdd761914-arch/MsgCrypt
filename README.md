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
bridge/tdlight-android/    сборка Android JNI для TDLight Java
protocol/                 общие тестовые векторы
docs/                     архитектура, безопасность и wire protocol
```

## Сборка

Для Telegram нужен собственный `api_id` и `api_hash`, полученный на
<https://my.telegram.org>. Они не коммитятся в Git.

### Android

```sh
cd android
cp local.properties.example local.properties
# заполнить MSGCRYPT_TELEGRAM_API_ID и MSGCRYPT_TELEGRAM_API_HASH
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Скрипт `scripts/build-whatsmeow-android.sh` создаёт WhatsMeow AAR.
`scripts/build-tdlight-android.sh` собирает совместимый `libtdjni.so` для
TDLight Java. GitHub Actions выполняет эти шаги и публикует APK как artifact.

### iOS

```sh
brew install xcodegen
cd ios
xcodegen generate
xcodebuild -scheme MsgCrypt -destination 'platform=iOS Simulator,name=iPhone 16' test
```

`TDLibFramework` закреплён на `1.8.66-022d6020` с проверкой SHA-256. WhatsMeow
XCFramework создаёт `scripts/build-whatsmeow-ios.sh`. Для IPA необходимы
Apple signing certificate и provisioning profile; CI без этих секретов строит
и тестирует simulator-приложение.

## Важное предупреждение

WhatsMeow использует неофициальный WhatsApp Web API. Meta может изменить
протокол или ограничить аккаунт. MsgCrypt не связан с WhatsApp, Meta, Telegram
или авторами используемых библиотек. Перед реальным использованием нужна
независимая криптографическая проверка реализации.


# Архитектура MsgCrypt

## Ограничения продукта

`AccountStore` разрешает не больше двух записей каждого типа. Каждый аккаунт
имеет отдельный каталог SDK, историю, идентификатор узла и ключи. Удаление
аккаунта удаляет только его каталог и связанные ключи.

```text
UI (Accounts -> Chats -> Messages)
                 |
          TextMessageService
          /                \
 WhatsAppGateway       TelegramGateway
 WhatsMeow/Gomobile    TDLight Java / TDLibFramework
          \                /
             CryptoSession
       ECDH + HKDF + AES-GCM + ECDSA
                      |
                  WordCoder
```

Провайдеры передают только текст. Перед отправкой пользовательский текст
обязательно проходит `CryptoSession.seal`; прямого публичного метода отправки
открытого текста у UI нет. Входящие тексты сначала проходят детектор carrier,
сборку чанков, проверку подписи и AES-GCM. Не-MsgCrypt текст сохраняется как
исторический `plain/legacy`, но ответить открытым текстом приложение не даёт.

## Состояния авторизации

- WhatsApp: `new -> qr -> connecting -> ready`.
- Telegram: `new -> phone -> code -> password? -> ready`.
- Любая ошибка переводит аккаунт в `error`, сохраняя возможность повторить
  текущий шаг.

## Состояния защищённого диалога

`none -> helloSent/helloReceived -> keyReady -> verified`.

Полученный ключ сначала имеет статус `keyReady`. Пользователь сравнивает
отпечаток с собеседником по независимому каналу и подтверждает его. Отправка
DATA разрешена только в `verified`. Смена identity key сбрасывает доверие.


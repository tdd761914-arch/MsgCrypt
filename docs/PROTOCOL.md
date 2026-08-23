# MsgCrypt wire protocol v1

Все целые числа — unsigned big-endian. Размеры проверяются до выделения памяти.

## Примитивы

- identity/signature: ECDSA P-256 + SHA-256, X9.62 compressed public key;
- key agreement: ephemeral ECDH P-256;
- KDF: HKDF-SHA256, 32 bytes;
- payload: AES-256-GCM, nonce 12 bytes, tag 16 bytes;
- camouflage: фиксированный WordCoder 1 byte -> 1 unique word.

## SignedPacket

```text
magic "MC"        2
version           u8 = 1
kind              u8 (1 HELLO, 2 DATA, 3 CLOSE)
senderNodeId      16
sessionId         16
messageId         16
timestamp         u64 (Unix seconds)
counter           u64
payloadLength     u32
payload           payloadLength
signatureLength   u16
signature         DER ECDSA
```

Подпись вычисляется над всеми байтами от `magic` до конца `payload`, без полей
`signatureLength/signature`.

HELLO payload:

```text
identitySigningPublicKey  33
ephemeralEcdhPublicKey    33
```

HELLO самоподписан ключом, содержащимся в payload. DATA проверяется закреплённым
identity key собеседника.

DATA payload: `nonce[12] || AES-GCM(ciphertext || tag)`. AAD — заголовок
SignedPacket без `payloadLength`. Открытые данные:

```text
contentType       u8 = 1 (UTF-8 text)
sentAt            u64
textLength        u32
text              textLength (maximum 16384 bytes)
```

Session key:

```text
salt = SHA256(min(ephemeralPubA, ephemeralPubB) || max(...))
info = UTF8("MsgCrypt/1/session/") || sessionId
key  = HKDF-SHA256(ecdhSecret, salt, info, 32)
```

## Carrier и чанки

SignedPacket делится на чанки до 160 bytes. Каждый текст транспорта имеет вид:

```text
MSGCRYPT1 <message-id-hex> <index>/<count> <word> <word> ...
```

Индекс начинается с нуля. Максимум 512 чанков, 65536 bytes на собранный пакет,
TTL незавершённой сборки — 10 минут. Дубликаты идентичного чанка игнорируются;
конфликтующие дубликаты отбрасывают всю сборку.


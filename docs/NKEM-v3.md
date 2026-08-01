# NKEM v3 Hybrid container

NKEM v3 is the default NekoKEM Hybrid file container. It preserves the v2
X448 and ML-KEM-1024 operations, the `x448_secret || mlkem_secret` input
ordering, HKDF-SHA512, and AES-256-GCM. Its protocol change is an independent
32-byte HKDF salt instead of reusing the 12-byte GCM nonce as that salt.

This is a project-specific experimental format, not a standardized Hybrid KEM
protocol.

## Header

The fixed header is 32 bytes. Multibyte integers use big-endian encoding.

| Offset | Length | Field |
|---:|---:|---|
| 0 | 4 | Magic: ASCII `NKEM` |
| 4 | 1 | Version: `3` |
| 5 | 1 | Algorithm ID: `3` |
| 6 | 2 | Header length: `32` |
| 8 | 2 | X448 ephemeral public-key length: `56` |
| 10 | 2 | Reserved: `0` |
| 12 | 4 | ML-KEM ciphertext length |
| 16 | 8 | AES-GCM ciphertext length |
| 24 | 1 | HKDF salt length: `32` |
| 25 | 1 | AES-GCM nonce length: `12` |
| 26 | 1 | Authentication tag length: `16` |
| 27 | 1 | Reserved: `0` |
| 28 | 4 | Flags/reserved: `0` |

Algorithm ID 3 means:

```text
X448 + ML-KEM-1024 / HKDF-SHA512 / AES-256-GCM / split salt and nonce
```

## Body

The fields immediately following the header are:

```text
56-byte X448 ephemeral public key ||
ML-KEM-1024 ciphertext ||
32-byte HKDF salt ||
12-byte AES-GCM nonce ||
AES-GCM ciphertext ||
16-byte authentication tag
```

Both salt and nonce are generated independently for every encryption with
separate `RAND_bytes()` calls.

## Key derivation and authentication

The existing Hybrid shared-secret derivation is unchanged:

```text
IKM = x448_secret || mlkem_secret
AES key = HKDF-SHA512(IKM, salt, existing Hybrid info, 32 bytes)
```

For compatibility with the unchanged Hybrid KDF implementation, the fixed
HKDF info remains:

```text
NekoKEM v2 HYBRID-X448-MLKEM1024/HKDF-SHA512/AES-256-GCM
```

AES-256-GCM uses only the independent 12-byte nonce as its IV. The exact AAD
byte sequence is:

```text
header ||
X448 ephemeral public key ||
ML-KEM ciphertext ||
HKDF salt ||
AES-GCM nonce
```

Consequently, changes to the header, salt, nonce, ephemeral public key, or KEM
ciphertext fail GCM authentication. Decryption validates the version,
algorithm ID, fixed lengths, reserved fields, total size, and absence of
trailing data before committing atomic plaintext output.

## Compatibility

- `nekokem_encrypt_file()` writes v3.
- `nekokem_decrypt_file()` reads the magic/version prefix and strictly
  dispatches v1, v2, or v3.
- `nekokem_encrypt_file_v1()` and `nekokem_decrypt_file_v1()` retain v1.
- `nekokem_encrypt_file_v2()` and `nekokem_decrypt_file_v2()` retain v2 for
  compatibility tests and existing consumers.
- Legacy v1/v2 header decoders reject v3 because both its version and
  algorithm ID are distinct.

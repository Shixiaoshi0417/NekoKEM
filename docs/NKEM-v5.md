# NKEM v5 signed container

NKEM v5 adds sender authentication to the multi-recipient construction of
[NKEM v4](NKEM-v4.md). The sender signs the whole container with a hybrid
Ed448 + ML-DSA-87 signature; a recipient accepts the file only when both
signatures verify. The signature and the sender's public identity are
encrypted under the file key, so only recipients learn who sent the file.

One container format covers 1 to 64 recipients. NKEM v3 and v4 stay
unchanged and are still written when the user explicitly encrypts without a
signature (see [Signature policy](#signature-policy)).

This is a project-specific experimental format, not a standardized signed
or multi-recipient Hybrid KEM protocol.

## Identity keys

Signing needs a four-component **identity key**. The two encryption
components are the existing X448 and ML-KEM-1024 key pair, so upgrading a
key keeps every older file decryptable.

| Component | Algorithm ID | Raw public key | Standard |
|---|---:|---:|---|
| X448 | `0x01` | 56 bytes | RFC 7748 u-coordinate, little-endian |
| ML-KEM-1024 | `0x02` | 1568 bytes | FIPS 203 encapsulation key |
| Ed448 | `0x03` | 57 bytes | RFC 8032 encoded point |
| ML-DSA-87 | `0x04` | 2592 bytes | FIPS 204 public key |

### Canonical identity encoding

Fingerprints, signatures and the sender identity embedded in a container all
use one fixed binary encoding. It never depends on PEM, DER or OpenSSL
serialization details, and every identity has exactly one valid encoding.
Multibyte integers are big-endian.

```text
identity encoding (4291 bytes) =
    "NKID"                 4 bytes, ASCII magic
    0x01                   encoding version
    0x04                   component count
    component 0x01 || component 0x02 || component 0x03 || component 0x04

component = algorithm ID (1) || raw public-key length (2) || raw public key
```

Decoding requires the exact magic, version, count, algorithm IDs, order and
lengths above, and no trailing bytes. It then rejects:

- an X448 key whose little-endian `u` is not below
  `p = 2^448 - 2^224 - 1`, or whose `u` is `0`, `1` or `p - 1`;
- an ML-KEM-1024 key that fails the FIPS 203 encapsulation-key modulus
  check;
- an Ed448 encoding whose `y` (the low 455 bits) is not below `p`, whose
  `x` sign bit is set while `x` would be zero, or that does not decode to a
  curve point;
- an Ed448 key of small order: `y = 1` or `y = p - 1` with the sign bit
  clear, or `y = 0` with either sign bit;
- an ML-DSA-87 key that OpenSSL cannot import.

These checks inspect the bytes themselves and do not depend on OpenSSL
reason codes.

### Identity fingerprint

```text
identity fingerprint = SHA-256("NekoKEM v5 identity fingerprint" || identity encoding)
```

The label is ASCII without a terminating NUL. The fingerprint is displayed
like the existing public-key fingerprint: 32 bytes as uppercase,
colon-separated hex. Front ends label it "identity fingerprint" so it is
not confused with the legacy fingerprint of a two-component key, which is
unchanged:

```text
legacy fingerprint = SHA-256("NekoKEM v2 hybrid public-key fingerprint" ||
                             u32 len || DER(X448) || u32 len || DER(ML-KEM-1024))
```

### Public-key file

An identity public-key file holds four PEM `PUBLIC KEY` blocks in the
component order above, followed only by whitespace. Core parses the PEM,
extracts the raw keys, rebuilds the canonical encoding and computes the
fingerprint from that encoding, never from the PEM text.

A two-component file (X448, ML-KEM-1024) is still accepted as a recipient
key. It cannot verify signatures. NekoKEM 4.4.0 and earlier reject a
four-component file, so front ends also offer a legacy export with only the
two encryption blocks.

### NKPR v2 private key

NKPR v2 is NKPR v1 with version byte `2`. Header layout, Argon2id
parameters (64 MiB, 3 iterations, 4 lanes), AES-256-GCM and the AAD
(header, salt and nonce) are unchanged. The decrypted plaintext holds four
PEM private keys in component order, followed only by whitespace. Loading
checks that every private key's public part matches the component of the
same position and rejects the degenerate X448 key as v1 does.

Because the version byte is authenticated as AAD, a v2 container cannot be
relabelled as v1. NekoKEM 4.4.0 and earlier cannot read v2 and report it
as a wrong password: "Private-key password is incorrect or NKPR data is
corrupted" in the CLI. The password is not the problem; the key needs NekoKEM 5.0.0 or later.
Core still reads NKPR v1 and plaintext two-component PEM keys.

Upgrading a key generates Ed448 and ML-DSA-87 keys, keeps the X448 and
ML-KEM-1024 keys, and atomically replaces both files under the existing pair
lock, with the same password.

## Header

The fixed header is 32 bytes. It is the v4 header with two reserved bytes
reused for the signature block length.

| Offset | Length | Field |
|---:|---:|---|
| 0 | 4 | Magic: ASCII `NKEM` |
| 4 | 1 | Version: `5` |
| 5 | 1 | Algorithm ID: `5` |
| 6 | 2 | Header length: `32` |
| 8 | 2 | Recipient count: `1` to `64` |
| 10 | 2 | Recipient entry length: `1672` |
| 12 | 2 | Encrypted signature block length: `9048` |
| 14 | 2 | Reserved: `0` |
| 16 | 8 | Payload ciphertext length |
| 24 | 1 | HKDF salt length: `32` |
| 25 | 1 | AES-GCM nonce length: `12` |
| 26 | 1 | Authentication tag length: `16` |
| 27 | 1 | Header MAC length: `64` |
| 28 | 4 | Flags/reserved: `0` |

Algorithm ID 5 means:

```text
X448 + ML-KEM-1024 per recipient / HKDF-SHA512 / AES-256-GCM key wrap /
HMAC-SHA512 header MAC / AES-256-GCM payload /
Ed448 + ML-DSA-87 sender signature in an AES-256-GCM signature block
```

## Body

```text
32-byte HKDF salt ||
recipient entry 1 || ... || recipient entry N ||
64-byte header MAC ||
12-byte payload nonce ||
payload ciphertext || 16-byte payload tag ||
9032-byte encrypted signature block || 16-byte signature block tag
```

Recipient entries have the v4 layout of exactly 1672 bytes:

```text
56-byte X448 ephemeral public key ||
1568-byte ML-KEM-1024 ciphertext ||
32-byte wrapped file key ||
16-byte wrap authentication tag
```

The signature block plaintext is 9032 bytes:

```text
4291-byte sender identity encoding ||
114-byte Ed448 signature ||
4627-byte ML-DSA-87 signature
```

A container of `L` payload bytes for `N` recipients is exactly
`9204 + 1672 * N + L` bytes; v4 is `156 + 1672 * N + L`.

## Key derivation

Encryption draws a random 32-byte file key, a 32-byte HKDF salt and a
12-byte payload nonce with independent `RAND_bytes()` calls. All HKDF calls
use SHA-512 and the container's salt. Every label is ASCII without NUL.

```text
wrap key      = HKDF(x448_secret || mlkem_secret,
                     info = "NekoKEM v5 recipient wrap X448-MLKEM1024" ||
                            x448_ephemeral_public || recipient_x448_public,
                     32 bytes)
payload key   = HKDF(file_key, info = "NekoKEM v5 payload AES-256-GCM", 32 bytes)
MAC key       = HKDF(file_key, info = "NekoKEM v5 header HMAC-SHA512", 64 bytes)
signature key = HKDF(file_key, info = "NekoKEM v5 signature block AES-256-GCM", 32 bytes)
```

Every label differs from its v4 counterpart, so no key derived for one
version is ever used for another.

## Wrapping, header MAC and payload

These steps are those of v4 with the v5 keys:

```text
wrapped file key || wrap tag =
    AES-256-GCM(wrap key, nonce = 12 zero bytes, file_key,
                AAD = header || salt || x448_ephemeral_public || mlkem_ciphertext)

header MAC = HMAC-SHA512(MAC key, header || salt || all recipient entries)

payload ciphertext || payload tag =
    AES-256-GCM(payload key, payload nonce, file data,
                AAD = header || salt || recipient entries || header MAC || payload nonce)
```

The header MAC commits to the file key, as described in
[NKEM v4](NKEM-v4.md#header-mac-and-key-commitment). The payload is
processed in 64 KiB chunks and keeps the `2^36 - 32` byte AES-GCM limit.

## Payload digest

The signature covers the payload through a two-level SHA-512 tree, so the
leaves can be hashed in parallel. The ciphertext of `L` bytes is split into
`n = ceil(L / 1048576)` leaves of 1 MiB; only the last may be shorter, and an
empty payload has no leaves. `u64be` is an 8-byte big-endian integer and the
labels are ASCII without NUL.

```text
leaf i         = SHA-512("NekoKEM v5 payload leaf" || u64be(i) ||
                         payload ciphertext[i * 1048576 .. min((i + 1) * 1048576, L)])
payload digest = SHA-512("NekoKEM v5 payload root" || u64be(L) ||
                         leaf 0 || ... || leaf n-1 || payload tag)
```

Every leaf and the root are fixed-layout inputs with distinct labels, and
the root fixes `L` and therefore `n`, so finding two payloads with one
digest needs a SHA-512 collision. The leaf size is part of the format: a
different split gives a different digest.

Core hashes leaves on up to four worker threads (one per online processor)
while the calling thread runs AES-GCM and file I/O, and hashes inline when
threads cannot start or the payload has one leaf. The digest does not depend
on the number of threads.

Test vector: `L = 2621443` (2.5 MiB + 3 bytes) with ciphertext byte `i`
equal to `i mod 251` and the payload tag `00 01 02 ... 0f` give

```text
829a3198f9894e5f0008a34575168f0cc436a5a341cb9511b6ed501e69277962
ecbcc712915fd1602efe47c2ea4a8c4ff80c61638a71e62790dd1208c35d744d
```

## Signature

```text
M = "NekoKEM v5 sender signature" ||
    header || salt || all recipient entries || header MAC || payload nonce ||
    payload digest ||
    sender identity encoding

Ed448 signature     = Ed448(sender Ed448 key, M, context = "NekoKEM v5 Ed448")
ML-DSA-87 signature = ML-DSA-87.Sign(sender ML-DSA key, M,
                                     context = "NekoKEM v5 ML-DSA-87")
```

Ed448 is the RFC 8032 Ed448 variant with a context string, not Ed448ph.
ML-DSA-87 uses the pure (non-prehash) FIPS 204 interface with OpenSSL's
default hedged randomness. Both come from OpenSSL 4.0.3 through EVP.

```text
encrypted signature block || signature block tag =
    AES-256-GCM(signature key, nonce = 12 zero bytes, signature block plaintext,
                AAD = header || salt || all recipient entries || header MAC ||
                      payload nonce || payload tag)
```

Each signature key comes from a fresh random file key and encrypts exactly
one message, so the fixed nonce never repeats under one key.

What this provides:

- Only the holder of both signing keys can produce a container that
  verifies. Either algorithm alone remaining secure is enough, because both
  signatures must verify and algorithm ID 5 always carries both.
- The signature covers every recipient entry and the header MAC, which
  commits to the file key. A recipient who re-encrypts the content for
  someone else changes the entries, so the original signature no longer
  verifies and the recipient cannot present it as the sender's message to a
  new recipient.
- The ciphertext enters `M` through the SHA-512 payload digest, not through
  the GCM tag. GCM is not collision resistant for anyone who knows its key,
  and every recipient knows the file key.
- `M` includes the sender's whole identity, so a signature cannot be
  attributed to another identity even if a signature algorithm lacks
  exclusive ownership.
- Non-recipients cannot see the sender identity or the signatures.

What it does not provide:

- Deniability. A recipient can show a third party that the sender signed the
  container.
- Freshness. There is no timestamp or replay protection; resending an
  unchanged container verifies again.
- Protection from a recipient who knows the file key replacing the
  signature block with their own signature. The result names that recipient
  as the sender, which is then true.

## Encryption

1. Load and validate every recipient public key (two- or four-component; only
   the encryption components are used) and the sender's identity private
   key. The recipient rules of v4 apply: 1 to 64 keys, no two keys the same
   or sharing a component. Nothing is created on failure.
2. Write the header, salt, recipient entries and header MAC as above.
3. Stream the payload, feeding the ciphertext and then the tag into the
   payload digest.
4. Sign `M`, encrypt the signature block and append it.
5. Commit the output atomically.

## Decryption

1. Validate the version, algorithm ID, fixed lengths, recipient count,
   signature block length, reserved fields, total size and absence of
   trailing data. The X448 ephemeral key checks of v4 apply to every entry.
2. Try every entry with the constant-time procedure of
   [NKEM v4](NKEM-v4.md#decryption), and verify the header MAC before any
   ciphertext is read. A key with no entry and modified entries or MAC take
   the same path and report the same error.
3. Decrypt the payload into a private temporary file while feeding the
   ciphertext and then the tag into the payload digest, and verify the
   payload tag.
4. Decrypt the signature block. A failed signature block tag is a signature
   failure.
5. Decode and validate the sender identity encoding, rebuild `M`, and verify
   the Ed448 and the ML-DSA-87 signature. Both must verify.
6. Compute the sender identity fingerprint, apply the
   [signature policy](#signature-policy), and only then commit the output
   atomically.

No output is committed on any failure, including a valid payload with an
invalid signature.

## Signature policy

Core enforces the policy. Front ends translate settings into policy flags
and pass the public-key files the user trusts (contacts and the user's own
key); they never compare fingerprints or decide on their own whether to
accept a file.

Decryption reports one of:

| Status | Meaning |
|---|---|
| Unsigned | NKEM v3 or v4; no sender information |
| Trusted | NKEM v5; the signer's identity matches a trusted key, whose index is returned |
| Valid, untrusted | NKEM v5; signatures verify, but the signer is not in the trusted list |

With the require-signature flag, Core rejects v3 and v4 before committing
output. With the require-trusted flag, Core also rejects a valid v5 signer
outside the trusted list. An invalid signature is never a status: it is an
error and no output exists. Policy failures use dedicated error codes so
front ends can explain them. On request Core writes the signer's identity as
a four-component public-key file, for "add sender to contacts".

Encryption signs by default. Core writes v3 or v4 only when the caller
passes an explicit unsigned flag, and reports which version it wrote, so
front ends show their warning from Core's result rather than their own
guess. A missing sender key or password is an error, never a silent
fallback to an unsigned format.

The existing `nekokem_encrypt_file*()` and `nekokem_decrypt_file*()` calls
keep their behaviour. The decryption calls also accept v5, verify it
completely and reject an invalid signature; they apply no policy flags.

## Limits and boundaries

- 1 to 64 recipients, as in v4. The recipient count and the file length are
  visible to anyone who holds the file.
- Recipient entries carry no identifier, exactly as in v4.
- The signing private key is unlocked with the private-key password for each
  signed encryption and freed immediately afterwards.
- Trust in a sender is trust in their identity fingerprint. Users must
  compare it with the sender over another channel before adding a key to
  their contacts.

## Compatibility

- NekoKEM 5.0.0 reads NKEM v3, v4 and v5, NKPR v1 and v2, and two- and
  four-component public keys.
- NekoKEM 4.4.0 and earlier reject v5 containers, NKPR v2 and
  four-component public keys without committing output.
- Encrypting without a signature writes v3 for one recipient and v4 for two
  or more, byte-for-byte as before.
- Contacts are deduplicated by their encryption components. A
  four-component key may upgrade a two-component contact after the user
  confirms the new identity fingerprint; a two-component key never replaces
  a four-component contact.

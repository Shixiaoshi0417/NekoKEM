# NKEM v4 multi-recipient container

NKEM v4 encrypts one file once for up to 64 recipients. Every listed
recipient decrypts the same container with their own private key; nobody
else can. NKEM v3 remains the single-recipient format and is unchanged.
Encryption to exactly one public key still writes v3, including through the
multi-recipient API, so existing applications keep reading
single-recipient files.

This is a project-specific experimental format, not a standardized
multi-recipient or Hybrid KEM protocol.

## Construction overview

1. Encryption draws a random 32-byte file key, a 32-byte HKDF salt and a
   12-byte payload nonce with independent `RAND_bytes()` calls.
2. For every recipient it performs the same X448 + ML-KEM-1024
   encapsulation as v3, derives a one-time wrap key with HKDF-SHA512 and
   wraps the file key with AES-256-GCM.
3. A header MAC, keyed from the file key, authenticates the header, the
   salt and every recipient entry.
4. The file data is encrypted once with AES-256-GCM under a payload key
   derived from the file key. Its AAD is every byte that precedes the
   ciphertext.

## Header

The fixed header is 32 bytes. Multibyte integers use big-endian encoding.

| Offset | Length | Field |
|---:|---:|---|
| 0 | 4 | Magic: ASCII `NKEM` |
| 4 | 1 | Version: `4` |
| 5 | 1 | Algorithm ID: `4` |
| 6 | 2 | Header length: `32` |
| 8 | 2 | Recipient count: `1` to `64` |
| 10 | 2 | Recipient entry length: `1672` |
| 12 | 4 | Reserved: `0` |
| 16 | 8 | AES-GCM ciphertext length |
| 24 | 1 | HKDF salt length: `32` |
| 25 | 1 | AES-GCM nonce length: `12` |
| 26 | 1 | Authentication tag length: `16` |
| 27 | 1 | Header MAC length: `64` |
| 28 | 4 | Flags/reserved: `0` |

Algorithm ID 4 means:

```text
X448 + ML-KEM-1024 per recipient / HKDF-SHA512 / AES-256-GCM key wrap /
HMAC-SHA512 header MAC / AES-256-GCM payload
```

## Body

```text
32-byte HKDF salt ||
recipient entry 1 || ... || recipient entry N ||
64-byte header MAC ||
12-byte payload nonce ||
AES-GCM ciphertext ||
16-byte payload authentication tag
```

Each recipient entry is exactly 1672 bytes:

```text
56-byte X448 ephemeral public key ||
1568-byte ML-KEM-1024 ciphertext ||
32-byte wrapped file key ||
16-byte wrap authentication tag
```

Entries carry no recipient identifier or fingerprint. A container reveals
how many recipients it has, but not who they are.

## Key derivation

All HKDF calls use SHA-512 and the container's 32-byte salt.

```text
wrap key  = HKDF(x448_secret || mlkem_secret,
                 info = "NekoKEM v4 recipient wrap X448-MLKEM1024" ||
                        x448_ephemeral_public || recipient_x448_public,
                 32 bytes)
payload key = HKDF(file_key, info = "NekoKEM v4 payload AES-256-GCM", 32 bytes)
MAC key     = HKDF(file_key, info = "NekoKEM v4 header HMAC-SHA512", 64 bytes)
```

The wrap-key input keeps v3's `x448_secret || mlkem_secret` ordering. Its
info also binds the X448 ephemeral public key and the recipient's X448
public key, as the X-Wing combiner does, so each wrap key depends on the
exact X448 exchange that produced it.

## Wrapping the file key

```text
wrapped file key || wrap tag =
    AES-256-GCM(wrap key, nonce = 12 zero bytes, file_key,
                AAD = header || salt || x448_ephemeral_public ||
                      mlkem_ciphertext)
```

Every wrap key comes from a fresh ephemeral X448 key and a fresh ML-KEM
encapsulation and encrypts exactly one 32-byte message. The fixed nonce
therefore never repeats under one key.

## Header MAC and key commitment

```text
header MAC = HMAC-SHA512(MAC key, header || salt || all recipient entries)
```

AES-GCM does not commit to its key. Without the MAC, a malicious sender
could give two recipients different file keys whose payload tags both
verify, so each would read different plaintext. HMAC-SHA512 commits to its
key: two recipients who both accept the same MAC necessarily hold the same
file key, and therefore decrypt the same plaintext. The MAC also detects any
change to a recipient entry that its own recipient would not notice.

## Payload

```text
AAD = header || salt || recipient entries || header MAC || nonce
ciphertext || tag = AES-256-GCM(payload key, nonce, file data, AAD)
```

The payload is processed in 64 KiB chunks exactly as in v3 and keeps the
`2^36 - 32` byte AES-GCM limit.

## Decryption

1. Validate the version, algorithm ID, fixed lengths, recipient count,
   reserved fields, total size and absence of trailing data.
2. Unlock the private key. Loading rejects the one X448 private key whose
   public key is the all-zero point (the clamped scalar four times the
   prime subgroup order); see step 3. For every entry, decapsulate X448 and
   ML-KEM-1024, derive the wrap key and try to unwrap the file key. ML-KEM
   rejects implicitly, so another recipient's entry fails at the wrap tag.
   Every entry is tried, including after a match, and the first match is
   kept with a constant-time select. Nothing is reported per entry.
3. If any entry's X448 ephemeral key is rejected, the whole container is
   rejected as malformed, wherever that entry is. Only OpenSSL's rejection
   of an all-zero shared secret counts as a rejection; any other OpenSSL
   failure, such as an allocation, aborts decryption as an error. With the
   degenerate private key refused in step 2, only a small-order ephemeral
   key gives an all-zero secret, whatever the private key, and no correct
   encryptor writes one, so this check depends on the container alone.
4. Recompute the header MAC and compare it in constant time before reading
   any ciphertext. When no entry unwrapped, the MAC is still computed, over
   an all-zero file key, and the file is rejected.
5. Decrypt the payload into a private temporary file and commit it
   atomically only after the payload tag verifies and no trailing data
   remains.

A key with no entry and a file whose entries or MAC were modified take the
same path through step 4 and report the same error. Neither the error nor
the time taken shows which entry belongs to the key. Otherwise someone who
can submit modified files for decryption could break one wrap tag at a time
and learn which entry is whose. No output is created on any failure.

This protects which entry is whose, not whether a key can decrypt the file
at all. Anyone who can submit a file for decryption and see the result
learns that from the unmodified file, which simply decrypts. Likewise, a
file whose header verifies but whose payload was modified is processed and
fails at the payload tag, with the payload authentication error and
progress reports, while a key with no entry stops at step 4.

## Limits and boundaries

- At most 64 recipients per container; the recipient list adds 1672 bytes
  per recipient. Encryption rejects an empty list, more than 64 keys and
  two public keys that are the same or share either component, before
  creating output. A genuine key pair shares no component with another key,
  so a shared component means a key file was copied or spliced.
- Every recipient shares the file key. A recipient can therefore create a
  different container that reuses the original recipient entries and header
  MAC. NKEM has no sender authentication in v3 or v4, so recipients must not
  treat a container's recipient list as proof of who sent it.
- The recipient count is visible to anyone who holds the file, as is the
  file length.
- Front ends pass recipients as a list of public-key files. Core validates
  every public key itself and never substitutes, skips or reorders a
  recipient that fails validation; the whole operation fails instead.

## Compatibility

- `nekokem_encrypt_file_multi_with_progress()` writes v4 for two or more
  public keys and v3 for one.
- `nekokem_encrypt_file*()` continues to write v3.
- `nekokem_decrypt_file*()` accepts v3 and v4 and rejects v1, v2 and other
  versions without committing output.
- NekoKEM v3.3.2 and earlier cannot read v4 containers.

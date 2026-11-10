# Key commitment: assessment and design

Status: proposal. This document changes no format; it records where
NekoKEM's use of AES-GCM depends on key commitment, which uses are already
covered, and what a future format would change for the one that is not.

## Background

AES-GCM is not key-committing: a ciphertext and tag can be built that
verify under several keys, each giving a different plaintext. For `k`
chosen keys this takes about `k` blocks of ciphertext and a linear solve
over GF(2^128). Two attacks follow from it:

- **Inconsistent recipients** ("invisible salamanders", Dodis et al. 2018;
  Albertini et al. 2020). A sender gives two recipients different keys for
  one ciphertext, and each reads a different, authenticated plaintext.
- **Partitioning oracles** (Len, Grubbs and Ristenpart, USENIX Security
  2021). When a key is derived from a password, an attacker who can see
  whether a crafted ciphertext opens learns whether the password is one of
  `k` candidates per attempt, instead of one.

Both need an attacker who knows or chooses every key the ciphertext must
open under.

## Where NekoKEM uses AES-GCM

| Use | Key | Who can choose the keys |
|---|---|---|
| NKEM v3 payload | HKDF of one recipient's X448 + ML-KEM-1024 secrets | The sender, for the one recipient |
| NKEM v4/v5 recipient wrap | HKDF of each recipient's KEM secrets | The sender, for each intended recipient |
| NKEM v4/v5 payload | HKDF of the random file key | The sender |
| NKEM v5 signature block | HKDF of the file key | The sender |
| NKPR v1/v2 private key | Argon2id of the password, used directly | Whoever writes the NKPR file |

## Assessment

### NKEM v4 and v5: covered by the header MAC

Every recipient checks
`HMAC-SHA512(HKDF(file_key, ...), header || salt || entries)` before the
payload is decrypted ([NKEM v4](NKEM-v4.md#header-mac-and-key-commitment)).
HMAC-SHA512 commits to its key, so two recipients who accept the same file
hold the same file key. The payload, header MAC and signature keys all
derive from that file key, and AES-GCM decryption under a fixed key, nonce
and ciphertext has exactly one result, so both recipients read the same
plaintext and the same signature block. In v5 the signature also covers the
header MAC and a SHA-512 digest of the ciphertext.

A sender can still build one recipient entry that unwraps for two
recipients to different file keys. The header MAC then verifies for at most
one of them, and the other rejects the file as corrupted. That is a
malicious sender refusing service to one recipient, which sending a broken
file achieves as well.

**No change needed.**

### NKEM v3: not exploitable in practice

v3 has no header MAC. It has one recipient entry, though, and for a second
private key to open it, the second key's decapsulation of the same
ML-KEM-1024 ciphertext must yield a secret the sender knows. ML-KEM
rejects implicitly: decapsulating another recipient's ciphertext returns a
pseudorandom value derived from the secret rejection seed `z` of the
decapsulating key, which the sender does not know. The hybrid KDF mixes
that value into the payload key, so the sender cannot predict any payload
key but the intended recipient's, and cannot make the tag verify under it.
Two-component legacy keys use the same ML-KEM-1024 component.

v3 stays the unsigned single-recipient format for compatibility with
NekoKEM 4.x and earlier; changing it would defeat that purpose.

**No change needed.** The short phrase "AES-GCM provides no key commitment"
in earlier release notes is accurate for the primitive but overstates the
exposure; future release notes should say that NKEM v4/v5 commit to the
file key through the header MAC and that NKPR is the remaining
non-committing container.

### NKPR v1 and v2: partitioning oracle on the password

NKPR derives the AES-256-GCM key directly from the password with Argon2id
(64 MiB, 3 iterations, 4 lanes). An attacker who writes an NKPR file can
make it open under `k` passwords of their choice: they pay `k` Argon2id
evaluations offline and about `k` blocks of ciphertext. If the victim
then enters their real password into that file and the attacker learns
whether it opened, each attempt tests `k` candidates.

This needs both:

1. the victim entering their own password into a file the attacker wrote,
   for example a replaced key file or a "temporary private key" received
   from someone else; and
2. the attacker observing success or failure.

Core already reports a wrong password and a damaged container with one
message, which does not hide success itself. The exposure is low, but it is
the one place in NekoKEM where key commitment matters and is missing.

## Proposed NKPR v3

NKPR v3 would keep NKPR v2's header, parameters, plaintext and AAD rules,
with these changes:

```text
master      = Argon2id(password, salt, 64 MiB, t = 3, p = 4, 64 bytes)
cipher key  = HKDF-SHA512(master, info = "NekoKEM NKPR v3 AES-256-GCM", 32 bytes)
commitment  = HKDF-SHA512(master, info = "NekoKEM NKPR v3 key commitment", 32 bytes)

file = header (version 3) || salt || commitment || nonce || ciphertext || tag
AAD  = header || salt || commitment || nonce
```

- The reader recomputes the commitment and compares it in constant time
  before running AES-GCM. A mismatch is reported exactly like a wrong
  password, and no plaintext is produced.
- HKDF-SHA512 is collision resistant in its key input, so an NKPR v3 file
  matches one Argon2id output and therefore, up to Argon2id collisions, one
  password. A crafted file then tests one candidate per attempt, the same
  as online guessing.
- Argon2id cost is unchanged: one evaluation per unlock.
- The 64-byte Argon2id output separates the cipher key from the
  commitment, so the commitment reveals nothing about the cipher key.

### Compatibility

- NekoKEM 5.0.x and earlier cannot read NKPR v3 and would report it as a
  wrong password, as 4.x does for v2. Readers keep accepting NKPR v1 and v2.
- Core would write v3 for new keys, password changes and upgrades only, and
  could rewrite an existing v1/v2 key on request with the same password.
- Exported private keys meant for an older NekoKEM would need an explicit
  "export as NKPR v2" option, as legacy public keys have today.

### Work and tests

About 2–3 days across Core, CLI, Android JNI, desktop backend and docs:

- Core: v3 reader and writer, constant-time commitment check, unified
  error, format version in `nekokem_private_key_*` reporting.
- Tests: known-answer vectors for `master`, cipher key and commitment; a
  crafted two-password NKPR v2 file that opens under both passwords and is
  rejected once relabelled or re-encoded as v3; v1/v2/v3 cross-reading;
  NKPR fuzz seeds for v3.
- Front ends: no new UI beyond the optional "export as NKPR v2".

## Recommendation

1. Do not introduce an NKEM v6 for key commitment; v4 and v5 already have
   it through the header MAC, and v3 is not exposed.
2. Use the precise wording above in the next release notes.
3. Implement NKPR v3 when the next private-key format change is planned,
   or earlier if importing private keys from other people becomes a
   supported workflow. Until then, the existing guidance stands: enter your
   password only into your own key files.

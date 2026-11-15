# Key commitment: assessment and design

Status: NKPR v3 is implemented. This document records where NekoKEM's use
of AES-GCM depends on key commitment, which uses were already covered, and
the private-key format that covers the one that was not.

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
| NKPR v3 private key | HKDF of the Argon2id output, with a stored commitment | Whoever writes the NKPR file |

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

NKPR v1 and v2 derive the AES-256-GCM key directly from the password with Argon2id
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
the one place in NekoKEM where key commitment mattered and was missing.

## NKPR v3

NKPR v3 keeps NKPR v2's header (with version byte `3`), Argon2id
parameters and plaintext, and changes how the key is derived and what the
file stores:

```text
master      = Argon2id(password, salt, 64 MiB, t = 3, p = 4, 64 bytes)
cipher key  = HKDF-SHA512(key = master, salt = salt,
                          info = "NekoKEM NKPR v3 AES-256-GCM", 32 bytes)
commitment  = HKDF-SHA512(key = master, salt = salt,
                          info = "NekoKEM NKPR v3 key commitment", 32 bytes)

file = header (version 3) || salt || commitment || nonce || ciphertext || tag
AAD  = header || salt || commitment || nonce
```

`salt` is the container's 32-byte random salt, used for both Argon2id and
HKDF. The info strings are ASCII without a terminator. A v3 file is exactly
32 bytes longer than a v2 file of the same key.

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
- The version byte is authenticated and the lengths differ, so a v2 file
  cannot be relabelled as v3 or the reverse.
- NKPR v3 holds a four-component identity key. A two-component key from
  NekoKEM 4.x stays NKPR v1 until it is upgraded to an identity key, which
  writes NKPR v3.

### What readers of old formats still owe the user

Readers keep accepting NKPR v1 and v2, so a forged file can still be an
NKPR v2 file: writing v3 protects nothing by itself. The commitment helps
where a password meets a file the user did not write, and there the front
ends act on the version before any password is entered:

- Core reports the NKPR version from the header alone
  (`nekokem_private_key_format_version()`), without a password.
- When a private key is imported, or another private key is chosen for one
  decryption, and the file is NKPR v1 or v2, the password prompt says that
  the file uses an older format without key commitment and that the
  password should be entered only if the file is the user's own. The CLI
  prints the same notice for any NKPR v1/v2 key other than
  `keys/private.key.enc`.
- The user's own NKPR v2 key is used without a notice. It can be rewritten
  as NKPR v3 with the same password: "Upgrade private key format" on
  Android, the "Upgrade key" page on the desktop apps, `upgrade-key` in the
  CLI (`nekokem_upgrade_private_key_format()` in Core). The rewrite is
  explicit because older versions cannot read the result. Core takes the
  public key too and refuses a pair that does not match, since the desktop
  apps keep the two selected files as the user's key pair afterwards.

### Compatibility

- NekoKEM 5.2 and earlier cannot read NKPR v3 and report it as a wrong
  password, as 4.x does for v2. The password is not the problem; the key
  needs a version that reads NKPR v3.
- Newer versions read NKPR v1, v2 and v3. They write v3 for new keys, for
  the identity upgrade of an NKPR v1 key, and for the explicit format
  upgrade of an NKPR v2 key; an existing v1 or v2 file is never rewritten
  on its own.
- There is no "export as NKPR v2". A private key that must stay readable by
  NekoKEM 5.2 should be backed up before its format is upgraded.

### Tests

- Known answers for `master`, the cipher key and the commitment, computed
  with an independent Argon2id and HKDF implementation, and a container
  built only from them that Core opens
  (`core/tests/key_commitment_tests.c`).
- A two-password NKPR v2 file, built with the multi-key attack on AES-GCM,
  that Core opens under both passwords; relabelled as v3 it is rejected, and
  rebuilt as v3 with a tag forged for both derived keys it opens only under
  the password it commits to.
- NKPR v1, v2 and v3 reading, the format upgrade and its refusals, parser
  mutations and fuzz seeds for v3.
- A key pair written by NekoKEM 5.2.0 (`core/tests/fixtures/nkpr-v2`) is
  read, announced and upgraded in the CLI, desktop bridge and Android
  tests.

## Recommendation

1. Do not introduce an NKEM v6 for key commitment; v4 and v5 already have
   it through the header MAC, and v3 is not exposed.
2. Use the precise wording above in release notes: NKEM v4/v5 commit to the
   file key through the header MAC, NKPR v3 commits to the password-derived
   key, and NKPR v1/v2 are the remaining non-committing containers.
3. Enter a password only into your own key files. The apps say so whenever
   a key file from elsewhere is in a format that cannot enforce it.

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

Core reports a wrong password, a damaged container and authenticated bytes
that are not a key with one message, "Private-key password is incorrect or
NKPR data is corrupted". A crafted file decrypts to a usable key under at
most one of its candidate passwords. Under the others AES-GCM accepts the
tag, the plaintext fails to parse, and the user sees the same message as for
a password outside the set. What still differs is timing: only an accepted
tag makes Core parse the plaintext, up to 1 MiB, before it fails, and a
front end may act differently afterwards. The partitioning oracle therefore
needs an attacker who can time the unlock, or who learns the outcome some
other way. The exposure is low, but it is the one place in NekoKEM where key
commitment matters and is missing, and it should not rest on a message
being identical.

The files at risk are the ones a user unlocks without having written them:
a private key imported on Android or the desktop, an Android "temporary
private key", and any key path chosen for decryption in the desktop apps or
the CLI. The key a NekoKEM installation generated and keeps in its own key
directory is not, unless an attacker can already replace files there.

## Proposed NKPR v3

Status: the reader, the writer and the Core legacy rule are implemented,
with the CLI rules below. The Android and desktop apps still pass the
own-key flag on every call, so they open every key as before; telling their
own key from imported ones is the remaining step.

### Format

NKPR v3 keeps the NKPR v2 header layout, Argon2id parameters (64 MiB,
3 iterations, 4 lanes, version 0x13), salt and nonce sizes, cipher and
plaintext. The version byte is `3`, and a 32-byte key commitment follows the
salt. No header field describes the commitment; version 3 fixes its length.

```text
master     = Argon2id(password, salt, 64 MiB, t = 3, p = 4, 32 bytes)
prk        = HKDF-Extract-SHA512(salt = NKPR salt, IKM = master)
cipher key = HKDF-Expand-SHA512(prk, info = "NekoKEM NKPR v3 AES-256-GCM", 32 bytes)
commitment = HKDF-Expand-SHA512(prk, info = "NekoKEM NKPR v3 key commitment", 32 bytes)

file = header (version 3) || salt || commitment || nonce || ciphertext || tag
AAD  = header || salt || commitment || nonce
```

- `master` is the same Argon2id call as NKPR v1 and v2, so the existing
  derivation code is reused unchanged. The labels are ASCII without NUL and
  the HKDF salt is the container's 32-byte salt, as NKEM v4 and v5 use their
  container salt.
- The two HKDF labels separate the cipher key from the commitment. A longer
  Argon2id output would add nothing to that separation.
- The plaintext is always the four PEM private keys of an identity key, in
  the NKPR v2 order. A two-component NKPR v1 key becomes NKPR v3 only by
  being upgraded to an identity key, which the existing upgrade already does
  with the same password. The reader therefore takes the component count
  from the version: 2 for v1, 4 for v2 and v3.
- The container is 32 bytes longer than NKPR v2 for the same key. Every size
  limit on NKPR files grows by those 32 bytes.

### Reading

1. Validate the header and the total length for the version.
2. Run Argon2id, then derive the cipher key and the commitment.
3. Compare the derived commitment with the stored one in constant time
   (`CRYPTO_memcmp`). On a mismatch, stop before AES-GCM and report exactly
   the wrong-password message. The comparison is constant time because the
   derived value depends on the user's password and an attacker-written file
   could otherwise measure how much of it matches.
4. Decrypt and verify AES-256-GCM as in NKPR v2. A tag failure after a
   matching commitment means the ciphertext was modified, and it reports the
   same message.

### What it protects, and what it does not

- An NKPR v3 file matches one Argon2id output, and therefore, up to Argon2id
  and HKDF-SHA512 collisions, one password. Making one file open under two
  passwords needs two passwords whose 32-byte commitments collide, about
  2^128 Argon2id evaluations. A crafted file tests one candidate per
  attempt, the same as online guessing.
- The commitment works as a password verifier for anyone holding the file,
  but so does the GCM tag of NKPR v1 and v2. Offline guessing still costs one
  64 MiB Argon2id evaluation per candidate, so v3 does not make it cheaper.
- Unlocking costs one Argon2id evaluation, as before, plus two HKDF calls.
- It protects nothing while a reader still accepts NKPR v1 or v2 from the
  same file source: the attacker writes the file and chooses its version
  byte, so they simply write a v2 file that opens under `k` passwords. The
  version byte being in the AAD only stops relabelling a genuine file. The
  next section is therefore part of the design, not an option.

### Legacy versions and downgrade

Core reads NKPR v3 everywhere. It reads NKPR v1 and v2 only where the caller
says the file is the installation's own managed key:

- **Own managed key**: Android's key directory, the desktop apps' configured
  own key, and the CLI's default `keys/private.key.enc`. These keep reading
  v1 and v2, and after the first successful unlock with a writer release,
  the key is rewritten as NKPR v3 in place, with the same password, under
  the existing pair lock and atomic replace. A v1 key is upgraded to an
  identity key at the same time, which already keeps its encryption keys and
  password; the user is told once. The CLI never changes key files as a side
  effect of encrypting or decrypting, which scripts would not expect: it
  prints a hint instead, and `upgrade-key` performs the rewrite.
- **Every other source**: private-key import on Android and the desktop,
  Android temporary private keys, other key paths in the desktop apps and
  any other key path in the CLI. These refuse NKPR v1 and v2 with a dedicated
  error that names the older format, instead of asking for the password.
  Front ends offer one explicit action, "Import a key I exported from an
  older NekoKEM", which states that only the user's own exported key should
  be opened this way; on success it writes the key back as NKPR v3. The CLI
  accepts a legacy file at another path only with `--legacy-private-key`.

The explicit action leaves a narrow social-engineering path, in which the
attacker persuades the user that their file is the user's own old key. It
is the price of reading keys exported before NKPR v3, and it can be removed
in a later release once those keys are rare.

### Rollout

1. **Reader release**: Core and every front end read NKPR v3. Keys are still
   written as NKPR v2 and no legacy rule changes, since no NKPR v3 file
   exists yet. A key written later by the writer release can then be opened
   on this release.
2. **Writer release**, at least one release later: new keys and upgrades are
   written as NKPR v3, own managed keys are rewritten after unlocking, and
   the legacy rules above apply. A private-key export offers "Export for
   NekoKEM 5.x and earlier" (NKPR v2), as legacy public keys are exported
   today, for moving the key to an older installation.

### Compatibility

- Releases before the reader release reject NKPR v3 at the header. The CLI
  and the desktop apps report "Private-key password is incorrect or NKPR
  data is corrupted" after asking for the password. The Android app checks
  the key structure first, so it shows its own key as missing, and its
  import and password check refuse the file.
- After the writer release, keys exported by the reader release or earlier
  (NKPR v1 and v2) are opened through the explicit legacy action.
- NKEM containers do not change, and every key keeps its public key and
  fingerprints.

### Core interface

The own-key decision is an explicit input on every call that unlocks a
private key, and the default refuses NKPR v1 and v2:

- `NEKOKEM_VERIFY_LEGACY_PRIVATE_KEY` in the decryption policy,
  `NEKOKEM_ENCRYPT_LEGACY_SENDER_KEY` for the signing key, and
  `NEKOKEM_PRIVATE_KEY_LEGACY` for `nekokem_check_private_key_password_flags()`.
  A refusal returns `NEKOKEM_ERROR_LEGACY_PRIVATE_KEY`. The calls without
  flags, `nekokem_decrypt_file*()` and `nekokem_check_private_key_password()`,
  refuse older formats.
- The loader checks the version of the same bytes it then decrypts, before
  Argon2id runs, so a refusal never uses the password.
- `nekokem_private_key_is_legacy()` reads only the header, so a front end can
  explain the rule before asking for a password.
- `nekokem_update_private_key_format()` rewrites the own key pair and
  `nekokem_export_private_key_legacy()` writes the NKPR v2 copy; both treat
  the key as the installation's own.

### Implementation notes

- `NKPR_MAX_CONTAINER_SIZE` grows by 32 bytes. `MANAGED_NKPR_MAX_SIZE` in
  `key_management.c` repeats that sum and must use the same constant;
  otherwise `nekokem_private_key_exists()` would reject a large valid v3 key.
- `protected_private_key_container_parse()` and the length check in the
  reader depend on the version. `nkpr_components()` maps v3 to four
  components.
- The legacy rule needs an explicit Core input, such as a flag on the
  private-key loading calls that the JNI and bridge layers pass through, so
  that front ends never compare version bytes themselves.

### Tests

- Known-answer vectors for `master`, `prk`, the cipher key and the
  commitment, and one complete NKPR v3 container.
- A commitment with one flipped bit: the wrong-password message and no
  AES-GCM call.
- A crafted NKPR v2 file that opens under two passwords: still opened at an
  own-key path (documented legacy behaviour), refused with the legacy error
  at every other path, and refused when relabelled as version 3.
- v1, v2 and v3 cross-reading on every platform, the in-place rewrite of an
  own key (including from v1, with the identity upgrade) and its rollback on
  failure, and the NKPR v2 export.
- NKPR fuzz seeds and the parser harness for version 3.

### Work

About 4–6 days across Core, CLI, Android JNI and UI, the desktop bridge and
both desktop apps, the five language catalogues and documentation, split
across the two releases. The legacy rules and the in-place rewrite are most
of it; the format itself is small.

## Recommendation

1. Do not introduce an NKEM v6 for key commitment; v4 and v5 already have
   it through the header MAC, and v3 is not exposed.
2. Use the precise wording above in the next release notes.
3. Ship the NKPR v3 reader in the next release that changes private keys,
   and the writer with the legacy rules at least one release later. Until
   then, the existing guidance stands: enter your password only into your
   own key files.

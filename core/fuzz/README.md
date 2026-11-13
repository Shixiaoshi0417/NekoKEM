# NekoKEM fuzzing

The harnesses accept exactly one input path, as expected when AFL++ uses
`@@`:

```sh
make fuzz-build
afl-fuzz -i fuzz/seeds/nkem -o fuzz/out-nkem -- \
  fuzz/bin/fuzz_nkem @@
afl-fuzz -i fuzz/seeds/nkpr -o fuzz/out-nkpr -- \
  fuzz/bin/fuzz_nkpr @@
afl-fuzz -i fuzz/seeds/decrypt -o fuzz/out-decrypt -- \
  fuzz/bin/fuzz_decrypt @@
afl-fuzz -i fuzz/seeds/identity -o fuzz/out-identity -- \
  fuzz/bin/fuzz_identity @@
```

`fuzz_nkem` calls only the production NKEM v3, v4 and v5 header and
total-size parsers. `fuzz_nkpr` calls only the production NKPR header, parameter, and
total-size parser, which accepts NKPR v1 and v2. Neither performs key decapsulation, Argon2id, AES-GCM, or
plaintext output.

`fuzz_decrypt` runs the complete public decryption call on the input:
v3/v4/v5 header parsing, X448 and ML-KEM-1024 decapsulation, v4/v5 entry
unwrapping and header MAC, streaming AES-GCM authentication, the v5
signature block, identity decoding and Ed448 + ML-DSA-87 verification, and
the atomic output transaction, which commits only authenticated plaintext
and otherwise removes its temporary file. `make fuzz-build` runs
`fuzz_decrypt_seeds`, which generates a throwaway plaintext-PEM identity key
pair in `fuzz/bin/` and genuine containers for it in `fuzz/seeds/decrypt/`
(v3, v3 with no data, v4 with the key as the second of two recipients, v4
with no data and the key as the first of two, v4 with the key as the last
of the maximum 64 recipients, and v5 with one recipient, no data, two
recipients and 64 recipients, each signed by the same key). Mutations of
these seeds therefore reach trial decryption, MAC, payload authentication
and signature verification instead of stopping at the header parser.

`fuzz_identity` feeds untrusted public keys to the production hybrid
public-key PEM parser (two or four components) and, for inputs of exactly
4291 bytes, to the canonical NKID identity decoder used inside signature
blocks. Every accepted identity must encode back to the same bytes, so a
decoder that accepts a non-canonical encoding aborts. Its seeds in
`fuzz/seeds/identity/` (the identity public key, its NKID encoding and a
legacy two-component public key) are written by `fuzz_decrypt_seeds` too. The key is created for every build, protects nothing and
is never committed; the generated seeds are ignored by Git.

For the parser harnesses, inputs larger than 2 MiB are rejected before
allocation, and diagnostics are disabled through the quiet production entry
points so malformed inputs do not generate high-volume logs.

The parser seed files are synthetic structure-only containers. Their KEM bytes,
nonce, tags, and NKPR ciphertext bytes are all zero and are not
cryptographically valid. The v3 and v4 salts, the v4 recipient entries and
header MAC are synthetic zero data. The v4 parser seeds cover one, two and the
maximum 64 recipients, two recipients with one ciphertext byte, and two
recipients cut inside the second entry and inside the header MAC. The v5
parser seeds cover one recipient, two recipients with one ciphertext byte
and 64 recipients, each followed by a zero signature block, and one cut
inside the signature block; the NKPR seeds cover versions 1 and 2. Seeds
contain no password, private key, plaintext, or other sensitive material.
Run `sh core/fuzz/generate_seeds.sh` to regenerate them deterministically.

## Continuous fuzzing

CI fuzzes each harness for 15 seconds on every pull request. The
[Fuzz workflow](../../.github/workflows/fuzz.yml) runs every harness for an
hour each Sunday at 18:00 UTC, in four parallel jobs, under UBSan. It can
also be started by hand from the Actions tab with 60 to 18000 seconds per
harness, and a pull request that changes the workflow runs it for one minute
per harness. A crash, hang, sanitizer report or incomplete run fails the job; the
AFL output, including any samples, is kept as the `fuzz-<target>-results`
artifact for 30 days. Reproduce a sample locally with
`core/fuzz/bin/fuzz_<target> <sample>` after `make -C linux fuzz-build`.

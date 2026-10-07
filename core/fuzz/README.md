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
```

`fuzz_nkem` calls only the production NKEM v3 and v4 header and
total-size parsers. `fuzz_nkpr` calls only the production NKPR header, parameter, and
total-size parser. Neither performs key decapsulation, Argon2id, AES-GCM, or
plaintext output.

`fuzz_decrypt` runs the complete public decryption call on the input:
v3/v4 header parsing, X448 and ML-KEM-1024 decapsulation, v4 entry
unwrapping and header MAC, streaming AES-GCM authentication and the atomic
output transaction, which commits only authenticated plaintext and otherwise
removes its temporary file. `make fuzz-build` runs `fuzz_decrypt_seeds`, which
generates a throwaway plaintext-PEM key pair in `fuzz/bin/` and genuine
containers for it in `fuzz/seeds/decrypt/` (v3, v3 with no data, and v4 with
the key as the second of two recipients). Mutations of these seeds therefore
reach trial decryption, MAC and payload authentication instead of stopping at
the header parser. The key is created for every build, protects nothing and
is never committed; the generated seeds are ignored by Git.

For the parser harnesses, inputs larger than 2 MiB are rejected before
allocation, and diagnostics are disabled through the quiet production entry
points so malformed inputs do not generate high-volume logs.

The parser seed files are synthetic structure-only containers. Their KEM bytes,
nonce, tags, and NKPR ciphertext bytes are all zero and are not
cryptographically valid. The v3 and v4 salts, the v4 recipient entry and
header MAC are synthetic zero data. Seeds contain no
password, private key, plaintext, or other sensitive material.
Run `sh core/fuzz/generate_seeds.sh` to regenerate them deterministically.

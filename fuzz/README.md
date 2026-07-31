# NekoKEM parser fuzzing

The harnesses accept exactly one input path, as expected when AFL++ uses
`@@`:

```sh
make fuzz-build
afl-fuzz -i fuzz/seeds/nkem -o fuzz/out-nkem -- \
  fuzz/bin/fuzz_nkem @@
afl-fuzz -i fuzz/seeds/nkpr -o fuzz/out-nkpr -- \
  fuzz/bin/fuzz_nkpr @@
```

`fuzz_nkem` calls only the production NKEM v1/v2 header and total-size
parser. `fuzz_nkpr` calls only the production NKPR header, parameter, and
total-size parser. Neither harness performs key decapsulation, Argon2id,
AES-GCM, or plaintext output.

Inputs larger than 2 MiB are rejected before allocation. Parser diagnostics
are disabled through the quiet production entry points so malformed inputs do
not generate high-volume logs.

The seed files are synthetic structure-only containers. Their KEM bytes,
nonce, tags, and NKPR ciphertext bytes are all zero and are not
cryptographically valid. They contain no password, private key, plaintext, or
other sensitive material. Run `sh fuzz/generate_seeds.sh` to regenerate
them deterministically.

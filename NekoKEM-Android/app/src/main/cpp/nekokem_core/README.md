# Core integration marker

Android compiles the repository-level NekoKEM Core directly through
`../CMakeLists.txt`; cryptographic source is not copied into this placeholder.

- `include/` and `src/` remain reserved for a future packaged Core.
- `main.c` and `cli.c` are not part of the Android target.
- JNI calls the default NKEM v3 encrypt API and strict v1/v2/v3 decrypt
  dispatcher.
- NKPR generation and one-shot password validation use the shared Core.
- JNI contains argument conversion only; it does not parse NKEM or NKPR.

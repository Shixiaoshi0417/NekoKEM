# Windows filesystem boundary

This port shares the original crypto implementation. `aes.c`, `hybrid.c`, `kem.c`,
`private_key.c`, `nekokem_v3.c`, `file_v3.c`, `secure_mem.c` and `nekokem.c` are unchanged. The
Windows backend is selected at compile time; the POSIX backend and its test gates
remain in place. There is no format migration, password/KDF weakening, crypto
rewrite, new unlocked-key cache, or removal of assembly acceleration.

| Boundary | Windows implementation | Validation |
| --- | --- | --- |
| Ordinary input | Strict UTF-8 to Unicode path; local NTFS disk files; ancestors and leaf reject reparse points | Unicode/binary/empty input and junction/device/pipe/ADS regressions |
| Private input | Handle-based owner, DACL, single-link and size validation; no write/delete sharing while reading | Broad-ACL rejection, owner fault, hard-link rejection, password checks |
| Temporary output | Random 128-bit suffix, CREATE_NEW, protected owner-only DACL at creation, binary non-inheritable CRT descriptor | Failure/cancellation and output ACL checks |
| Destination | Pinned ancestors and trusted parent ownership/ACL; existing destination must be a private single-link regular file | Unsafe-parent/output and alias rejection; parent rename blocked |
| Authentication | Existing shared AES-GCM implementation; plaintext commits only after successful tag verification | Wrong password, modified tag/KEM, zero X448 peer, truncation, v1/v2 rejection |
| Commit failure | Flush temporary bytes, rename through retained file handle, flush again; backups retained through paired commit; reverse rollback on failure | Injected ENOSPC, prepare/post-rename flush failure, second-rename failure and short writes |
| Compatibility | Existing header/AAD/KDF/domain/fingerprint/constants | Shared parser/GCM/KDF tests; pre-port Linux ↔ Windows v3/NKPR exchanges |

Private DACL validation intentionally rejects all effective access grants to principals
other than the current user; OWNER RIGHTS refers only to the verified current owner.
This still rejects SYSTEM/Administrators entries inherited by normal
Explorer copies. Privileged Windows administrators remain outside this isolation
boundary, just as root is outside ordinary Unix mode isolation. Parent directories
may grant SYSTEM/Administrators modification rights; pinned ancestors also recognize
the exact built-in TrustedInstaller service SID controlled by SYSTEM; an untrusted SID cannot own or
modify an output parent. Ancestors are held without FILE_SHARE_DELETE to block path
replacement until commit/abort. Known default ancestor read/create permissions are
allowed when they cannot replace the already pinned path; the immediate output
parent has stricter write checks.

The backend uses file-handle FlushFileBuffers. It does not claim the same namespace
power-loss semantics as POSIX parent-directory fsync, and Windows power-loss testing
has not been performed. Two output renames are not cross-file crash atomic. Process
termination can leave private temporary plaintext; recovery journals and physical
secure erasure remain outside this change. These limitations must remain visible
when evaluating security parity. No independent professional audit is claimed.

Performance measurements report the host/architecture, fixed file size, sample count
and median throughput. They do not compare dissimilar runner hardware as though it
were the same machine. The data path still reads/writes the same 64 KiB blocks and
uses OpenSSL's accelerated AES/X448/ML-KEM implementations. Platform checks occur at
file-open/commit boundaries, not inside the streaming crypto loop.

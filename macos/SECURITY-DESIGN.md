# macOS 文件安全边界 / macOS filesystem boundary

macOS CLI 与 GUI 使用同一 C17 Core 和既有密码实现。Core 版本仍为 3.1，NKEM v3、NKPR v1、X448 + ML-KEM-1024、HKDF-SHA512、Argon2id 参数、AES-256-GCM、头部/AAD、指纹、nonce/tag 与 GCM 限额均不变。文件数据仍按 64 KiB 流式处理；没有加入解锁私钥缓存或弱化密码派生。

| 边界 | macOS 策略 | 原生 CI 检查范围 |
| --- | --- | --- |
| 私有输入 | 当前用户所有、`0600`、普通非空文件、一个硬链接，使用 `O_NOFOLLOW`/`O_CLOEXEC`；查询扩展 ACL 并拒绝所有 allow 条目 | 权限、所有者、符号链接/硬链接、扩展 ACL、错误密码及认证失败 |
| 私有目录与临时输出 | 私有目录 `0700`、暂存文件 `0600`，安全 ACL，输出暂存于目标同目录 | 不安全目录/ACL拒绝、暂存权限、取消与失败清理 |
| 目标名称 | 根据文件系统返回的实际文件身份检查路径别名；配对输出在首次提交后、第二次提交前再次检查 | APFS/HFS+ 大小写及 Unicode 等价名称别名拒绝，旧文件恢复、新文件无残留 |
| 普通文件持久化 | 先 `fsync`，再 `fcntl(F_FULLFSYNC)`；任一步失败或不支持均拒绝 | `F_FULLFSYNC` 故障注入、短写、ENOSPC、提交失败回滚 |
| 目录持久化 | 父目录 `fsync` 并传播失败；不向目录传入普通文件专用的 `F_FULLFSYNC` | 目录同步失败与回滚，目录不执行 full-flush 的回归检查 |
| 认证与兼容 | 沿用 Core 认证后提交、敏感缓冲区清零和协议参数 | Core parser/KDF/GCM 测试，以及历史 Linux ↔ macOS NKEM/NKPR 互操作 |

ACL 策略比只看 POSIX mode 更保守：空 ACL 或只含 deny 条目的 ACL 可接受，任何 allow 条目都拒绝，即使其主体是当前所有者。ACL 查询/解析失败同样拒绝。deny-only ACL 不额外授予访问权限，但操作仍可能因其中的拒绝规则失败。此检查与既有所有者、`0600`/`0700`、普通文件和硬链接约束同时生效；不自动删除 ACL 或放宽文件权限。

别名判断不自行实现大小写折叠或 Unicode 正规化。它使用 `lstat` 等取得的实际文件身份，因此遵循目标文件系统对名称的解析。公私钥是进程内可回滚的配对事务：第二次提交或目录同步失败会尝试恢复旧文件；两个 rename 不是跨文件崩溃原子事务。

本实现没有硬编码“只允许 APFS”或“只允许固定磁盘”。可用性取决于目标文件系统和设备能否兑现所需同步 API；不支持 `F_FULLFSYNC` 的路径会失败。同步 API 成功不是对物理控制器诚信、故障设备或瞬时断电绝对无损的保证，未进行独立断电测试。原 POSIX 路径层不固定所有祖先目录的文件描述符，不能宣称排除了同一 UID 恶意进程替换父目录。root/管理员和能读取进程内存的主体也不在常规权限隔离边界内。

认证成功前不提交解密目标；普通失败与取消会删除暂存文件并清理敏感内存。进程被强制终止仍可能留下仅当前用户可读的暂存明文。删除文件不保证物理擦除；崩溃恢复日志、跨文件崩溃原子性、内存交换策略和存储介质擦除均不是本次适配提供的保证。

原生 CI 在 `macos-15` arm64 runner 上运行 Core ASan/UBSan、文件故障注入、CLI/伪终端语言及密码回显、互操作和实际 GUI/包检查；Darwin ASan 不提供 Linux 的 LSan，原 Linux LSan 门槛保留。Release 只构建，但保留固定源码哈希、OpenSSL 运行时/线程配置、Mach-O arm64/PIE、系统动态库、deployment target、签名和包完整性检查。macOS 11.0 是构建 deployment target，不代表最低系统的实机测试已完成。

CLI 与 GUI 的 ad-hoc hardened-runtime 签名只用于结构验证，不是 Developer ID 发布者身份验证，也没有 Apple 公证。Gatekeeper 可阻止未公证的下载应用；此适配不提供关闭系统保护的步骤。Android 签名密钥不用于 macOS。没有独立专业安全审计，也不保证跨操作系统或 M 系列代际速度相同；本项目不应用于保护重要或敏感数据。

---

The macOS CLI and GUI use the same C17 Core and existing cryptographic implementation. Core remains 3.1. NKEM v3, NKPR v1, X448 + ML-KEM-1024, HKDF-SHA512, Argon2id parameters, AES-256-GCM, headers/AAD, fingerprints, nonce/tag and GCM limits are unchanged. File data still uses 64 KiB streaming blocks. No unlocked-key cache or weaker password derivation is introduced.

| Boundary | macOS policy | Native CI coverage |
| --- | --- | --- |
| Private input | Current-user ownership, mode `0600`, nonempty regular file, one hard link, `O_NOFOLLOW`/`O_CLOEXEC`; query extended ACL and reject every allow entry | Permissions, ownership, symlink/hard-link and ACL rejection, wrong passwords/authentication failures |
| Private directory and temporary output | Directory mode `0700`, temporary mode `0600`, safe ACLs; temporary output in the destination directory | Unsafe directory/ACL rejection, temporary permissions, cancellation/failure cleanup |
| Destination name | Compare actual file identities returned by the filesystem; paired outputs are checked again after first publication and before the second | APFS/HFS+ case/Unicode alias rejection, restoration of old files, no newly created residue |
| Regular-file durability | `fsync` followed by `fcntl(F_FULLFSYNC)`; reject either failure or unsupported operation | Injected full-flush failure, short writes, ENOSPC and commit rollback |
| Directory durability | Parent-directory `fsync`, propagating failures; no regular-file-only `F_FULLFSYNC` on directories | Directory-sync rollback and directory no-full-flush regression |
| Authentication and compatibility | Existing Core authenticated commit, sensitive-buffer clearing and protocol parameters | Core parser/KDF/GCM tests and historical Linux ↔ macOS NKEM/NKPR exchanges |

The ACL policy is more conservative than checking POSIX mode alone: empty and deny-only ACLs are accepted; every allow entry is rejected, even for the verified owner. ACL query/parse failures also reject the operation. Deny-only ACLs grant no extra access, but their restrictions can still make an operation fail. These checks supplement the existing owner, `0600`/`0700`, regular-file and hard-link requirements. ACLs are not automatically removed and permissions are not broadened.

Alias checks implement no custom case folding or Unicode normalization. They use actual identities from operations such as `lstat`, following the target filesystem's name resolution. Public/private keys form a process-local rollback-capable transaction: a second commit or directory-sync failure attempts to restore old files. Two renames are not a cross-file crash-atomic transaction.

The implementation does not hard-code APFS-only or fixed-disk-only access. Availability depends on the target filesystem/device honoring the required sync APIs; paths without `F_FULLFSYNC` support fail. Successful APIs cannot guarantee truthful physical controllers, faulty devices or absolute survival of sudden power loss, and independent power-loss testing has not been performed. The existing POSIX path layer does not pin every ancestor directory by descriptor, so it cannot claim to exclude malicious same-UID processes replacing parents. Root/administrators and parties able to read process memory remain outside normal permission isolation.

Decrypted destinations are not committed before authentication succeeds. Ordinary failures/cancellation delete temporary files and clear sensitive memory. Forced process termination can still leave current-user-only temporary plaintext. File deletion does not guarantee physical erasure. Crash-recovery journals, cross-file crash atomicity, swap policy and storage-media erasure are not guarantees provided by this port.

Native CI on `macos-15` arm64 runs Core ASan/UBSan, file fault injection, CLI/pseudoterminal language and password-echo checks, interoperability and actual GUI/package checks. Darwin ASan does not provide Linux's LSan; the existing Linux LSan gate remains. Release builds only, retaining pinned source hashes, OpenSSL runtime/threading, Mach-O arm64/PIE, system dylib, deployment-target, signature and package-integrity requirements. macOS 11.0 is a build deployment target, without claiming completed testing on that minimum system.

CLI/GUI ad-hoc hardened-runtime signatures validate structure rather than a Developer ID publisher identity, and Apple notarization is absent. Gatekeeper may block downloaded non-notarized applications; this port provides no steps to disable system protection. Android signing keys are not used for macOS. No independent professional security audit or identical speed across operating systems/M-series generations is claimed. This project must not protect important or sensitive data.

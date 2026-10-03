# Windows x64 CLI / Windows x64 命令行

v3.3.1 provides a native Windows 10/11 x64 EXE; no MSYS2 runtime or OpenSSL DLL
is required to run it. Download `NekoKEM-windows-x86_64.zip` from the
[v3.3.1 Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.1) and verify
the archive against its top-level `SHA256SUMS.txt`. The companion
[Windows GUI](../desktop/README.md) has a separate portable package. See the
[bilingual release notes](../release/v3.3.1.md).

CLI/GUI EXE icons keep the square Android artwork with the outer white area made
transparent; Android retains its existing square white background. Rounded README
artwork is a separate display asset.
Build in MSYS2 UCRT64 with GCC, Perl, Make, curl and Python:

```sh
bash windows/scripts/build-windows-cli.sh
```

Run in Windows Terminal with PowerShell or cmd, in a directory owned by your user:

```powershell
.\nekokem.exe --lang zh-CN keygen
.\nekokem.exe encrypt hybrid .\input.bin .\output.nkem .\keys\public.key
.\nekokem.exe decrypt hybrid .\output.nkem .\restored.bin .\keys\private.key.enc
.\nekokem.exe --set-lang system
```

Passwords are read from the console with echo disabled, or UTF-8 stdin for automation;
never put a private-key password on the command line. Five languages share the Linux
catalogs and the exact same argument parser and five-option menu. Double-clicking
`nekokem.exe`, or running it without arguments, opens that interactive menu directly.
Windows automatically uses the user's display language (rather than regional date/number
format), with unsupported languages falling back to English. Language precedence is
`--lang` > saved `--set-lang` preference > `LC_ALL` > `LC_MESSAGES` > `LANG` > Windows
display language. `--lang system` bypasses the saved preference; `--set-lang system`
restores automatic selection. Preferences live in OS LocalAppData/NekoKEM.
UTF-8 command-line paths are converted through the Unicode Windows APIs.

Security policy: local fixed NTFS drives only. UNC/network paths, FAT/exFAT, device names, named
pipes, alternate data streams and reparse points (including ancestor junctions)
are rejected. All file paths require trusted ancestor ownership and ACLs; inputs
whose parent can be modified by an untrusted principal are also rejected. New outputs and key directories get a protected owner-only DACL at
creation, with no inherited read access. Private inputs require current-user
ownership, an owner-only DACL, a single hard link and a regular file. Existing output
files must meet that same policy before replacement. Unsafe permissions cause a
failure; the CLI never silently repairs them or truncates an existing destination.

Imported NKPR files copied by Explorer or downloaded may inherit broad access.
Place them in the CLI-created `keys` directory and apply a current-user-only ACL
before use. Verify the owner and ACL; do not grant Everyone/Users read access.
Back up the encrypted NKPR and password before changing permissions or moving keys.
For an encrypted NKPR file you own, PowerShell can replace its ACL explicitly:

```powershell
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [System.Security.AccessControl.FileSecurity]::new()
$acl.SetOwner($sid)
$acl.SetAccessRuleProtection($true, $false)
$rule = [System.Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath '.\keys\private.key.enc' -AclObject $acl
Get-Acl -LiteralPath '.\keys\private.key.enc' | Format-List Owner, Access
```

NKEM v3, NKPR v1, all algorithms, KDF domains/parameters, header/AAD, fingerprint,
nonce/tag and GCM limits remain unchanged. OpenSSL 4.0.3 retains assembly acceleration;
file data still uses the same 64 KiB streaming implementation. The Windows backend
pins ancestors, uses private same-directory temporary files, flushes data before
handle-based rename, and rolls back paired outputs on ordinary commit failures.
Two renames are not a cross-file crash-atomic transaction. Windows does not expose
POSIX directory fsync through this implementation: sudden power-loss durability,
physical-device behavior and process-death cleanup remain unverified. Temporary
plaintext is access-controlled; deletion does not guarantee physical erasure.

Native CI tests the actual EXE, protocol/KDF/GCM regressions, ACLs, hard links,
reparse points, unsafe destinations, fault-injected flush/rename rollback and
cancellation, and exchanges binary/empty NKEM files and NKPR keys with pre-port
Linux main. Generated interoperability keys/passwords are public test data only.
Benchmark results are comparative measurements, not an identical-speed guarantee
across operating systems. This experimental project has no independent professional
security audit and must not be used to protect important or sensitive data.
The EXE is not Authenticode signed; Android signing keys are not used.

---

v3.3.1 提供原生 Windows 10/11 x64 CLI，运行无需 MSYS2 或 OpenSSL DLL。公开包为
`NekoKEM-windows-x86_64.zip`，下载后用 Release 顶层 `SHA256SUMS.txt` 校验。
[Windows GUI](../desktop/README.md) 使用独立便携包，完整更新见
[v3.3.1 中英双语发布说明](../release/v3.3.1.md)。Windows EXE 图标保留方形图案，
将外部白色区域改为透明；Android 方形白底图案不变，README 圆角图仅用于展示。
Windows 应用未进行 Authenticode 签名，不使用 Android 签名材料。使用现有
五语言目录；密码从关闭回显的终端或 UTF-8 标准输入读取，不放入命令行参数。
首版只接受本地固定 NTFS 磁盘，拒绝网络/设备路径、重解析点及替代数据流。私钥、暂存明文
和输出在创建时设置仅当前用户可访问的 ACL；所有文件路径都检查父目录的可信
所有者与 ACL，输入目录若允许其他非可信账户修改，也会拒绝；私钥还检查所有者与硬链接数。
不安全的已有输出会被拒绝，不自动放宽权限或先截断目标。

协议及密码参数不变；保留 OpenSSL 汇编加速和 64 KiB 流式处理。Windows 提交
机制的断电持久性、跨文件崩溃原子性及进程死亡清理仍需验证，不能等同于 POSIX
目录 fsync。本项目是未经独立专业安全审计的实验性工具，不应用于保护重要或敏感数据，
也不承诺跨系统速度完全相同。

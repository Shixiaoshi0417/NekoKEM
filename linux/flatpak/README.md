# Flatpak

`io.github.shixiaoshi0417.NekoKEM` packages the Linux desktop GUI and the
`nekokem` CLI as a Flatpak on the Freedesktop 26.08 runtime, for x86_64 and
aarch64. Like the [Arch Linux package](../arch/PKGBUILD) it compiles nothing:
it unpacks the published static CLI archive and the GUI folder archive (with its
own Java runtime) of one release.

| File | Purpose |
|---|---|
| `io.github.shixiaoshi0417.NekoKEM.json` | The manifest: sandbox permissions and the release archives with their SHA-256 |
| `io.github.shixiaoshi0417.NekoKEM.desktop` | The application menu entry |
| `io.github.shixiaoshi0417.NekoKEM.metainfo.xml` | AppStream data for software centres and Flathub |
| `screenshots/` | The screenshots the AppStream data links to |
| `build-flatpak.sh` | Builds a `.flatpak` bundle from local archives (CI and Release) |

## 安装 / Install

5.2.0 之后的发布每种架构附带 `NekoKEM-linux-<架构>-GUI.flatpak`。
Releases after 5.2.0 carry `NekoKEM-linux-<arch>-GUI.flatpak`:

```sh
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user ./NekoKEM-linux-x86_64-GUI.flatpak
flatpak run io.github.shixiaoshi0417.NekoKEM
flatpak run --command=nekokem io.github.shixiaoshi0417.NekoKEM --version
```

自行从清单构建（需要 `flatpak-builder`）/ Build from the manifest yourself:

```sh
flatpak-builder --user --install --install-deps-from=flathub --force-clean \
    build linux/flatpak/io.github.shixiaoshi0417.NekoKEM.json
```

## 沙盒 / Sandbox

- `--filesystem=home`, `/media`, `/run/media`: the files to encrypt, decrypt and
  sign, and key files, can be anywhere in the home folder or on removable media.
  The GUI's file dialogs are GTK's, inside the sandbox.
- `--share=network`: only the update check (one HTTPS request to GitHub through
  the runtime's `curl`). The release page opens through the desktop portal.
- `--socket=x11`, `--share=ipc`, `--device=dri`: the Java/Compose window draws
  through X11 (XWayland on a Wayland desktop) with OpenGL when available.

Settings, contacts and the language are in
`~/.var/app/io.github.shixiaoshi0417.NekoKEM/config/nekokem`, apart from
`~/.config/nekokem` of the DEB, RPM and Arch packages. Copy that folder over to
keep existing contacts. Chinese, Japanese and Korean text uses the fonts
installed on the system, which Flatpak makes visible to the sandbox.

## 维护 / Maintenance

After each release, point the manifest at it:

```sh
python3 scripts/update_flatpak_manifest.py 5.3.0
```

This reads the release's `SHA256SUMS.txt`, rewrites the four archive URLs and
checksums, and adds the release to the AppStream metadata. CI runs
`update_flatpak_manifest.py --check`, then builds the bundle from its own
archives on both architectures, installs it, and opens the GUI in English and
Chinese.

## 上架 Flathub / Publishing on Flathub

Flathub publishes only what its own maintainer submits; nothing in this
repository does it. Once the screenshots are on `main`:

1. Fork [flathub/flathub](https://github.com/flathub/flathub) and branch from
   its `new-pr` branch.
2. Copy the manifest, the desktop entry and the metainfo file into the
   branch's root, and open a pull request against `new-pr` titled
   `Add io.github.shixiaoshi0417.NekoKEM`.
3. Flathub's reviewers and build bot check it; afterwards Flathub creates the
   `flathub/io.github.shixiaoshi0417.NekoKEM` repository, which the maintainer
   then updates. The `x-checker-data` entries let Flathub's update bot open a
   pull request there for each new GitHub release.
4. Verify the `io.github.shixiaoshi0417` app ID on Flathub with the same GitHub
   account to get the verified badge.

`flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest
linux/flatpak/io.github.shixiaoshi0417.NekoKEM.json` reports no findings for
the manifest.

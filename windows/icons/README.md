Windows CLI and Tauri GUI use the same square NekoKEM artwork with an alpha-transparent exterior. The blank outer white background is removed; white areas within the character, keys and locks remain opaque. No rounded crop is applied to the application icons.

`windows/icons/icon.png` and `desktop/public/icon.png` are byte-identical 192-pixel Windows variants. Windows PNG SHA-256: `ec17e99e4cb3017bde3efe3805c1f13c550378f74a321e597099648680bdae60`. The ICO includes 16, 32, 48, 64, 128 and 256 pixel images with transparent corners. CLI and GUI builds verify that their EXEs embed these exact ICO payloads.

Android retains its existing square white-background launchers. The Android 192-pixel source and repository-root `icon.png` remain unchanged: SHA-256 `c4425f8ee453571228e8b0260667533ef1ec35e0ce812f61d3b1eb2caea09d76`. README uses the separate `docs/icon-rounded.png` display asset, with a rounded presentation mask; it does not replace either platform's launcher icons.

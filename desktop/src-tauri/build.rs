use std::{env, fs, path::PathBuf};
fn main() {
    let target = env::var("TARGET").unwrap();
    let windows = target == "x86_64-pc-windows-gnu";
    let macos = target == "aarch64-apple-darwin";
    let linux = matches!(target.as_str(), "x86_64-unknown-linux-gnu" | "aarch64-unknown-linux-gnu");
    assert!(windows || macos || linux, "Unsupported desktop target: {target}");
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../..");
    let prefix = PathBuf::from(env::var("NEKOKEM_OPENSSL_PREFIX").expect("Build the platform's pinned static OpenSSL prefix first"));
    // A dependency upgrade must rebuild the C ABI and relink the GUI even when
    // the prefix path and workspace source files stay the same.
    for path in ["include/openssl/opensslv.h", "lib/libcrypto.a"] {
        println!("cargo:rerun-if-changed={}", prefix.join(path).display());
    }
    let manifest = if windows { "nekokem-build-manifest.txt" } else { "nekokem-build-manifest.json" };
    println!("cargo:rerun-if-changed={}", prefix.join(manifest).display());
    if macos || linux {
        println!("cargo:rerun-if-changed={}", prefix.join("LICENSE.txt").display());
        // Generate the bundled license before Tauri packages the app. The
        // source is the same verified prefix used to link static libcrypto.
        let resources = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("build-resources");
        fs::create_dir_all(&resources).expect("Cannot create desktop license resources");
        fs::copy(prefix.join("LICENSE.txt"), resources.join("openssl-LICENSE.txt"))
            .expect("Pinned OpenSSL prefix must include its LICENSE.txt");
    }
    let mut build = cc::Build::new();
    build.include(root.join("core/include")).include(root.join("core/src"))
        .include(root.join("linux/src")).include(prefix.join("include"))
        .flag("-std=c17").flag("-O2").flag("-Wall").flag("-Wextra")
        .flag("-Wpedantic").flag("-Wconversion").flag("-Wshadow").flag("-Wformat=2")
        .flag("-Wstrict-prototypes").flag("-Werror").flag("-fstack-protector-strong")
        .define("_FORTIFY_SOURCE", "3")
        .define("NEKOKEM_DESKTOP_UTF8_UI", "1");
    if windows {
        build.define("_WIN32_WINNT", "0x0A00").define("__USE_MINGW_ANSI_STDIO", "1");
    } else if macos {
        build.flag("-mmacosx-version-min=11.0").define("_DARWIN_C_SOURCE", "1");
    } else {
        build.define("_POSIX_C_SOURCE", "200809L")
            .flag("-fPIC").flag("-fvisibility=hidden")
            .flag("-ffunction-sections").flag("-fdata-sections");
    }
    for name in ["nekokem", "key_management", "nekokem_v3", "kem", "hybrid", "aes", "file", "file_v3", "secure_mem", "private_key"] {
        let path = root.join(format!("core/src/{name}.c"));
        println!("cargo:rerun-if-changed={}", path.display()); build.file(path);
    }
    build.file(root.join("linux/src/i18n.c")).file(root.join("desktop/native/bridge.c"));
    build.compile("nekokem_core");
    println!("cargo:rerun-if-env-changed=NEKOKEM_OPENSSL_PREFIX");
    for path in ["core/src/file_windows.inc", "desktop/native/bridge.c", "linux/src/i18n.c", "linux/src/i18n.h"] {
        println!("cargo:rerun-if-changed={}", root.join(path).display());
    }
    println!("cargo:rustc-link-search=native={}", prefix.join("lib").display());
    println!("cargo:rustc-link-lib=static=crypto");
    println!("cargo:rustc-link-arg=-fstack-protector-strong");
    if windows {
        println!("cargo:rustc-link-arg=-static");
        println!("cargo:rustc-link-arg=-Wl,--dynamicbase,--nxcompat,--high-entropy-va,--no-insert-timestamp");
        for lib in ["crypt32", "bcrypt", "advapi32", "shell32", "ole32", "uuid", "ws2_32"] { println!("cargo:rustc-link-lib={lib}"); }
    } else if macos {
        // libcrypto is static; macOS system frameworks/libSystem remain dynamic.
        println!("cargo:rustc-link-arg=-mmacosx-version-min=11.0");
        println!("cargo:rustc-link-lib=framework=CoreFoundation");
        println!("cargo:rerun-if-env-changed=MACOSX_DEPLOYMENT_TARGET");
    } else {
        println!("cargo:rustc-link-arg=-Wl,-z,relro,-z,now,-z,noexecstack,--gc-sections");
        // WebKitGTK can load the system's OpenSSL for its own TLS stack. Keep
        // Core's pinned static OpenSSL out of the dynamic symbol namespace.
        println!("cargo:rustc-link-arg=-Wl,--exclude-libs,libcrypto.a:libnekokem_core.a");
        println!("cargo:rustc-link-lib=dl");
        println!("cargo:rustc-link-lib=pthread");
    }
    tauri_build::build();
}

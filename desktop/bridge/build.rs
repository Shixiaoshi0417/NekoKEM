// Compiles the same C17 Core and desktop adapter as desktop/src-tauri/build.rs,
// with the same flags, for the Compose desktop app (Windows and Linux).
// The library is loaded through Java's foreign function API.
use std::{env, path::PathBuf};
fn main() {
    let target = env::var("TARGET").unwrap();
    let windows = target == "x86_64-pc-windows-gnu";
    let linux = matches!(target.as_str(), "x86_64-unknown-linux-gnu" | "aarch64-unknown-linux-gnu");
    assert!(windows || linux, "Unsupported desktop bridge target: {target}");
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../..");
    let prefix = PathBuf::from(env::var("NEKOKEM_OPENSSL_PREFIX").expect("Build the platform's pinned static OpenSSL prefix first"));
    for path in ["include/openssl/opensslv.h", "lib/libcrypto.a"] {
        println!("cargo:rerun-if-changed={}", prefix.join(path).display());
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
    } else {
        build.define("_POSIX_C_SOURCE", "200809L")
            .flag("-fPIC").flag("-fvisibility=hidden")
            .flag("-ffunction-sections").flag("-fdata-sections");
    }
    for name in ["nekokem", "key_management", "nekokem_v3", "nekokem_v4", "nekokem_v5", "identity", "kem", "hybrid", "aes", "payload_hash", "file", "file_v3", "file_v4", "secure_mem", "private_key"] {
        build.file(root.join(format!("core/src/{name}.c")));
    }
    build.file(root.join("linux/src/i18n.c")).file(root.join("desktop/native/bridge.c"));
    build.compile("nekokem_core");
    println!("cargo:rerun-if-env-changed=NEKOKEM_OPENSSL_PREFIX");
    for path in ["core/include", "core/src", "linux/src", "desktop/native", "desktop/src-tauri/src"] {
        println!("cargo:rerun-if-changed={}", root.join(path).display());
    }
    println!("cargo:rustc-link-search=native={}", prefix.join("lib").display());
    println!("cargo:rustc-link-lib=static=crypto");
    println!("cargo:rustc-link-arg=-fstack-protector-strong");
    if windows {
        // One self-contained DLL: no MinGW runtime DLLs beside it.
        println!("cargo:rustc-link-arg=-static");
        println!("cargo:rustc-link-arg=-Wl,--dynamicbase,--nxcompat,--high-entropy-va,--no-insert-timestamp");
        for lib in ["crypt32", "bcrypt", "advapi32", "shell32", "ole32", "uuid", "ws2_32"] { println!("cargo:rustc-link-lib={lib}"); }
    } else {
        println!("cargo:rustc-link-arg=-Wl,-z,relro,-z,now,-z,noexecstack,--gc-sections");
        println!("cargo:rustc-link-arg=-Wl,--exclude-libs,libcrypto.a:libnekokem_core.a");
        println!("cargo:rustc-link-lib=dl");
        println!("cargo:rustc-link-lib=pthread");
    }
}

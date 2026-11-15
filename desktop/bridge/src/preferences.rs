// The desktop choices: interface style, language and similar settings. They
// live in a private file beside update.json, written by Core's file layer.
// Unsigned output is not among them: every launch starts by signing again.
use serde::{Deserialize, Serialize};
use std::ffi::{c_char, c_int, c_void, CString};

const PREFERENCES_FILE: &str = "desktop.json";
const MAX_PREFERENCES_BYTES: usize = 64 * 1024;
const MAX_PATH_BYTES: usize = 32767;
// The two looks the Android app offers: MIUIX (default) and Material 3 Expressive.
const STYLES: [&str; 2] = ["miuix", "material"];
extern "C" {
    fn desktop_write_private(path: *const c_char, bytes: *const u8, length: usize) -> c_int;
    fn file_read_sensitive(
        path: *const c_char,
        maximum: usize,
        buffer: *mut *mut u8,
        length: *mut usize,
    ) -> c_int;
    fn secure_free(memory: *mut c_void, length: usize);
}

/// A missing or damaged file means the defaults: accept unsigned files with a
/// notice, no saved signing key, sidebar open, MIUIX.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default, deny_unknown_fields)]
// The user's own key pair: the public key is offered first as a recipient, the
// private key signs and decrypts by default.
// `record_name`: signed files keep the input's file name (NekoKEM 5.1 cannot
// open those), on unless the user turned it off.
// `decrypt_folder`: decrypted files go there without a save dialog; empty asks every time.
pub struct Preferences {
    pub require_signature: bool,
    pub sign_key_path: String,
    pub public_key_path: String,
    pub sidebar_open: bool,
    pub style: String,
    pub record_name: bool,
    pub decrypt_folder: String,
}
impl Default for Preferences {
    fn default() -> Self {
        Self {
            require_signature: false,
            sign_key_path: String::new(),
            public_key_path: String::new(),
            sidebar_open: true,
            style: "miuix".into(),
            record_name: true,
            decrypt_folder: String::new(),
        }
    }
}
impl Preferences {
    fn valid(&self) -> bool {
        STYLES.contains(&self.style.as_str())
            && [
                &self.sign_key_path,
                &self.public_key_path,
                &self.decrypt_folder,
            ]
            .iter()
            .all(|path| path.len() <= MAX_PATH_BYTES && !path.contains('\0'))
    }
}

pub fn load(directory: &str) -> Preferences {
    let file = format!("{directory}/{PREFERENCES_FILE}");
    if std::fs::symlink_metadata(&file).is_err() {
        return Preferences::default();
    }
    let Ok(path) = CString::new(file) else {
        return Preferences::default();
    };
    let mut buffer = std::ptr::null_mut();
    let mut length = 0;
    if unsafe {
        file_read_sensitive(
            path.as_ptr(),
            MAX_PREFERENCES_BYTES,
            &mut buffer,
            &mut length,
        )
    } != 1
    {
        return Preferences::default();
    }
    let parsed = serde_json::from_slice::<Preferences>(unsafe {
        std::slice::from_raw_parts(buffer, length)
    });
    unsafe { secure_free(buffer.cast(), length) };
    parsed.ok().filter(Preferences::valid).unwrap_or_default()
}

pub fn store(directory: &str, preferences: &Preferences) -> bool {
    if !preferences.valid() {
        return false;
    }
    let (Ok(bytes), Ok(path)) = (
        serde_json::to_vec(preferences),
        CString::new(format!("{directory}/{PREFERENCES_FILE}")),
    ) else {
        return false;
    };
    bytes.len() <= MAX_PREFERENCES_BYTES
        && unsafe { desktop_write_private(path.as_ptr(), bytes.as_ptr(), bytes.len()) } == 1
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn choices_survive_in_a_private_file_and_damage_means_defaults() {
        let _core = crate::contacts::tests::core_lock();
        let directory = crate::contacts::tests::Directory::new();
        let path = directory.0.to_str().unwrap();
        assert_eq!(load(path), Preferences::default());
        let chosen = Preferences {
            require_signature: true,
            sign_key_path: "C:\\Keys\\me.private.key.enc".into(),
            public_key_path: "C:\\Keys\\me.public.key".into(),
            sidebar_open: false,
            style: "material".into(),
            record_name: false,
            decrypt_folder: "D:\\Decrypted".into(),
        };
        assert!(store(path, &chosen));
        assert_eq!(load(path), chosen);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(directory.file(PREFERENCES_FILE))
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o600
            );
        }
        assert!(!store(
            path,
            &Preferences {
                sign_key_path: "a\0b".into(),
                ..Preferences::default()
            }
        ));
        assert!(!store(
            path,
            &Preferences {
                style: "glass".into(),
                ..Preferences::default()
            }
        ));
        assert!(!store(
            path,
            &Preferences {
                decrypt_folder: "a\0b".into(),
                ..Preferences::default()
            }
        ));
        std::fs::write(directory.file(PREFERENCES_FILE), br#"{"unsigned":true}"#).unwrap();
        assert_eq!(load(path), Preferences::default());
        // A 5.1 file has no recordName: names are recorded.
        std::fs::write(
            directory.file(PREFERENCES_FILE),
            br#"{"requireSignature":true}"#,
        )
        .unwrap();
        assert_eq!(
            load(path),
            Preferences {
                require_signature: true,
                ..Preferences::default()
            }
        );
    }
}

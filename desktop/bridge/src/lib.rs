//! The Compose desktop app's native layer: the Tauri desktop backend (Core
//! calls, contact store, update check) unchanged, behind a small C ABI that
//! Kotlin calls through Java's foreign function API.
//!
//! Every call is one UTF-8 JSON request and one JSON reply, both owned by the
//! caller's buffers until this layer returns. Secrets (passwords, pasted keys)
//! are parsed straight into `Zeroizing` fields: requests are externally tagged
//! so serde never buffers them in an intermediate copy.
#![allow(clippy::missing_safety_doc)]
#[allow(dead_code)]
#[path = "../../src-tauri/src/core.rs"]
mod core;
#[allow(dead_code)]
#[path = "../../src-tauri/src/contacts.rs"]
mod contacts;
#[allow(dead_code)]
#[path = "../../src-tauri/src/update.rs"]
mod update;
mod preferences;

use crate::contacts::{Source, Store};
use crate::core::{Backend, Failure, Request, Reservation, CORE_LOCK};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::ffi::{c_char, c_int, CStr, CString};
use std::sync::{atomic::Ordering, Arc, OnceLock};
use zeroize::Zeroizing;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

fn backend() -> &'static Arc<Backend> {
    static BACKEND: OnceLock<Arc<Backend>> = OnceLock::new();
    BACKEND.get_or_init(|| Arc::new(Backend::default()))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ContactImport {
    #[serde(default)] key_path: String,
    #[serde(default)] key_text: Zeroizing<String>,
    #[serde(default)] paste: bool,
    #[serde(default)] note: String,
    // The full fingerprint the user checked before upgrading a legacy contact.
    #[serde(default)] confirm_upgrade: String,
}

/// The same commands as the Tauri backend (desktop/src-tauri/src/main.rs).
#[derive(Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
enum Call {
    GetSettings,
    SetLanguage { language: String },
    RunOperation { request: Request },
    ListContacts,
    SaveContact { contact: ContactImport },
    UpdateContactNote { id: String, note: String },
    DeleteContact { id: String },
    CheckUpdate,
    OpenReleasePage { version: String },
    UpdatePreferences,
    SaveUpdatePreferences { preferences: update::Preferences },
    DesktopPreferences,
    SaveDesktopPreferences { preferences: preferences::Preferences },
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Settings { language: String, selection: String, version: &'static str }

/// Progress for the running operation: bytes processed and the total.
pub type ProgressCallback = extern "C" fn(processed: u64, total: u64);

fn idle() -> Result<(), Failure> {
    let active = backend().active.lock().map_err(|_| Failure::new("internal"))?;
    if active.is_some() { Err(Failure::new("busy")) } else { Ok(()) }
}

// Contact changes hold the operation slot and Core, as in the Tauri backend.
fn contact_job<T>(work: impl FnOnce(&Store) -> Result<T, Failure>) -> Result<T, Failure> {
    let backend = backend().clone();
    let job = backend.reserve_internal()?;
    let _hold = Reservation { backend, job };
    let _core = CORE_LOCK.lock().map_err(|_| Failure::new("internal"))?;
    work(&Store::open()?)
}

fn locked<T>(work: impl FnOnce() -> Result<T, Failure>) -> Result<T, Failure> {
    let _core = CORE_LOCK.lock().map_err(|_| Failure::new("internal"))?;
    work()
}

fn json<T: Serialize>(value: Result<T, Failure>) -> Result<Value, Failure> {
    value.and_then(|value| serde_json::to_value(value).map_err(|_| Failure::new("internal")))
}

fn run(call: Call, progress: Option<ProgressCallback>) -> Result<Value, Failure> {
    match call {
        Call::GetSettings => json((|| {
            idle()?;
            locked(|| Ok(Settings { language: core::language(), selection: core::preference(), version: VERSION }))
        })()),
        Call::SetLanguage { language } => json(idle().and_then(|_| locked(|| core::save_language(&language)))),
        Call::RunOperation { request } => {
            let backend = backend().clone();
            let job = backend.reserve(&request)?;
            let hold = Reservation { backend: backend.clone(), job: job.clone() };
            let result = core::execute(&backend, request, job, move |processed, total| {
                if let Some(callback) = progress { callback(processed, total); }
            });
            drop(hold);
            json(result)
        }
        Call::ListContacts => json(locked(|| Store::open()?.list())),
        Call::SaveContact { contact } => json(contact_job(move |store| {
            let source = if contact.paste { Source::Text(&contact.key_text) } else { Source::Path(&contact.key_path) };
            store.save_confirmed(source, &contact.note, &contact.confirm_upgrade)
        })),
        Call::UpdateContactNote { id, note } => json(contact_job(move |store| store.update_note(&id, &note))),
        Call::DeleteContact { id } => json(contact_job(move |store| store.delete(&id))),
        // Needs no Core lock and no job: it never touches keys or files.
        Call::CheckUpdate => json(Ok(update::check(VERSION))),
        Call::OpenReleasePage { version } => {
            if update::open_page(&version) { Ok(Value::Null) } else { Err(Failure::new("open-release")) }
        }
        Call::UpdatePreferences => json(locked(|| Ok(update::config_directory().map(|directory| update::load(&directory)).unwrap_or_default()))),
        Call::SaveUpdatePreferences { preferences } => locked(|| {
            let directory = update::config_directory().ok_or_else(|| Failure::new("settings-save"))?;
            if update::store(&directory, &preferences) { Ok(Value::Null) } else { Err(Failure::new("settings-save")) }
        }),
        Call::DesktopPreferences => json(locked(|| Ok(update::config_directory().map(|directory| preferences::load(&directory)).unwrap_or_default()))),
        Call::SaveDesktopPreferences { preferences } => locked(|| {
            let directory = update::config_directory().ok_or_else(|| Failure::new("settings-save"))?;
            if preferences::store(&directory, &preferences) { Ok(Value::Null) } else { Err(Failure::new("settings-save")) }
        }),
    }
}

/// One request, one reply: `{"ok":value}` or `{"error":{"code":…}}`.
pub fn dispatch(request: &[u8], progress: Option<ProgressCallback>) -> String {
    let reply = match serde_json::from_slice::<Call>(request) {
        Ok(call) => run(call, progress),
        Err(_) => Err(Failure::new("invalid-request")),
    };
    let body = match reply {
        Ok(value) => serde_json::json!({ "ok": value }),
        Err(failure) => serde_json::json!({ "error": failure }),
    };
    body.to_string()
}

/// Runs one request. It blocks until Core finishes, so call it off the UI
/// thread; `progress` is called on the calling thread. The caller keeps and
/// clears `request`. Free the reply with `nekokem_desktop_free`.
#[no_mangle]
pub unsafe extern "C" fn nekokem_desktop_call(request: *const u8, length: usize, progress: Option<ProgressCallback>) -> *mut c_char {
    let bytes = if request.is_null() || length == 0 { &[][..] } else { std::slice::from_raw_parts(request, length) };
    CString::new(dispatch(bytes, progress)).map_or(std::ptr::null_mut(), CString::into_raw)
}

#[no_mangle]
pub unsafe extern "C" fn nekokem_desktop_free(reply: *mut c_char) {
    if !reply.is_null() { drop(CString::from_raw(reply)); }
}

/// Asks the running encryption or decryption with this id to stop.
#[no_mangle]
pub unsafe extern "C" fn nekokem_desktop_cancel(id: *const c_char) -> c_int {
    if id.is_null() { return 0; }
    CStr::from_ptr(id).to_str().is_ok_and(|id| backend().cancel(id)) as c_int
}

/// 1 while the window must stay open for Core to finish cleaning up; the
/// running operation is told to stop. 0 when the window may close now.
#[no_mangle]
pub extern "C" fn nekokem_desktop_defer_close() -> c_int { backend().defer_close() as c_int }

/// 1 once a deferred close may go ahead: the operation has finished.
#[no_mangle]
pub extern "C" fn nekokem_desktop_close_ready() -> c_int {
    let backend = backend();
    (backend.close_after.load(Ordering::Acquire) && backend.active.lock().is_ok_and(|active| active.is_none())) as c_int
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contacts::tests as contact;
    use std::sync::atomic::AtomicU64;

    // Operations share the backend's single slot; tests that use it take turns.
    static SLOT: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn slot() -> std::sync::MutexGuard<'static, ()> { SLOT.lock().unwrap_or_else(|error| error.into_inner()) }

    fn reply(request: &str) -> Value { serde_json::from_str(&dispatch(request.as_bytes(), None)).unwrap() }

    #[test]
    fn malformed_and_unknown_requests_are_refused() {
        for request in ["", "{", "\"nothing\"", r#"{"get_settings":{"extra":1}}"#, r#"{"run_operation":{"request":{"id":"a","kind":"keygen","stray":1}}}"#] {
            assert_eq!(reply(request)["error"]["code"], "invalid-request", "{request}");
        }
    }

    #[test]
    fn settings_report_the_bridge_version() {
        let _slot = slot();
        let settings = reply(r#""get_settings""#);
        assert_eq!(settings["ok"]["version"], VERSION);
        assert!(settings["ok"]["language"].is_string());
    }

    static SEEN: AtomicU64 = AtomicU64::new(0);
    extern "C" fn seen(processed: u64, _total: u64) { SEEN.fetch_max(processed, Ordering::Relaxed); }

    #[test]
    fn operations_run_through_core_with_progress() {
        let _slot = slot();
        let directory = contact::Directory::new();
        let (public, private, fingerprint) = { let _core = contact::core_lock(); contact::keypair(&directory, "alice", "secret") };
        let input = directory.file("plain.txt");
        std::fs::write(&input, vec![7u8; 200_000]).unwrap();
        let path = |value: &str| serde_json::to_string(value).unwrap();
        let shown = reply(&format!(r#"{{"run_operation":{{"request":{{"id":"fp-1","kind":"fingerprint","keyPath":{}}}}}}}"#, path(&public)));
        assert_eq!(shown["ok"]["fingerprint"], fingerprint.as_str());
        let encrypted = directory.file("plain.txt.nkem");
        let request = format!(r#"{{"run_operation":{{"request":{{"id":"enc-1","kind":"encrypt","input":{},"output":{},"keyPath":{},"signKeyPath":{},"password":"secret"}}}}}}"#,
            path(&input), path(&encrypted), path(&public), path(&private));
        let done: Value = serde_json::from_str(&dispatch(request.as_bytes(), Some(seen))).unwrap();
        assert_eq!(done["ok"]["format"], 5, "{done}");
        assert!(SEEN.load(Ordering::Relaxed) > 0);
        let again = reply(&request);
        assert_eq!(again["error"]["code"], "output-exists");
    }

    #[test]
    fn cancel_and_close_need_a_running_operation() {
        let id = CString::new("nothing-running").unwrap();
        assert_eq!(unsafe { nekokem_desktop_cancel(id.as_ptr()) }, 0);
        assert_eq!(unsafe { nekokem_desktop_cancel(std::ptr::null()) }, 0);
    }
}

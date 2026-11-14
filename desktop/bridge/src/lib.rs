//! The desktop apps' native layer: the backend (Core calls, contact store,
//! update check) behind a small C ABI. The Compose app (Windows, Linux) calls
//! it through Java's foreign function API; the SwiftUI app (macOS) links it.
//!
//! Every call is one UTF-8 JSON request and one JSON reply, both owned by the
//! caller's buffers until this layer returns. Secrets (passwords, pasted keys)
//! are parsed straight into `Zeroizing` fields: requests are externally tagged
//! so serde never buffers them in an intermediate copy.
#![allow(clippy::missing_safety_doc)]
mod core;
mod contacts;
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

/// The commands the desktop apps send.
#[derive(Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
enum Call {
    GetSettings,
    SetLanguage { language: String },
    RunOperation { request: Request },
    SaveOutput { destination: String },
    DiscardOutput,
    ListContacts,
    SaveContact { contact: ContactImport },
    UpdateContactNote { id: String, note: String },
    DeleteContact { id: String },
    SetContactPinned { id: String, pinned: bool },
    SetContactVerified { id: String, fingerprint: String, verified: bool },
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

// Contact changes hold the operation slot and Core, so nothing else runs meanwhile.
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
        Call::SaveOutput { destination } => idle().and_then(|_| locked(|| backend().save_output(&destination))).map(|_| Value::Null),
        Call::DiscardOutput => idle().and_then(|_| locked(|| if backend().discard_output() { Ok(Value::Null) } else { Err(Failure::new("cleanup-error")) })),
        Call::ListContacts => json(locked(|| Store::open()?.list())),
        Call::SaveContact { contact } => json(contact_job(move |store| {
            let source = if contact.paste { Source::Text(&contact.key_text) } else { Source::Path(&contact.key_path) };
            store.save_confirmed(source, &contact.note, &contact.confirm_upgrade)
        })),
        Call::UpdateContactNote { id, note } => json(contact_job(move |store| store.update_note(&id, &note))),
        Call::DeleteContact { id } => json(contact_job(move |store| store.delete(&id))),
        Call::SetContactPinned { id, pinned } => json(contact_job(move |store| store.set_pinned(&id, pinned))),
        Call::SetContactVerified { id, fingerprint, verified } => json(contact_job(move |store| store.set_verified(&id, &fingerprint, verified))),
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
    fn staged_output_is_saved_where_the_user_chooses_afterwards() {
        let _slot = slot();
        let directory = contact::Directory::new();
        let (public, private, _) = { let _core = contact::core_lock(); contact::keypair(&directory, "alice", "secret") };
        let input = directory.file("plain.txt");
        std::fs::write(&input, vec![9u8; 100_000]).unwrap();
        let path = |value: &str| serde_json::to_string(value).unwrap();
        let encrypt = format!(r#"{{"run_operation":{{"request":{{"id":"enc-2","kind":"encrypt","input":{},"keyPath":{},"signKeyPath":{},"password":"secret","stage":true}}}}}}"#,
            path(&input), path(&public), path(&private));
        // Nothing is staged yet, and a staged run names no output.
        assert_eq!(reply(&format!(r#"{{"save_output":{{"destination":{}}}}}"#, path(&directory.file("early.nkem"))))["error"]["code"], "invalid-request");
        assert_eq!(reply(&encrypt.replace(r#""stage":true"#, r#""stage":true,"output":"x""#))["error"]["code"], "invalid-request");
        let done = reply(&encrypt);
        assert_eq!(done["ok"]["format"], 5, "{done}");
        assert!(done["ok"]["output"].is_null());
        // A place that cannot be written keeps the output for another try.
        let missing = directory.file("no-such-folder/plain.txt.nkem");
        assert_eq!(reply(&format!(r#"{{"save_output":{{"destination":{}}}}}"#, path(&missing)))["error"]["code"], "save-failed");
        // The save dialog asked before replacing, so an existing file is replaced.
        let encrypted = directory.file("plain.txt.nkem");
        std::fs::write(&encrypted, b"old").unwrap();
        assert_eq!(reply(&format!(r#"{{"save_output":{{"destination":{}}}}}"#, path(&encrypted))), serde_json::json!({ "ok": null }));
        assert!(std::fs::metadata(&encrypted).unwrap().len() > 100_000);
        assert_eq!(reply(&format!(r#"{{"save_output":{{"destination":{}}}}}"#, path(&encrypted)))["error"]["code"], "invalid-request");

        let decrypt = format!(r#"{{"run_operation":{{"request":{{"id":"dec-2","kind":"decrypt","input":{},"keyPath":{},"password":"secret","stage":true}}}}}}"#,
            path(&encrypted), path(&private));
        let opened = reply(&decrypt);
        assert_eq!(opened["ok"]["signature"]["status"], "untrusted", "{opened}");
        // Giving up deletes the plaintext; nothing is left to save.
        assert_eq!(reply(r#""discard_output""#), serde_json::json!({ "ok": null }));
        let plain = directory.file("restored.txt");
        assert_eq!(reply(&format!(r#"{{"save_output":{{"destination":{}}}}}"#, path(&plain)))["error"]["code"], "invalid-request");
        assert!(!std::path::Path::new(&plain).exists());
        assert_eq!(reply(&decrypt.replace("dec-2", "dec-3"))["ok"]["format"], 5);
        assert_eq!(reply(&format!(r#"{{"save_output":{{"destination":{}}}}}"#, path(&plain))), serde_json::json!({ "ok": null }));
        assert_eq!(std::fs::read(&plain).unwrap(), vec![9u8; 100_000]);
    }

    #[test]
    fn cancel_and_close_need_a_running_operation() {
        let id = CString::new("nothing-running").unwrap();
        assert_eq!(unsafe { nekokem_desktop_cancel(id.as_ptr()) }, 0);
        assert_eq!(unsafe { nekokem_desktop_cancel(std::ptr::null()) }, 0);
    }
}

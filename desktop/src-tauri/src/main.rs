#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod contacts;
mod core;
use crate::contacts::{Contact,Contacts,Source,Store};
use crate::core::{Backend,Failure,Request,Reservation,Outcome,CORE_LOCK};
use serde::{Deserialize,Serialize};
use std::sync::{atomic::Ordering,Arc};
use tauri::{Emitter,Manager,State};
use zeroize::Zeroizing;
#[derive(Serialize)] struct Settings {language:String,selection:String,version:&'static str}
#[derive(Clone,Serialize)]#[serde(rename_all="camelCase")]struct ProgressEvent{id:String,processed:u64,total:u64}
#[cfg(any(target_os="macos",target_os="linux"))]
fn native_startup_evidence(stage:&str){
    // Opt-in native CI diagnostics contain only fixed startup markers, never
    // paths, language preferences, requests, passwords or key material.
    if std::env::var("NEKOKEM_NATIVE_STARTUP_EVIDENCE").as_deref()==Ok("1"){
        eprintln!("NekoKEM native startup: {stage}");
    }
}
#[tauri::command]
fn get_settings(state:State<'_,Arc<Backend>>)->Result<Settings,Failure>{
    let active=state.active.lock().map_err(|_|Failure::new("internal"))?;
    if active.is_some(){return Err(Failure::new("busy"));}
    let _core=CORE_LOCK.lock().map_err(|_|Failure::new("internal"))?;
    let language=core::language();
    #[cfg(any(target_os="macos",target_os="linux"))]
    native_startup_evidence("settings-ready");
    Ok(Settings{language,selection:core::preference(),version:env!("CARGO_PKG_VERSION")})
}
#[tauri::command]
fn set_language(language:String,state:State<'_,Arc<Backend>>)->Result<String,Failure>{
    let active=state.active.lock().map_err(|_|Failure::new("internal"))?;
    if active.is_some(){return Err(Failure::new("busy"));}
    let _core=CORE_LOCK.lock().map_err(|_|Failure::new("internal"))?;
    core::save_language(&language)
}
#[tauri::command]
fn cancel_operation(id:String,state:State<'_,Arc<Backend>>)->bool{state.cancel(&id)}
#[tauri::command]
fn close_app(state:State<'_,Arc<Backend>>,app:tauri::AppHandle)->Result<(),Failure>{
    let active=state.active.lock().map_err(|_|Failure::new("internal"))?;
    if active.is_some(){return Err(Failure::new("busy"));} app.exit(0);Ok(())
}
#[tauri::command]
async fn run_operation(request:Request,state:State<'_,Arc<Backend>>,app:tauri::AppHandle)->Result<Outcome,Failure>{
    let backend=state.inner().clone();let job=backend.reserve(&request)?;
    let hold=Reservation{backend,job:job.clone()};
    tauri::async_runtime::spawn_blocking(move||{
        let id=job.id.clone();let emitter=app.clone();let backend=hold.backend.clone();
        let result=core::execute(request,job,move|processed,total|{let _=emitter.emit("operation-progress",ProgressEvent{id:id.clone(),processed,total});});
        drop(hold);
        if backend.close_after.load(Ordering::Acquire){app.exit(0);}
        result
    }).await.map_err(|_|Failure::new("internal"))?
}
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct ContactImport{
    #[serde(default)] key_path:String,
    #[serde(default)] key_text:Zeroizing<String>,
    #[serde(default)] paste:bool,
    #[serde(default)] note:String,
}
// Contact changes run off the UI thread, exclusively with Core operations, and
// delay window/app shutdown until their atomic record update has finished.
async fn contact_job<T:Send+'static>(state:State<'_,Arc<Backend>>,app:tauri::AppHandle,work:impl FnOnce(&Store)->Result<T,Failure>+Send+'static)->Result<T,Failure>{
    let backend=state.inner().clone();let job=backend.reserve_internal()?;
    let hold=Reservation{backend,job};
    tauri::async_runtime::spawn_blocking(move||{
        let result=CORE_LOCK.lock().map_err(|_|Failure::new("internal")).and_then(|_core|work(&Store::open()?));
        let backend=hold.backend.clone();drop(hold);
        if backend.close_after.load(Ordering::Acquire){app.exit(0);}
        result
    }).await.map_err(|_|Failure::new("internal"))?
}
// Listing is read-only: it waits for Core instead of reserving the operation slot.
#[tauri::command]
async fn list_contacts()->Result<Contacts,Failure>{
    tauri::async_runtime::spawn_blocking(||{
        let _core=CORE_LOCK.lock().map_err(|_|Failure::new("internal"))?;
        Store::open()?.list()
    }).await.map_err(|_|Failure::new("internal"))?
}
#[tauri::command]
async fn save_contact(contact:ContactImport,state:State<'_,Arc<Backend>>,app:tauri::AppHandle)->Result<Contact,Failure>{
    contact_job(state,app,move|store|{
        let source=if contact.paste{Source::Text(&contact.key_text)}else{Source::Path(&contact.key_path)};
        store.save(source,&contact.note)
    }).await
}
#[tauri::command]
async fn update_contact_note(id:String,note:String,state:State<'_,Arc<Backend>>,app:tauri::AppHandle)->Result<Contact,Failure>{
    contact_job(state,app,move|store|store.update_note(&id,&note)).await
}
#[tauri::command]
async fn delete_contact(id:String,state:State<'_,Arc<Backend>>,app:tauri::AppHandle)->Result<(),Failure>{
    contact_job(state,app,move|store|store.delete(&id)).await
}
fn main(){
    tauri::Builder::default().plugin(tauri_plugin_dialog::init()).manage(Arc::new(Backend::default()))
        .setup(|app|{
            let config=app.config().app.windows.iter().find(|window|window.label=="main").expect("Main window config missing");
            let builder=tauri::WebviewWindowBuilder::from_config(app,config)?
                .on_navigation(|url|{
                    (url.scheme()=="tauri"&&url.host_str()==Some("localhost")) ||
                    (matches!(url.scheme(),"http"|"https")&&url.host_str()==Some("tauri.localhost")) ||
                    (cfg!(debug_assertions)&&url.scheme()=="http"&&url.host_str()==Some("127.0.0.1")&&url.port()==Some(1420))
                })
                .on_new_window(|_,_|tauri::webview::NewWindowResponse::Deny);
            #[cfg(any(target_os="macos",target_os="linux"))]
            let builder=builder.on_page_load(|_,payload|{
                if payload.event()==tauri::webview::PageLoadEvent::Finished{
                    native_startup_evidence("page-loaded");
                }
            });
            builder.build()?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![get_settings,set_language,run_operation,cancel_operation,close_app,list_contacts,save_contact,update_contact_note,delete_contact])
        .on_window_event(|window,event|{if let tauri::WindowEvent::CloseRequested{api,..}=event{
            let state=window.state::<Arc<Backend>>();
            if state.defer_close(){api.prevent_close();}
        }})
        .build(tauri::generate_context!()).expect("Cannot start NekoKEM desktop")
        .run(|_app,_event|{
            #[cfg(any(target_os="macos",target_os="linux"))]
            if let tauri::RunEvent::ExitRequested{api,..}=_event{
                // Application-level exits also need Core cleanup before exit
                // (including macOS application-menu Quit and Cmd+Q).
                if _app.state::<Arc<Backend>>().defer_close(){api.prevent_exit();}
            }
        });
}

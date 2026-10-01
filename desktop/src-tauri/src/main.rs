#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod core;
use core::{Backend,Failure,Request,Reservation,Outcome,CORE_LOCK};
use serde::Serialize;
use std::sync::Arc;
use tauri::{Emitter,Manager,State};
#[derive(Serialize)] struct Settings {language:String,selection:String,version:&'static str}
#[derive(Clone,Serialize)]#[serde(rename_all="camelCase")]struct ProgressEvent{id:String,processed:u64,total:u64}
#[tauri::command]
fn get_settings(state:State<'_,Arc<Backend>>)->Result<Settings,Failure>{
    let active=state.active.lock().map_err(|_|Failure::new("internal"))?;
    if active.is_some(){return Err(Failure::new("busy"));}
    let _core=CORE_LOCK.lock().map_err(|_|Failure::new("internal"))?;
    let language=core::language();
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
        if backend.close_after.load(std::sync::atomic::Ordering::Acquire){app.exit(0);}
        result
    }).await.map_err(|_|Failure::new("internal"))?
}
fn main(){
    tauri::Builder::default().plugin(tauri_plugin_dialog::init()).manage(Arc::new(Backend::default()))
        .setup(|app|{
            let config=app.config().app.windows.iter().find(|window|window.label=="main").expect("Main window config missing");
            tauri::WebviewWindowBuilder::from_config(app,config)?
                .on_navigation(|url|{
                    (url.scheme()=="tauri"&&url.host_str()==Some("localhost")) ||
                    (matches!(url.scheme(),"http"|"https")&&url.host_str()==Some("tauri.localhost")) ||
                    (cfg!(debug_assertions)&&url.scheme()=="http"&&url.host_str()==Some("127.0.0.1")&&url.port()==Some(1420))
                })
                .on_new_window(|_,_|tauri::webview::NewWindowResponse::Deny)
                .build()?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![get_settings,set_language,run_operation,cancel_operation,close_app])
        .on_window_event(|window,event|{if let tauri::WindowEvent::CloseRequested{api,..}=event{
            let state=window.state::<Arc<Backend>>();
            if let Ok(active)=state.active.lock(){if let Some(job)=active.as_ref(){state.close_after.store(true,std::sync::atomic::Ordering::Release);job.cancelled.store(true,std::sync::atomic::Ordering::Release);api.prevent_close();}};
        }})
        .run(tauri::generate_context!()).expect("Cannot start NekoKEM desktop");
}

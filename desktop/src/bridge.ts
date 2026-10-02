import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { open, save } from '@tauri-apps/plugin-dialog';
export type Operation='keygen'|'encrypt'|'decrypt'|'fingerprint';
export interface Request {id:string;kind:Operation;input:string;output:string;keyPath:string;publicPath:string;privatePath:string;password:string;confirmation:string;keyText:string;paste:boolean}
export interface Outcome {output:string|null;fingerprint:string|null}
export interface Progress {id:string;processed:number;total:number}
export const getSettings=()=>invoke<{language:string;selection:string;version:string}>('get_settings');
export const setLanguage=(language:string)=>invoke<string>('set_language',{language});
export const runOperation=(request:Request)=>invoke<Outcome>('run_operation',{request});
export const cancelOperation=(id:string)=>invoke<boolean>('cancel_operation',{id});
export const closeApp=()=>invoke<void>('close_app');
export const onProgress=(callback:(value:Progress)=>void)=>listen<Progress>('operation-progress',event=>callback(event.payload));
export async function chooseOpen():Promise<string|null>{const value=await open({multiple:false,directory:false});return typeof value==='string'?value:null;}
export const chooseSave=(defaultPath:string)=>save({defaultPath});

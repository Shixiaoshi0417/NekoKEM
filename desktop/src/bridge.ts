import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { open, save } from '@tauri-apps/plugin-dialog';
export type Operation='keygen'|'encrypt'|'decrypt'|'fingerprint';
export interface Request {id:string;kind:Operation;input:string;output:string;keyPath:string;publicPath:string;privatePath:string;password:string;confirmation:string;keyText:string;paste:boolean;contacts:string[]}
export interface Outcome {output:string|null;fingerprint:string|null;recipients?:string[]}
// Same limit as Core's NEKOKEM_MAX_RECIPIENTS for one NKEM v4 file.
export const MAX_RECIPIENTS=64;
export interface Progress {id:string;processed:number;total:number}
export interface Contact {id:string;fingerprint:string;name:string;note:string}
export interface ContactList {contacts:Contact[];unreadable:number}
export interface ContactImport {keyPath:string;keyText:string;paste:boolean;note:string}
export const getSettings=()=>invoke<{language:string;selection:string;version:string}>('get_settings');
export const setLanguage=(language:string)=>invoke<string>('set_language',{language});
export const runOperation=(request:Request)=>invoke<Outcome>('run_operation',{request});
export const cancelOperation=(id:string)=>invoke<boolean>('cancel_operation',{id});
export const closeApp=()=>invoke<void>('close_app');
export const listContacts=()=>invoke<ContactList>('list_contacts');
export const saveContact=(contact:ContactImport)=>invoke<Contact>('save_contact',{contact});
export const updateContactNote=(id:string,note:string)=>invoke<Contact>('update_contact_note',{id,note});
export const deleteContact=(id:string)=>invoke<void>('delete_contact',{id});
export const onProgress=(callback:(value:Progress)=>void)=>listen<Progress>('operation-progress',event=>callback(event.payload));
export async function chooseOpen():Promise<string|null>{const value=await open({multiple:false,directory:false});return typeof value==='string'?value:null;}
export const chooseSave=(defaultPath:string)=>save({defaultPath});

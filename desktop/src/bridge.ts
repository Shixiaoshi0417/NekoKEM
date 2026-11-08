import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { open, save } from '@tauri-apps/plugin-dialog';
export type Operation='keygen'|'encrypt'|'decrypt'|'fingerprint'|'upgrade'|'export-legacy';
export interface Request {id:string;kind:Operation;input:string;output:string;keyPath:string;publicPath:string;privatePath:string;password:string;confirmation:string;keyText:string;paste:boolean;contacts:string[];replace:boolean;overwrite:boolean;signKeyPath:string;unsigned:boolean;requireSignature:boolean}
// Core decides the status and matches the signer against saved identity contacts.
export interface Signature {status:'trusted'|'untrusted'|'unsigned';fingerprint?:string;contact?:string;signerKey?:string}
export interface Outcome {output:string|null;fingerprint:string|null;recipients?:string[];format?:number;signature?:Signature;legacyFingerprint?:string;canVerify?:boolean}
// Same limit as Core's NEKOKEM_MAX_RECIPIENTS for one NKEM v4/v5 file.
export const MAX_RECIPIENTS=64;
export interface Progress {id:string;processed:number;total:number}
export interface Contact {id:string;fingerprint:string;name:string;note:string;canVerify:boolean;legacyFingerprint?:string}
export interface ContactList {contacts:Contact[];unreadable:number}
export interface ContactImport {keyPath:string;keyText:string;paste:boolean;note:string}
export const getSettings=()=>invoke<{language:string;selection:string;version:string}>('get_settings');
export const setLanguage=(language:string)=>invoke<string>('set_language',{language});
export const runOperation=(request:Request)=>invoke<Outcome>('run_operation',{request});
export const cancelOperation=(id:string)=>invoke<boolean>('cancel_operation',{id});
export const closeApp=()=>invoke<void>('close_app');
// The backend contacts only GitHub's fixed release address; it never downloads or installs.
export type UpdateStatus={status:'available';version:string}|{status:'current'}|{status:'failed'};
export const checkUpdate=()=>invoke<UpdateStatus>('check_update');
export const openReleasePage=(version:string)=>invoke<void>('open_release_page',{version});
// Kept by the backend: the incognito WebView forgets its own storage on exit.
export interface UpdatePreferences {auto:boolean;lastCheck:number;ignored:string}
export const updatePreferences=()=>invoke<UpdatePreferences>('update_preferences');
export const saveUpdatePreferences=(preferences:UpdatePreferences)=>invoke<void>('save_update_preferences',{preferences});
export const listContacts=()=>invoke<ContactList>('list_contacts');
export const saveContact=(contact:ContactImport)=>invoke<Contact>('save_contact',{contact});
export const updateContactNote=(id:string,note:string)=>invoke<Contact>('update_contact_note',{id,note});
export const deleteContact=(id:string)=>invoke<void>('delete_contact',{id});
export const onProgress=(callback:(value:Progress)=>void)=>listen<Progress>('operation-progress',event=>callback(event.payload));
export async function chooseOpen():Promise<string|null>{const value=await open({multiple:false,directory:false});return typeof value==='string'?value:null;}
export const chooseSave=(defaultPath:string)=>save({defaultPath});

import { expect, test, type Page } from '@playwright/test';
import { translate, type Language } from '../../src/i18n';

interface Harness {
 progress(processed:number,total:number,id?:string):void;
 complete():void;
 fail(code:string):void;
 resolveDialog(path:string|null):void;
}
async function harness(page:Page,action:'complete'|'fail'|'progress'|'resolveDialog',value?:string|number,total?:number,id?:string){
 await page.evaluate(({action,value,total,id})=>{
  const desktop=(window as unknown as {desktop:Harness}).desktop;
  if(action==='progress')desktop.progress(value as number,total!,id);
  else if(action==='fail')desktop.fail(value as string);
  else if(action==='resolveDialog')desktop.resolveDialog(value as string|null);
  else desktop.complete();
 },{action,value,total,id});
}
async function fillEncryption(page:Page){
 await page.locator('[name=keyPath]').fill('C:\\Local\\public.key');
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');
 await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await page.locator('.primary').click();
}
async function noHorizontalOverflow(page:Page){
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 const card=await page.locator('.card').boundingBox();
 const button=await page.locator('.primary').boundingBox();
 expect(card).not.toBeNull();expect(button).not.toBeNull();
 expect(button!.x+button!.width).toBeLessThanOrEqual(card!.x+card!.width);
}
test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{
  const callbacks:Record<number,(value:unknown)=>void>={};let next=1;
  let progressHandler=0,request:{id:string;output:string}|null=null;
  let finish:((value:unknown)=>void)|null=null,fail:((value:unknown)=>void)|null=null;
  let choose:((path:string|null)=>void)|null=null;
  Object.assign(window,{__TAURI_INTERNALS__:{
   transformCallback(callback:(value:unknown)=>void){const id=next++;callbacks[id]=callback;return id;},
   unregisterCallback(id:number){delete callbacks[id];},
   invoke(command:string,args:Record<string,unknown>){
    if(command==='get_settings')return Promise.resolve({language:'zh-CN',selection:'system',version:'3.3.0'});
    if(command==='set_language')return Promise.resolve(args.language==='system'?'zh-CN':args.language);
    if(command==='plugin:event|listen'){progressHandler=args.handler as number;return Promise.resolve(1);}
    if(command==='plugin:event|unlisten')return Promise.resolve(null);
    if(command==='run_operation'){
     const value=args.request as {id:string;output:string};request={id:value.id,output:value.output};
     return new Promise((resolve,reject)=>{finish=resolve;fail=reject;});
    }
    if(command==='cancel_operation')return Promise.resolve(true);
    if(command==='plugin:dialog|open'||command==='plugin:dialog|save')return new Promise(resolve=>{choose=resolve;});
    return Promise.reject({code:'internal'});
   },
  },__TAURI_EVENT_PLUGIN_INTERNALS__:{unregisterListener(){}},desktop:{
   progress(processed:number,total:number,id?:string){callbacks[progressHandler]?.({event:'operation-progress',id:1,payload:{id:id??request?.id,processed,total}});},
   complete(){finish?.({output:request?.output??null,fingerprint:null});},
   fail(code:string){fail?.({code});},
   resolveDialog(path:string|null){choose?.(path);},
  }});
 });
 await page.goto('/');await expect(page.getByRole('heading',{name:'加密文件',exact:true})).toBeVisible();
});

test('five languages across all four operation layouts',async({page})=>{
 for(const tag of ['zh-CN','zh-TW','en','ja','ko'] as Language[]){
  await page.locator('select').selectOption(tag);
  for(const [index,operation] of ['keygen','encrypt','decrypt','fingerprint'].entries()){
   await page.locator('.operation-tab').nth(index).click();
   await expect(page.getByRole('heading',{name:translate(operation as 'keygen'|'encrypt'|'decrypt'|'fingerprint',tag),exact:true})).toBeVisible();
   await noHorizontalOverflow(page);
  }
  await page.locator('.operation-tab').nth(1).click();
  await page.screenshot({path:`test-results/desktop-${tag}.png`,fullPage:true,animations:'disabled'});
 }
});

test('minimum native window fits all five languages and pasted-key layouts',async({page})=>{
 await page.setViewportSize({width:760,height:620});
 for(const tag of ['zh-CN','zh-TW','en','ja','ko'] as Language[]){
  await page.locator('select').selectOption(tag);
  for(let index=0;index<4;index++){
   await page.locator('.operation-tab').nth(index).click();await noHorizontalOverflow(page);
   if(index>0){await page.getByRole('radio').nth(1).check();await noHorizontalOverflow(page);}
  }
 }
 await page.locator('select').selectOption('zh-CN');await page.locator('.operation-tab').nth(2).click();
 await page.screenshot({path:'test-results/desktop-minimum-decrypt.png',fullPage:true,animations:'disabled'});
});

test('keyboard navigation and error focus remain visible',async({page})=>{
 await page.locator('select').selectOption('en');
 const first=page.locator('.operation-tab').first();await first.focus();
 await first.press('ArrowDown');await expect(page.locator('.operation-tab').nth(1)).toBeFocused();
 await page.keyboard.press('End');await expect(page.locator('.operation-tab').nth(3)).toBeFocused();
 await page.keyboard.press('Home');await expect(first).toBeFocused();
 await page.locator('.primary').focus();await page.keyboard.press('Enter');
 await expect(page.locator('[name=publicPath]')).toBeFocused();
 await expect(page.locator('[name=publicPath]')).toHaveAttribute('aria-invalid','true');
 await expect(page.getByRole('alert')).toContainText('required file paths');
 expect(await page.locator('[name=publicPath]').evaluate(element=>getComputedStyle(element).outlineWidth)).toBe('2px');
 await page.screenshot({path:'test-results/desktop-keyboard-error.png',fullPage:true,animations:'disabled'});
});

test('animated operation switches discard secret DOM before the next form appears',async({page})=>{
 await page.locator('.operation-tab').nth(2).click();await page.getByRole('radio').nth(1).check();
 await page.locator('[name=keyText]').fill('private-pem-test-fixture');
 await page.locator('[name=password]').fill('test-password');
 expect(await page.locator('[name=keyText]').evaluate(element=>getComputedStyle(element).getPropertyValue('-webkit-text-security'))).toBe('disc');
 await page.evaluate(()=>Object.assign(window,{removedKey:document.querySelector('[name=keyText]'),removedPassword:document.querySelector('[name=password]')}));
 await page.locator('.operation-tab').first().click();
 expect(await page.evaluate(()=>{
  const old=window as unknown as {removedKey:HTMLTextAreaElement;removedPassword:HTMLInputElement};
  return [old.removedKey.value,old.removedPassword.value,old.removedKey.isConnected,old.removedPassword.isConnected];
 })).toEqual(['','',false,false]);
 await expect(page.locator('.form-content')).toHaveCount(1);
 await expect(page.locator('[name=password]')).toHaveValue('');
});

test('real progress, safe cancellation and completion states',async({page})=>{
 await fillEncryption(page);
 await expect(page.getByRole('progressbar')).not.toHaveAttribute('aria-valuenow');
 await expect(page.locator('.status')).toContainText('正在处理');
 await harness(page,'progress',80,100,'old-job');
 await expect(page.getByRole('progressbar')).not.toHaveAttribute('aria-valuenow');
 await harness(page,'progress',48,100);
 await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow','48');
 await page.screenshot({path:'test-results/desktop-progress.png',fullPage:true,animations:'disabled'});
 await page.locator('.secondary').click();await expect(page.locator('.secondary')).toBeDisabled();
 await expect(page.locator('.status')).toContainText('正在安全取消');
 await expect(page.locator('.operation-tab').first()).toBeDisabled();
 await harness(page,'fail','cancelled');await expect(page.getByRole('alert')).toContainText('未提交新的输出');
 await expect(page.locator('.primary')).toBeEnabled();await expect(page.getByRole('progressbar')).toHaveCount(0);
 await fillEncryption(page);await harness(page,'complete');
 await expect(page.locator('.notice.success')).toContainText('操作完成');
 await expect(page.locator('.notice.success')).toContainText('report.txt.nkem');
 await expect(page.locator('.spinner')).toHaveCount(0);
 await page.screenshot({path:'test-results/desktop-completed.png',fullPage:true,animations:'disabled'});
});

test('reduced motion disables entry, progress and spinner animations',async({page})=>{
 await page.emulateMedia({reducedMotion:'reduce'});
 await page.locator('.operation-tab').first().click();
 expect(await page.locator('.page-title').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 expect(await page.locator('.form-content').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 await page.locator('.operation-tab').nth(1).click();await fillEncryption(page);
 expect(await page.locator('.spinner').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 expect(await page.locator('.progress-fill').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 expect(await page.locator('.progress-fill').evaluate(element=>getComputedStyle(element).transitionDuration)).toBe('0s');
 await harness(page,'progress',50,100);await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow','50');
 await harness(page,'complete');await expect(page.locator('.notice.success')).toBeVisible();
});

test('normal motion is enabled only during entry or active processing',async({page})=>{
 await page.emulateMedia({reducedMotion:'no-preference'});
 await page.locator('.operation-tab').first().click();
 expect(await page.locator('.page-title').evaluate(element=>getComputedStyle(element).animationName)).toBe('enter-page');
 await page.locator('.operation-tab').nth(1).click();await fillEncryption(page);
 expect(await page.locator('.spinner').evaluate(element=>getComputedStyle(element).animationName)).toBe('spin');
 expect(await page.locator('.progress-fill').evaluate(element=>getComputedStyle(element).animationName)).toBe('progress-scan');
 await harness(page,'complete');
 await expect.poll(()=>page.evaluate(()=>document.getAnimations().filter(animation=>animation.playState==='running').length)).toBe(0);
});

test('native dialog keeps controls locked until a selection returns',async({page})=>{
 await page.locator('.path-input button').first().click();
 await expect(page.locator('.status')).toContainText('正在选择文件');
 await expect(page.locator('.operation-tab').first()).toBeDisabled();await expect(page.locator('.primary')).toBeDisabled();
 await harness(page,'resolveDialog','C:\\Local\\public.key');
 await expect(page.locator('[name=keyPath]')).toHaveValue('C:\\Local\\public.key');
 await expect(page.locator('.operation-tab').first()).toBeEnabled();await expect(page.locator('.primary')).toBeEnabled();
});

test('Windows high contrast keeps navigation, focus and progress readable',async({page})=>{
 await page.emulateMedia({forcedColors:'active'});
 await page.locator('.operation-tab').nth(1).focus();
 expect(await page.locator('.operation-tab').nth(1).evaluate(element=>getComputedStyle(element).outlineStyle)).toBe('solid');
 await fillEncryption(page);await harness(page,'progress',50,100);
 await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow','50');
 expect(await page.locator('.progress-track').evaluate(element=>getComputedStyle(element).borderTopWidth)).toBe('1px');
 await page.screenshot({path:'test-results/desktop-high-contrast.png',fullPage:true,animations:'disabled'});
});

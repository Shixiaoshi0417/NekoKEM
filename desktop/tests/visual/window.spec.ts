import { expect,test } from '@playwright/test';
test('five language desktop layouts',async({page})=>{
 await page.addInitScript(()=>{
  const callbacks:Record<number,(value:unknown)=>void>={};let next=1;
  Object.assign(window,{__TAURI_INTERNALS__:{
   transformCallback(callback:(value:unknown)=>void){const id=next++;callbacks[id]=callback;return id;},
   unregisterCallback(id:number){delete callbacks[id];},
   invoke(command:string,args:Record<string,unknown>){
    if(command==='get_settings')return Promise.resolve({language:'zh-CN',selection:'system',version:'3.2.0'});
    if(command==='set_language')return Promise.resolve(args.language);
    if(command==='plugin:event|listen')return Promise.resolve(1);
    if(command==='plugin:event|unlisten')return Promise.resolve(null);
    return Promise.reject({code:'internal'});
   },
  },__TAURI_EVENT_PLUGIN_INTERNALS__:{unregisterListener(){}}});
 });
 await page.goto('/');await expect(page.getByRole('heading',{name:'加密文件',exact:true})).toBeVisible();
 for(const [tag,title] of [['zh-CN','加密文件'],['zh-TW','加密檔案'],['en','Encrypt file'],['ja','ファイルを暗号化'],['ko','파일 암호화']]){
  await page.locator('select').selectOption(tag!);await expect(page.getByRole('heading',{name:title!,exact:true})).toBeVisible();
  await page.screenshot({path:`test-results/desktop-${tag}.png`,fullPage:true});
 }
});

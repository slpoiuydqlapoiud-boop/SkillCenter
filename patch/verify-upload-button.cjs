const {chromium}=require('playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try {
  for(const scenario of ['normal','fetch-error','invalid-topology']){
   const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
   if(scenario==='fetch-error')await page.route('**/data.json',r=>r.fulfill({status:500,body:'failed'}));
   if(scenario==='invalid-topology')await page.route('**/data.json',r=>r.fulfill({contentType:'application/json',body:JSON.stringify({schemaVersion:1,cases:[{nodes:[{id:'a'}],edges:[],adaptive:{}}]})}));
   await page.goto(process.argv[2]??'http://127.0.0.1:8766/');
   await page.waitForFunction(()=>document.querySelector('#summary').textContent.includes('路径评分')||document.querySelector('#message').textContent.length>0);
   assert.equal(await page.locator('#upload').isEnabled(),true,`${scenario}: upload must remain available`);
   const [chooser]=await Promise.all([page.waitForEvent('filechooser',{timeout:5000}),page.locator('#upload').click()]);assert.ok(chooser);
   if(scenario!=='normal'){assert.equal(await page.locator('#calculate').isDisabled(),true);assert.equal(await page.locator('.node').count(),0);}
   assert.deepEqual(errors,[]);console.log(scenario+': actual button click opens file chooser');await page.close();
  }
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});

import { test, expect } from '@playwright/test';
test.setTimeout(90000);
test.use({actionTimeout:30000});

test('namespace grants are editable, stale scopes recover and private form state is cleared on switching', async ({ page }) => {
  const token=`test.${Buffer.from(JSON.stringify({sub:'alice',username:'alice',roles:['admin']})).toString('base64url')}.test`;
  await page.addInitScript(t=>localStorage.setItem('claw_token',t),token);
  const namespaces=[{tenant:'default',name:'personal',displayName:'Personal',kind:'personal',roles:['admin','member','developer','operator']},{tenant:'default',name:'engineering',displayName:'Engineering',kind:'shared',roles:['admin']}];
  let membership:Record<string,string[]>={bob:['member']};let version=1;const seen:string[]=[];
  await page.route('**/api/**',async route=>{
    const req=route.request(),url=new URL(req.url()),path=url.pathname;
    if(!path.startsWith('/api/'))return route.continue();
    const json=(body:unknown)=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    if(path==='/api/v1/me/scope')return json({tenant:'default',namespace:'personal',mode:'multi',selectorVisible:true,namespaces});
    if(path==='/api/auth/me')return json({userId:'alice',username:'alice',roles:['admin']});
    if(path.startsWith('/api/v1/namespaces/')){
      const name=path.split('/').pop();seen.push(req.headers()['x-agentscope-namespace']);
      if(req.method()==='PUT'){const body=req.postDataJSON();expect(body.version).toBe(version);membership=body.members;version++;}
      return json({namespace:{tenant:'default',name,displayName:name,kind:name==='personal'?'personal':'shared',owner:'alice',members:name==='personal'?{}:membership,version}});
    }
    if(path==='/api/v1/inbox/summary')return json({summary:{unread:0,attentionTotal:0,pendingApprovals:0}});
    return json({items:[],events:[]});
  });
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('/work/permissions?tenant=default&namespace=forged');
  await expect(page.getByRole('heading',{name:'Namespace permissions'})).toBeVisible({timeout:30000});
  await expect(page.getByLabel('Namespace',{exact:true})).toHaveValue('default/personal');
  await expect(page).toHaveURL(/namespace=personal/);
  await page.getByLabel('Namespace',{exact:true}).selectOption('default/engineering');
  await expect(page.getByRole('button',{name:'Save membership'})).toBeVisible();
  await page.getByLabel('Account ID',{exact:true}).fill('carol');
  await page.getByLabel('Member role').selectOption('viewer');
  await page.getByRole('button',{name:'Add member'}).click();
  await page.getByRole('button',{name:'Save membership'}).click();
  await expect.poll(()=>membership.carol).toEqual(['viewer']);
  await page.getByLabel('Account ID',{exact:true}).fill('unsaved-private-draft');
  await page.screenshot({path:'/tmp/agentscope-permissions-ui.png',fullPage:true});
  await page.getByLabel('Namespace',{exact:true}).selectOption('default/personal');
  await expect(page.getByText('Personal namespace membership is fixed to its owner.')).toBeVisible();
  await page.getByLabel('Namespace',{exact:true}).selectOption('default/engineering');
  await expect(page.getByLabel('Account ID',{exact:true})).toHaveValue('');
  expect(seen).not.toContain('forged');expect(errors).toEqual([]);
});

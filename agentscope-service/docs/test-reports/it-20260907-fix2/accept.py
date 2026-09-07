from probe import *
s=json.loads((ROOT/'state.json').read_text())
for name,marker in [('single-qoder','QODER=apple,pear,zebra'),('single-codex','CODEX=prime'),('team-success','CALC=400')]:
 if name in s.get('adminAcceptance',{}):continue
 iid=s['issues'][name]['issue']['id'];ex=api('/api/v1/issues/'+iid+'/export')
 if ex['issue']['status']!='in_review' or any(r['state']!='succeeded' for r in ex['runs']): continue
 results=''.join(c['content'] for c in ex.get('comments',[]) if c['type']=='result')
 if marker not in results: continue
 x=api('/api/v1/issues/'+iid+'/accept',{'expectedVersion':ex['issue']['version'],'reason':'管理员人工验收：计算、排序或质数结论与任务要求一致，批准交付。'},label='admin-accept-'+name)
 s.setdefault('adminAcceptance',{})[name]={'status':api.last_status,'response':x}
 print(name,api.last_status)
(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))

from probe import *
name='endpoint-input-20260907-v2'
x=api('/api/v1/endpoints',{'tenant':'default','namespace':'default','name':name,'slug':name,'targetType':'team','targetRef':'cf7b2403-a314-44f9-a959-e81708e7ae60','invocationMode':'job'},label='retest2-endpoint-create')
assert api.last_status==201,x
key=x['credential']; ep=x['endpoint']
pub=api('/api/v1/endpoints/'+ep['id']+'/publish',{'version':ep['version']},label='retest2-endpoint-publish');assert api.last_status==200,pub
body={'input':{'prompt':'帮我查看当前目录下的文件，写一首诗'}}
r=api('/invoke/v1/endpoints/'+ep['slug']+'/jobs',body,headers={'X-API-Key':key,'Idempotency-Key':name},label='retest2-invoke');assert api.last_status==202,r
(ROOT/'retest2-state.json').write_text(json.dumps({'endpoint':ep,'invocation':r},ensure_ascii=False,indent=2))
print(json.dumps(r,ensure_ascii=False))

negative={'input':{'prompt':'必须调用工具 missing_web_search_20260907 查询今天的手机行业新闻，并提供实际查询结果。此工具未配置且本次禁止替代工具。如果缺少此工具，明确以 blocked/failed 结束并说明缺失工具，不要伪造结果，也不要索要 API 参数。'}}
r=api('/invoke/v1/endpoints/'+ep['slug']+'/jobs',negative,headers={'X-API-Key':key,'Idempotency-Key':name+'-negative'},label='retest2-negative-invoke');assert api.last_status==202,r
(ROOT/'retest2-negative-state.json').write_text(json.dumps(r,ensure_ascii=False,indent=2))
print('negative',json.dumps(r,ensure_ascii=False))

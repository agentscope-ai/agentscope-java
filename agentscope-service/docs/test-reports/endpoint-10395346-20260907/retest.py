from probe import *
name='endpoint-input-20260907'
x=api('/api/v1/endpoints',{'tenant':'default','namespace':'default','name':name,'slug':name,'targetType':'team','targetRef':'cf7b2403-a314-44f9-a959-e81708e7ae60','invocationMode':'job'},label='retest-endpoint-create')
assert api.last_status==201,x
key=x['credential']; ep=x['endpoint']
pub=api('/api/v1/endpoints/'+ep['id']+'/publish',{'version':ep['version']},label='retest-endpoint-publish');assert api.last_status==200,pub
body={'input':{'prompt':'帮我查看当前目录下的文件，写一首诗'}}
r=api('/invoke/v1/endpoints/'+ep['slug']+'/jobs',body,headers={'X-API-Key':key,'Idempotency-Key':name},label='retest-invoke');assert api.last_status==202,r
(ROOT/'retest-state.json').write_text(json.dumps({'endpoint':ep,'invocation':r},ensure_ascii=False,indent=2))
print(json.dumps(r,ensure_ascii=False))

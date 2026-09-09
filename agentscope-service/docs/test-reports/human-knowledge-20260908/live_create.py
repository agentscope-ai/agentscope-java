from probe import *
NS='personal-8c6976e5b5410415bde9'
Q='?tenant=default&namespace='+NS
body={'tenant':'default','namespace':NS,'title':'IT 执行摘要回归：新能源与智能手机','description':'这是隔离的回归测试任务。请分别调研新能源技术和智能手机技术的最新发展趋势，各形成一份报告，并在主任务汇总两份报告。请分成两个子任务交给团队成员处理。','assigneeType':'team','assigneeRef':'77fb26b6-23c8-4ff8-898b-96bcafa30ae5','creator':{'type':'human','ref':'admin'}}
x=api('/api/v1/issues'+Q,body,label='live-create');assert api.last_status in (200,201),x
(ROOT/'live-state.json').write_text(json.dumps({'root':x['issue']['id'],'children':[]},ensure_ascii=False,indent=2))
print('root',x['issue']['id'])

from probe import *
NS='personal-8c6976e5b5410415bde9';Q='?tenant=default&namespace='+NS
state=json.loads((ROOT/'live-state.json').read_text())
if 'negative' not in state:
 x=api('/api/v1/issues'+Q,{'tenant':'default','namespace':NS,'title':'IT 保留实时来源要求','description':'请联网查询今天发布的智能手机行业新闻，必须给出可核验的原始链接与发布日期。不能仅使用模型知识替代实时检索。','assigneeType':'agent','assigneeRef':'678ae47f-0848-43d9-95e5-a79f5ed9fa72','creator':{'type':'human','ref':'admin'}},label='negative-create')
 assert api.last_status in (200,201),x
 state['negative']=x['issue']['id'];(ROOT/'live-state.json').write_text(json.dumps(state,indent=2))
c=api('/api/v1/issues/'+state['negative']+'/export'+Q,label='negative-current')
print('negative',c['issue']['id'],c['issue']['status'],[(t['triggerType'],t['status']) for t in c.get('tasks',[])])
if c['issue']['status']=='blocked' and not any(x['author']['type']=='human' for x in c.get('comments',[])):
 x=api('/api/v1/issues/'+state['negative']+'/comments'+Q,{'content':'仍然需要今天的实时来源和可核验链接，不接受基于自身知识的替代报告；缺少搜索能力时请明确说明阻塞。'},label='negative-human')
 assert api.last_status in (200,201),x
 print('submitted strict requirement')

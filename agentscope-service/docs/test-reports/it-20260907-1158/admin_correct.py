from probe import *
s=json.loads((ROOT/'state.json').read_text())
if 'adminCorrection' in s:
 print('already submitted');sys.exit(0)
iid=s['issues']['single-managed']['issue']['id']
ex=api('/api/v1/issues/'+iid+'/export',label='before-admin-correction-export')
if any(r['state'] not in ['succeeded','failed','cancelled'] for r in ex['runs']):
 print('cross mention still active');sys.exit(0)
content='管理员更正：停止之前的19×21、12×12及CROSS_ACK任务。本条是最新的人类需求，第二个乘数改为13。请只计算12×13，最终交付必须是 ADMIN_FINAL=156，不要重放旧委派或旧答案，不要主动mention其他人；按协作协议完成任务。'
body={'content':content,'mentions':[{'type':'agent','ref':s['agents']['qoder']}]}
x=api('/api/v1/issues/'+iid+'/comments',body,label='admin-correction')
s['adminCorrection']={'issueId':iid,'request':body,'response':x,'status':api.last_status}
(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
print('admin correction',api.last_status)

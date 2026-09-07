from probe import *
s=json.loads((ROOT/'live-state.json').read_text())
for name,iid in s.items():
 before=api('/api/v1/issues/'+iid+'/export',label='before-'+name)
 assert not before.get('tasks') and not before.get('runs') and len(before['children'])==2, before
 assert before['issue']['status']==('done' if name=='accepted' else 'in_review')
 body={'content':'非常好'}
 if name=='mention':body={'content':'@MA1 非常好，谢谢！','mentions':[{'type':'agent','ref':'90fac7c8-c0f6-4f57-a278-f499dcd1fd44'}]}
 if name=='accepted':body={'content':'做得很好，感谢！'}
 if name=='change':body={'content':'请新增一项：把 7×8 委派给 MA2 计算，原有 A、B 已完成，无需重做。最后在主 Issue 给出新增计算的实际结果，并保留原结果 84。'}
 x=api('/api/v1/issues/'+iid+'/comments',body,label='input-'+name);assert api.last_status in (200,201),x
 print(name,iid)

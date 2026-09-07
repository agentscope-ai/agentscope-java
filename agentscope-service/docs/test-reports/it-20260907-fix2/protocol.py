from probe import *
s=json.loads((ROOT/'state.json').read_text());keys=json.loads(pathlib.Path('/tmp/agentscope-it-20260907-fix2-endpoint-keys.json').read_text())
v=s['invocations']['managed-job-ok'];h={'X-API-Key':keys[v['endpoint']],'Idempotency-Key':PREFIX+'-managed-job-ok'}
x=api('/invoke/v1/endpoints/'+s['endpoints'][v['endpoint']]['slug']+'/jobs',v['request'],headers=h,label='endpoint-idempotency-after-completion')
s['idempotencyAfterCompletion']={'status':api.last_status,'response':x};(ROOT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
r=urllib.request.Request(BASE+v['response']['eventsUrl'],headers={'X-API-Key':keys[v['endpoint']],'Last-Event-ID':'4','Accept':'text/event-stream'})
with urllib.request.urlopen(r,timeout=10) as f: status=f.status;raw=f.read().decode()
ids=[int(l[4:]) for l in raw.splitlines() if l.startswith('id: ')]
(ROOT/'evidence/sse-resume-managed-job.json').write_text(json.dumps(scrub({'status':status,'ids':ids,'stream':raw}),ensure_ascii=False,indent=2))
r=urllib.request.Request(BASE+'/mcp/collaboration',headers={'Authorization':'Bearer '+TOKEN})
try:
 with urllib.request.urlopen(r,timeout=10) as f: status=f.status;allow=f.headers.get('Allow')
except urllib.error.HTTPError as e: status=e.code;allow=e.headers.get('Allow')
(ROOT/'evidence/mcp-get.json').write_text(json.dumps({'status':status,'allow':allow}))
print('idempotency',s['idempotencyAfterCompletion']['status'],'SSE',ids,'MCP',status,allow)

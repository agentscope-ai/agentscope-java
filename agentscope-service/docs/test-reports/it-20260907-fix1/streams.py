from probe import *
import time,concurrent.futures
s=json.loads((ROOT/'state.json').read_text());keys=json.loads(pathlib.Path('/tmp/agentscope-it-20260907-fix1-endpoint-keys.json').read_text())
def stream(item):
 name,v=item;path=v['response'].get('eventsUrl');lines=[];err=None;status=None
 if not path:return
 req=urllib.request.Request(BASE+path,headers={'X-API-Key':keys[v['endpoint']],'Accept':'text/event-stream'})
 try:
  with urllib.request.urlopen(req,timeout=4) as f:
   status=f.status;start=time.monotonic()
   while time.monotonic()-start<7 and len(lines)<12000:
    line=f.readline().decode()
    if not line:break
    if line.startswith('data: '):
     try:line='data: '+json.dumps(scrub(json.loads(line[6:])),ensure_ascii=False)+'\n'
     except ValueError:line=scrub(line)
    lines.append(line)
 except Exception as e:err=type(e).__name__+': '+str(e)
 raw=''.join(lines);ids=[int(x[4:]) for x in raw.splitlines() if x.startswith('id: ')]
 result={'path':path,'httpStatus':status,'readEnd':err or 'EOF','eventCount':len(ids),'idsIncreasing':all(a<b for a,b in zip(ids,ids[1:])),'stream':raw}
 (ROOT/'evidence'/('stream-'+name+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
 return name,{k:v for k,v in result.items() if k!='stream'}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 for r in pool.map(stream,s['invocations'].items()):print(r,flush=True)

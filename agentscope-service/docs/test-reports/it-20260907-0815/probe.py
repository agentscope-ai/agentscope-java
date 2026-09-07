import urllib.request,urllib.error,json,pathlib,datetime,re,sys,os
ROOT=pathlib.Path(__file__).resolve().parent
BASE='http://localhost:18080'
PREFIX='it-20260907-0815'
def scrub(x):
 if isinstance(x,dict): return {k:('[REDACTED]' if re.search(r'token|password|credential|secret|apiKey|authorization',k,re.I) else scrub(v)) for k,v in x.items()}
 if isinstance(x,list): return [scrub(v) for v in x]
 if isinstance(x,str):
  try:
   parsed=json.loads(x)
   if isinstance(parsed,(dict,list)): return json.dumps(scrub(parsed),ensure_ascii=False)
  except (ValueError,TypeError): pass
  x=re.sub(r'(?i)(Bearer\s+)[A-Za-z0-9_.-]+',r'\1[REDACTED]',x)
  return re.sub(r'eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+','[REDACTED JWT]',x)
 return x
def login():
 r=urllib.request.Request(BASE+'/api/auth/login',json.dumps({'username':os.environ.get('IT_USERNAME','admin'),'password':os.environ['IT_PASSWORD']}).encode(),{'Content-Type':'application/json'})
 return json.load(urllib.request.urlopen(r,timeout=15))['token']
TOKEN=login()
def api(path,body=None,method=None,label=None,timeout=40):
 method=method or ('POST' if body is not None else 'GET'); start=datetime.datetime.now(datetime.timezone.utc).isoformat()
 r=urllib.request.Request(BASE+path,json.dumps(body).encode() if body is not None else None,{'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'},method=method)
 try:
  with urllib.request.urlopen(r,timeout=timeout) as f: status=f.status; raw=f.read().decode()
 except urllib.error.HTTPError as e: status=e.code; raw=e.read().decode()
 except Exception as e: status=0; raw=str(e)
 try: out=json.loads(raw)
 except: out={'raw':raw}
 record=scrub({'at':start,'method':method,'path':path,'request':body,'status':status,'response':out})
 if label: (ROOT/'evidence'/f'{label}.json').write_text(json.dumps(record,ensure_ascii=False,indent=2))
 with (ROOT/'evidence'/'http.jsonl').open('a') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')
 return out
if __name__=='__main__':
 for label,path in [('agents','/api/v1/agents?tenant=default&namespace=default'),('runtime-options','/api/v1/agents/runtime-options?tenant=default&namespace=default'),('hosts','/api/v1/runtime-hosts'),('environments','/api/environments')]:
  out=api(path,label='baseline-'+label); print(label,json.dumps(scrub(out),ensure_ascii=False)[:600])
 for aid in ['412b8523-59e4-4b32-8a39-2d343c435132','cede31bd-7922-4786-8526-78254b631bbe']:
  for suffix in ['definition','bindings','instances','overview']:
   x=api('/api/v1/agents/'+aid+'/'+suffix,label='baseline-'+aid+'-'+suffix);print(aid,suffix,json.dumps(scrub(x),ensure_ascii=False)[:3500])

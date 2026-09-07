import urllib.request,urllib.error,json,pathlib,datetime,re,sys,os
ROOT=pathlib.Path(__file__).resolve().parent
BASE='http://localhost:18080'
PREFIX='it-20260907-1130'
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
def api(path,body=None,method=None,label=None,timeout=40,headers=None):
 method=method or ('POST' if body is not None else 'GET'); start=datetime.datetime.now(datetime.timezone.utc).isoformat()
 r=urllib.request.Request(BASE+path,json.dumps(body).encode() if body is not None else None,({'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'} if headers is None else {'Content-Type':'application/json',**headers}),method=method)
 try:
  with urllib.request.urlopen(r,timeout=timeout) as f: status=f.status; raw=f.read().decode()
 except urllib.error.HTTPError as e: status=e.code; raw=e.read().decode()
 except Exception as e: status=0; raw=str(e)
 try: out=json.loads(raw)
 except: out={'raw':raw}
 record=scrub({'at':start,'method':method,'path':path,'request':body,'status':status,'response':out})
 if label: (ROOT/'evidence'/f'{label}.json').write_text(json.dumps(record,ensure_ascii=False,indent=2))
 with (ROOT/'evidence'/'http.jsonl').open('a') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')
 api.last_status=status
 return out

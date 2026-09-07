from probe import *
import subprocess,hashlib,zipfile,io
REPO=ROOT.parents[3]
def cmd(*args): return subprocess.check_output(args,cwd=REPO,text=True).strip()
paths=[REPO/'agentscope-service/aistio/bin/aistiod']+[next(p for p in (REPO/('agentscope-service/'+n+'/target')).glob(n+'-*.jar') if not any(x in p.name for x in ['sources','javadoc','tests'])) for n in ['service-dataplane','service-scheduler','service-gateway']]+[pathlib.Path('/Users/ken/.local/bin')/n for n in ['aistio-runtime-host','agentscope']]
artifacts={str(p):{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'mtime':p.stat().st_mtime} for p in paths}
aistio=paths[0].read_bytes();host=paths[-2].read_bytes()
with zipfile.ZipFile(paths[1]) as z:
 managed=z.read('BOOT-INF/classes/io/agentscope/builder/web/catalog/HarnessAgentBuildService.class')
 harness=zipfile.ZipFile(io.BytesIO(z.read(next(n for n in z.namelist() if n.startswith('BOOT-INF/lib/agentscope-harness-')))))
 web=harness.read('io/agentscope/harness/agent/tool/WebTools$WebSearchTool.class')
markers={'control_delegation_reply':b'replyToOwnDelegation' in aistio,'host_delegation_reply':b'Do not mention the responder' in host,'control_current_request':b'currentRequest' in aistio,'control_endpoint_correlation':b'endpointInvocationId' in aistio,'control_exact_math':b'math.evaluate' in aistio,'host_current_request':b'CURRENT REQUEST (latest instructions take precedence)' in host,'managed_current_request':b'currentRequest' in managed,'managed_math_verification':b'math.evaluate' in managed,'web_structured_failure':b'java/lang/IllegalStateException' in web}
report={'at':datetime.datetime.now().astimezone().isoformat(),'directory':str(REPO),'prefix':PREFIX,'head':cmd('git','rev-parse','HEAD'),'branch':cmd('git','branch','--show-current'),'gitStatus':cmd('git','status','--short'),'workingDiffSha256':hashlib.sha256(subprocess.check_output(['git','diff'],cwd=REPO)).hexdigest(),'artifacts':artifacts,'fixMarkers':markers,'processes':[l for l in cmd('ps','-eo','pid,lstart,comm').splitlines() if any(n in l for n in ['aistiod','aistio-runtime-host','java'])],'build':cmd('go','version','-m',str(paths[0]))}
(ROOT/'evidence'/((sys.argv[1] if len(sys.argv)>1 else 'baseline-build')+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({'branch':report['branch'],'fixMarkers':markers,'processes':report['processes']},ensure_ascii=False,indent=2))
assert all(markers.values())

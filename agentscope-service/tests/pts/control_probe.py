#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Cancel a suspended Turn, verify queued user.message progress and one physical MCP call."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid


def run(args):
    os.umask(0o077)
    args.out.mkdir(parents=True,exist_ok=False)
    token = json.loads(args.token_file.read_text())['token']
    headers = {'Authorization':'Bearer '+token,'Content-Type':'application/json',
        'X-Managed-Agents-Tenant':'default','X-Managed-Agents-Namespace':'default',
        'Managed-Agents-Extensions':'turn-inputs-v1'}
    marker = args.batch+'-'+uuid.uuid4().hex[:12]
    def api(path,body=None,key=None):
        request = urllib.request.Request(args.base_url.rstrip('/')+path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={**headers,**({'Idempotency-Key':key} if key else {})})
        with urllib.request.urlopen(request,timeout=30) as response:
            return json.load(response)
    session = api('/v1/sessions',{'agent':args.agent,'environment_id':args.environment,
        'title':marker,'metadata':{'load_test_batch':args.batch,'load_test_scenario':'cancel'}},marker+'-create')['id']
    path = '/v1/sessions/'+session
    def message(text,key):
        value = api(path+'/events',{'events':[{'type':'user.message','content':[{'type':'text','text':text}],
            'managed_agents':{'intent':'new_turn'}}]},key)
        return value['data'][0]['managed_agents']['receipt']['id']
    first = message('只调用一次 longwait__long_wait，marker='+marker+'，seconds=30。等待真实结果后报告 marker、call_id。',marker+'-t1')
    (args.out/'identity.json').write_text(json.dumps({'session_id':session,'turn_id':first,'marker':marker}))
    deadline = time.monotonic()+180
    while time.monotonic()<deadline:
        status = api(path+'/turns/'+first)['status']
        if status=='waiting_tool':break
        if status in ['completed','failed','cancelled']:raise RuntimeError('No suspended-tool window before terminal '+status)
        time.sleep(1)
    else:raise RuntimeError('No suspended-tool window before deadline')
    second = message('不要调用工具。仅回复 PTS_CANCEL_FOLLOWUP。',marker+'-t2')
    control = {'events':[{'type':'user.interrupt','managed_agents':{'intent':'cancel','turn_id':first}}]}
    sent = api(path+'/events',control,marker+'-cancel')
    repeated = api(path+'/events',control,marker+'-cancel')
    if sent['data'][0]['id']!=repeated['data'][0]['id']:
        raise RuntimeError('Repeated cancellation did not preserve its event identity')
    while time.monotonic()<deadline:
        a,b = api(path+'/turns/'+first),api(path+'/turns/'+second)
        if a['status']=='cancelled' and b['status']=='completed':break
        if a['status'] in ['completed','failed'] or b['status'] in ['cancelled','failed']:
            raise RuntimeError('Unexpected cancel/FIFO terminal state')
        time.sleep(1)
    else:raise RuntimeError('Cancellation or queued follow-up did not finish')
    output = api(path+'/turns/'+second+'/output?limit=100')
    if 'PTS_CANCEL_FOLLOWUP' not in json.dumps(output,ensure_ascii=False):
        raise RuntimeError('Queued follow-up output missing')
    # This fixture intentionally shields physical execution from HTTP cancellation. MCP cancel
    # currently queries the original operation; require its known terminal result, not a stopped claim.
    physical = []
    while time.monotonic()<deadline:
        result = subprocess.run(['kubectl','--kubeconfig',str(args.kubeconfig),'-n','agencore-ma',
            'logs','-l','app=managed-agents-long-tool-mcp','--tail=-1','--max-log-requests=4'],
            capture_output=True,text=True,check=True,timeout=30)
        records=[]
        for line in result.stdout.splitlines():
            try: record=json.loads(line[line.index('{'):])
            except (ValueError,TypeError): continue
            if record.get('marker')==marker: records.append(record)
        starts=[r for r in records if r.get('event')=='long_wait.started']
        physical=[{**r,'state':'succeeded'} for r in records if r.get('event')=='long_wait.completed']
        if len(starts)>1 or len(physical)>1:raise RuntimeError('Duplicate physical MCP execution')
        if len(starts)==len(physical)==1 and starts[0]['call_id']==physical[0]['call_id']:break
        time.sleep(2)
    else:raise RuntimeError('Independent MCP stdout did not confirm the original physical outcome')
    summary = {'session_id':session,'cancelled_turn':first,'completed_followup':second,
        'repeat_cancel_same_event':True,'physical_calls':physical,
        'physical_cancel_supported':False,'passed':True}
    (args.out/'summary.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps({'passed':True,'cancelled_turns':1,'completed_followups':1,'physical_calls':1}),flush=True)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--agent',required=True)
    parser.add_argument('--environment',required=True)
    parser.add_argument('--token-file',type=Path,required=True)
    parser.add_argument('--kubeconfig',type=Path,required=True)
    parser.add_argument('--batch',required=True)
    parser.add_argument('--out',type=Path,required=True)
    run(parser.parse_args())

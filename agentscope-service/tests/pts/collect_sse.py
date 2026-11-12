#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Save content-free SSE timing evidence before removing the temporary subscriber."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

REMOTE = r'''
from collections import Counter
import hashlib,json,math,tarfile,time
from pathlib import Path
r=Path('/results')
delays,terminal_delays,first_delays=[],[],[]
connected,ended,first={}, {},set()
events=Counter()
errors=0
for line in (r/'events.jsonl').open():
    v=json.loads(line);sid=v['session_id']
    if 'connected_at' in v:connected[sid]=v['connected_at'];continue
    if 'error' in v:errors+=1;continue
    events[v['type']]+=1
    delay=v.get('delivery_delay_seconds')
    if delay is not None:
        delays.append(delay)
        if sid not in first:first_delays.append(delay)
        if v['type'] in ['managed_agents.turn.completed','managed_agents.turn.failed','managed_agents.turn.cancelled']:terminal_delays.append(delay)
    first.add(sid)
    if v['type']=='session.status_idle':ended[sid]=v['received_at']
def dist(values):
    values=sorted(values)
    def q(p):
        if not values:return None
        n=(len(values)-1)*p;lo=math.floor(n)
        return values[lo]+(values[min(lo+1,len(values)-1)]-values[lo])*(n-lo)
    return dict(samples=len(values),p50=q(.5),p95=q(.95),p99=q(.99),max=max(values) if values else None)
points=[(at,1) for at in connected.values()]+[(at,-1) for at in ended.values()]
active=peak=0
for _,delta in sorted(points):active+=delta;peak=max(peak,active)
summary=dict(connected_sessions=len(connected),closed_after_idle=len(ended),errors=errors,
    peak_concurrent_streams=peak,event_types=dict(events),event_delivery_seconds=dist(delays),
    terminal_delivery_seconds=dist(terminal_delays),first_event_delivery_seconds=dist(first_delays),
    note='Delivery delay is receive time minus durable processed_at; includes server polling and network, depends on host clock synchronization.')
(r/'delivery-summary.json').write_text(json.dumps(summary,indent=2))
files=[r/name for name in ['events.jsonl','timeline.jsonl','summary.json','delivery-summary.json']]
manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
(r/'evidence-manifest.json').write_text(json.dumps(manifest,indent=2))
with tarfile.open(r/'evidence.tar.gz','w:gz') as archive:
    for p in files+[r/'evidence-manifest.json']:archive.add(p,arcname=p.name)
summary['archive_sha256']=hashlib.sha256((r/'evidence.tar.gz').read_bytes()).hexdigest()
print(json.dumps(summary))
'''


def run(args):
    result = subprocess.run(['kubectl','--kubeconfig',str(args.kubeconfig),'-n','agencore-ma',
        'exec','deployment/managed-agents-pts-sse-bridge','--','python','-c',REMOTE],
        capture_output=True,text=True,timeout=120)
    if result.returncode:
        raise RuntimeError('SSE evidence preparation failed: '+result.stderr[-400:])
    summary = json.loads(result.stdout)
    archive = args.out/'sse-evidence.tar.gz'
    with urllib.request.urlopen(args.bridge_url.rstrip('/')+'/artifact',timeout=120) as response,archive.open('wb') as output:
        while data:=response.read(1024*1024):
            output.write(data)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=summary['archive_sha256']:
        raise RuntimeError('Downloaded SSE evidence hash differs')
    (args.out/'sse-delivery.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps(summary))


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig',type=Path,required=True)
    parser.add_argument('--bridge-url',default='http://127.0.0.1:19114')
    parser.add_argument('--out',type=Path,required=True)
    run(parser.parse_args())

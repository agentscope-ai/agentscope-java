#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Compare each streamed event ID and canonical payload against the durable protocol ledger."""
import argparse
import json
from pathlib import Path
import subprocess

REMOTE = r'''
import hashlib,json,os,sys,urllib.request
import psycopg
from psycopg.rows import dict_row
batch,url=sys.argv[1:3]
only_session=sys.argv[3] if len(sys.argv)>3 else None
totals=dict(sessions=0,expected_events=0,received_events=0,missing_events=0,unexpected_events=0,
    payload_mismatches=0,stream_errors=0,duplicates=0,incomplete_streams=0,missing_streams=0,
    admitted_sessions=0,created_without_admission=0)
with psycopg.connect(os.environ['DATABASE_URI'],autocommit=True,row_factory=dict_row,
        options='-c default_transaction_read_only=on -c statement_timeout=30000') as db:
    scopes=db.execute("SELECT scope_key,receipt::jsonb->>'id' AS session_id FROM ma.protocol_creation WHERE receipt::jsonb->'metadata'->>'load_test_batch'=%s ORDER BY scope_key",(batch,)).fetchall()
    if only_session:scopes=[s for s in scopes if s['session_id']==only_session]
    for offset in range(0,len(scopes),40):
        page=scopes[offset:offset+40]
        req=urllib.request.Request(url.rstrip('/')+'/facts',data=json.dumps([s['session_id'] for s in page]).encode(),headers={'Content-Type':'application/json'})
        observed=json.load(urllib.request.urlopen(req,timeout=20))
        keys=[s['scope_key'] for s in page]
        admitted={row['scope_key'] for row in db.execute('SELECT DISTINCT scope_key FROM ma.turn WHERE scope_key=ANY(%s)',(keys,)).fetchall()}
        rows=db.execute("""SELECT DISTINCT ON(p.scope_key,p.event_id) p.scope_key,p.event_id,p.payload
            FROM ma.protocol_event p JOIN (SELECT scope_key,min(sequence) AS first_sequence
              FROM ma.protocol_event WHERE scope_key=ANY(%s) AND event_type='managed_agents.turn.accepted'
              GROUP BY scope_key) b USING(scope_key)
            WHERE p.scope_key=ANY(%s) AND p.sequence>=b.first_sequence AND p.processed_at IS NOT NULL
            ORDER BY p.scope_key,p.event_id,p.sequence DESC""",(keys,keys)).fetchall()
        expected={key:{} for key in keys}
        for row in rows:
            payload=json.loads(row['payload'])
            expected[row['scope_key']][row['event_id']]=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
        for scope in page:
            totals['sessions']+=1
            is_admitted=scope['scope_key'] in admitted
            totals['admitted_sessions']+=is_admitted
            totals['created_without_admission']+=not is_admitted
            state=observed.get(scope['session_id'])
            wanted=expected[scope['scope_key']]
            totals['expected_events']+=len(wanted)
            if state is None:
                totals['missing_streams']+=1
                totals['missing_events']+=len(wanted)
                continue
            got=state.get('received',{})
            totals['received_events']+=len(got)
            totals['missing_events']+=len(wanted.keys()-got.keys())
            totals['unexpected_events']+=len(got.keys()-wanted.keys())
            totals['payload_mismatches']+=sum(got[eid]!=wanted[eid] for eid in got.keys()&wanted.keys())
            totals['stream_errors']+=bool(state.get('error'))
            totals['duplicates']+=state['duplicates']
            totals['incomplete_streams']+=is_admitted and not state.get('complete',False)
totals['passed']=totals['sessions']>0 and totals['expected_events']>0 and not any(totals[key] for key in [
    'missing_events','unexpected_events','payload_mismatches','stream_errors','duplicates','incomplete_streams','missing_streams'])
print(json.dumps(totals))
'''


def run(args):
    command = ['kubectl','--kubeconfig',str(args.kubeconfig),'-n','agencore-ma','exec',
        'deployment/managed-agents-observer','--','python','-c',REMOTE,args.batch,args.bridge_url]
    if args.session_id:
        command.append(args.session_id)
    result = subprocess.run(command,capture_output=True,text=True,timeout=300)
    if result.returncode:
        raise RuntimeError('SSE durable audit failed: '+result.stderr[-500:])
    value = json.loads(result.stdout)
    value['session_filter'] = args.session_id
    args.out.write_text(json.dumps(value,indent=2))
    print(json.dumps(value))
    if not value['passed']:
        raise RuntimeError('SSE events did not match the durable ledger')


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch',required=True)
    parser.add_argument('--kubeconfig',type=Path,required=True)
    parser.add_argument('--bridge-url',default='http://managed-agents-pts-sse-bridge')
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--session-id',help='One explicit functional-probe Session; omit for full pressure-batch acceptance')
    run(parser.parse_args())

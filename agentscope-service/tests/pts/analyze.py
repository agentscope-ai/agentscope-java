#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Reconcile a PTS stage with read-only durable Turn, tool and FIFO facts."""
import argparse
from collections import defaultdict
from datetime import datetime
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.request
from zoneinfo import ZoneInfo
from pts import cloud

REMOTE = r'''
import json,os,sys,time,gzip,base64
import psycopg
from psycopg.rows import dict_row
batch=sys.argv[1]
offset=int(sys.argv[2])
with psycopg.connect(os.environ['DATABASE_URI'],autocommit=True,row_factory=dict_row,
                    options='-c default_transaction_read_only=on -c statement_timeout=15000') as db:
    scopes=globals().get('SCOPES_PAGE')
    if scopes is None:
        scopes=db.execute("SELECT scope_key,receipt::jsonb->>'id' AS session_id FROM ma.protocol_creation WHERE receipt::jsonb->'metadata'->>'load_test_batch'=%s ORDER BY scope_key LIMIT 40 OFFSET %s",(batch,offset)).fetchall()
    keys=[s['scope_key'] for s in scopes]
    turns=db.execute("""SELECT t.scope_key,t.turn_id,t.status,t.admission_sequence,t.created_at,
        t.usage_summary::jsonb->'totals' AS usage,t.usage_summary::jsonb->>'model_calls' AS model_calls,
        min(e.created_at) FILTER(WHERE e.type='turn.running') AS started,
        max(e.created_at) FILTER(WHERE e.type IN ('turn.completed','turn.failed','turn.cancelled')) AS ended,
        min(e.sequence) FILTER(WHERE e.type='turn.running') AS start_sequence,
        max(e.sequence) FILTER(WHERE e.type='turn.completed') AS end_sequence,
        coalesce(so.sealed,o.sealed) AS sealed
        FROM ma.turn t LEFT JOIN ma.public_event e ON e.scope_key=t.scope_key
          AND e.payload::jsonb->>'turn_id'=t.turn_id
          AND e.type IN ('turn.running','turn.completed','turn.failed','turn.cancelled')
        LEFT JOIN ma.protocol_output o ON o.scope_key=t.scope_key AND o.turn_id=t.turn_id
        LEFT JOIN ma.session_store_output so ON so.scope_key=t.scope_key AND so.turn_id=t.turn_id
        WHERE t.scope_key=ANY(%s)
        GROUP BY t.scope_key,t.turn_id,t.status,t.admission_sequence,t.created_at,t.usage_summary,o.sealed,so.sealed
        ORDER BY t.scope_key,t.admission_sequence""",(keys,)).fetchall()
    inputs=db.execute('SELECT status,count(*) AS count FROM ma.input WHERE scope_key=ANY(%s) GROUP BY status',(keys,)).fetchall()
    physical=db.execute("""SELECT h.result::jsonb#>>'{structuredContent,marker}' AS marker,
        h.status AS state,h.result::jsonb#>>'{structuredContent,call_id}' AS call_id,
        extract(epoch FROM (h.result::jsonb#>>'{structuredContent,started_at}')::timestamptz)*1000 AS started,
        extract(epoch FROM (h.result::jsonb#>>'{structuredContent,finished_at}')::timestamptz)*1000 AS ended,
        'hands_captured_result' AS evidence_source
        FROM ma.hands_execution h WHERE h.scope_key=ANY(%s) AND h.tool_name='mcp.call'
        AND h.result::jsonb#>>'{structuredContent,call_id}' IS NOT NULL""",(keys,)).fetchall()
    worker=db.execute("""SELECT h.scope_key,h.turn_id,h.task_id,
        min(e.recorded_at) FILTER(WHERE e.phase='received') AS received,
        min(e.recorded_at) FILTER(WHERE e.phase='starting') AS starting,
        min(e.recorded_at) FILTER(WHERE e.phase='result_pending') AS captured
        FROM ma.hands_execution h JOIN ma.hands_execution_event e USING(scope_key,task_id)
        WHERE h.scope_key=ANY(%s) AND h.batch_parent_id IS NULL
        GROUP BY h.scope_key,h.turn_id,h.task_id""",(keys,)).fetchall()
    outputs=db.execute("""SELECT t.scope_key,t.turn_id,s.session_id,
        jsonb_path_query_array(e.payload::jsonb,'$.content[*].text') AS texts,
        coalesce(jsonb_path_query_array(e.payload::jsonb,'$.content[*].text')::text LIKE '%%PTS_OK%%'
          AND (position(s.session_id in t.input_payload)=0 OR jsonb_path_query_array(e.payload::jsonb,'$.content[*].text')::text LIKE '%%'||s.session_id||'%%'),false) AS short_valid
        FROM ma.turn t JOIN ma.session s USING(scope_key)
        LEFT JOIN ma.protocol_output o USING(scope_key,turn_id)
        LEFT JOIN LATERAL (
          SELECT payload FROM (
            SELECT payload,0 AS preference,ordinal AS position FROM ma.session_store_output_event se
              WHERE se.scope_key=t.scope_key AND se.turn_id=t.turn_id AND finalized=TRUE
            UNION ALL SELECT p.payload,1 AS preference,p.sequence AS position FROM ma.protocol_event p
              WHERE p.scope_key=t.scope_key AND p.event_id=(o.event_ids::jsonb->>-1)
          ) candidates ORDER BY preference,position DESC LIMIT 1) e ON true
        WHERE t.scope_key=ANY(%s) AND t.status='succeeded'""",(keys,)).fetchall()
    physical_by_marker={}
    for row in physical: physical_by_marker.setdefault(row['marker'],[]).append(row)
    for row in outputs:
        text='\n'.join(row.pop('texts') or [])
        sid=row.pop('session_id')
        marker='pts-'+sid
        row['tool_valid']=any(marker in text and call['call_id'] in text
            for call in physical_by_marker.get(marker,[]) if call['state']=='succeeded')
        row['injection_valid']=sid+'-injected' in text
    # Native public-event sequence ordering is the durable FIFO proof, independent of query timing.
    failures=db.execute("""SELECT coalesce(e.payload::jsonb#>>'{error,code}','unknown') AS code,count(*) AS count
        FROM ma.public_event e WHERE e.scope_key=ANY(%s) AND e.type='turn.failed'
        GROUP BY coalesce(e.payload::jsonb#>>'{error,code}','unknown')""",(keys,)).fetchall()
raw=json.dumps({'sampled_at':time.time(),'scopes':scopes,'turns':turns,'inputs':inputs,
                  'physical':physical,'worker':worker,'failures':failures,'outputs':outputs},default=float).encode()
compressed=base64.b64encode(gzip.compress(raw)).decode()
if len(compressed)>24000:raise RuntimeError('Stage facts exceed safe exec transport limit')
print(compressed)
'''


def facts(kubeconfig, batch):
    import gzip, base64
    merged = {key: [] for key in ['scopes','turns','inputs','physical','worker','failures','outputs']}
    totals = {key: defaultdict(int) for key in ['inputs','failures']}
    # One exec stream avoids hundreds of Kubernetes TLS handshakes. Each flushed page stays
    # below ACS's frame limit; independent header/trailer counts detect truncated transport.
    remote = '''import os,sys,json,time,psycopg
from psycopg.rows import dict_row
with psycopg.connect(os.environ['DATABASE_URI'],autocommit=True,row_factory=dict_row,options='-c default_transaction_read_only=on -c statement_timeout=15000') as count_db:
    all_scopes=count_db.execute("SELECT scope_key,receipt::jsonb->>'id' AS session_id FROM ma.protocol_creation WHERE receipt::jsonb->'metadata'->>'load_test_batch'=%s ORDER BY scope_key",(sys.argv[1],)).fetchall()
expected=len(all_scopes)
print(json.dumps({'expected_scopes':expected}),flush=True)
if expected>100000:raise RuntimeError('Stage exceeds bounded fact pagination')
pages=0
for offset in range(0,100040,40):
    sys.argv[2]=str(offset)
    SCOPES_PAGE=all_scopes[offset:offset+40]
    exec(SOURCE)
    pages+=1
    if len(scopes)<40:break
    time.sleep(.03)
print(json.dumps({'pages':pages,'expected_scopes':expected}),flush=True)
'''.replace('SOURCE',repr(REMOTE.replace('print(compressed)','print(compressed,flush=True)')))
    command = ['kubectl','--kubeconfig',str(kubeconfig),'-n','agencore-ma','exec',
        'deployment/managed-agents-observer','--','python','-u','-c',remote,batch,'0']
    result = subprocess.run(command,capture_output=True,text=True,timeout=900)
    if result.returncode:
        raise RuntimeError('Read-only stage fact query failed; no capacity conclusion: '+result.stderr[-400:])
    lines = result.stdout.splitlines()
    header,trailer = json.loads(lines[0]),json.loads(lines[-1])
    if len(lines)-2!=trailer['pages'] or header['expected_scopes']!=trailer['expected_scopes']:
        raise RuntimeError('Stage fact stream is truncated')
    for encoded in lines[1:-1]:
        page = json.loads(gzip.decompress(base64.b64decode(encoded)))
        merged['sampled_at'] = page['sampled_at']
        for key in ['scopes','turns','physical','worker','outputs']:
            merged[key].extend(page[key])
        for key, field in [('inputs','status'),('failures','code')]:
            for row in page[key]:
                totals[key][row[field]] += row['count']
    if len(merged['scopes'])!=header['expected_scopes']:
        raise RuntimeError('Stage facts omit created Sessions')
    for key, field in [('inputs','status'),('failures','code')]:
        merged[key] = [{field:value,'count':count} for value,count in totals[key].items()]
    return merged


def percentile(values, q):
    if not values:
        return None
    values = sorted(values)
    index = (len(values)-1)*q
    low = math.floor(index)
    return values[low]+(values[min(low+1,len(values)-1)]-values[low])*(index-low)


def distribution(values):
    return {'samples': len(values), 'p50': percentile(values,.5), 'p95': percentile(values,.95),
            'p99': percentile(values,.99), 'max': max(values) if values else None}


def concurrent_peak(intervals, observed_until):
    points = [(first, 1) for first, _ in intervals]
    points += [(last if last is not None else observed_until, -1) for _, last in intervals]
    current, peak = 0, 0
    for _, delta in sorted(points, key=lambda item: (item[0], item[1])):
        current += delta
        peak = max(peak, current)
    return peak


def reconcile(value, config, begin, end, observations):
    sessions = defaultdict(list)
    for turn in value['turns']:
        sessions[turn['scope_key']].append(turn)
    statuses = defaultdict(int)
    intervals, queue, latencies, successes = [], [], [], []
    fifo_errors, unsealed = 0, 0
    for key, turns in sessions.items():
        turns.sort(key=lambda t:t['admission_sequence'])
        for turn in turns:
            statuses[turn['status']] += 1
            if turn['started'] is not None:
                queue.append((turn['started']-turn['created_at'])/1000)
            if turn['status']=='succeeded' and not turn['sealed']:
                unsealed += 1
        for first, second in zip(turns,turns[1:]):
            if second['start_sequence'] is not None and (first['end_sequence'] is None or first['end_sequence'] >= second['start_sequence']):
                fifo_errors += 1
        first = min(t['created_at'] for t in turns)
        finished = all(t['status'] in ['succeeded','failed','cancelled'] and t['ended'] is not None for t in turns)
        succeeded = all(t['status']=='succeeded' for t in turns)
        last = max(t['ended'] for t in turns) if finished else None
        intervals.append((first,last))
        if last is not None and succeeded:
            latencies.append((last-first)/1000)
            successes.append(last)
    warmup = max(60, config.get('seconds',5)*2 if config['scenario']!='short' else 60)*1000
    steady_begin, steady_end = begin+warmup, end-20000
    seconds = max(1,(steady_end-steady_begin)/1000)
    offered = sum(steady_begin <= first < steady_end for first,_ in intervals)
    completed = sum(steady_begin <= last < steady_end for last in successes)
    turn_arrivals = sum(steady_begin <= t['created_at'] < steady_end for t in value['turns'])
    turn_completions = sum(t['status']=='succeeded' and t['ended'] is not None
                           and steady_begin <= t['ended'] < steady_end for t in value['turns'])
    turn_latencies = [(t['ended']-t['created_at'])/1000 for t in value['turns']
                      if t['status']=='succeeded' and t['ended'] is not None]
    peak = concurrent_peak(intervals, value['sampled_at']*1000)
    turn_peak = concurrent_peak([(t['created_at'],t['ended']) for t in value['turns']], value['sampled_at']*1000)
    active_samples, cpu, memory, pending, timeouts = [],defaultdict(float),defaultdict(float),defaultdict(float),defaultdict(list)
    pod_samples = defaultdict(lambda: defaultdict(list))
    restart_samples = defaultdict(list)
    trend = []
    for snapshot in observations:
        if not snapshot.get('success') or not begin <= snapshot['sampled_at']*1000 <= end:
            continue
        point = {'timestamp':snapshot['sampled_at'],'active_turns':0,'turns_by_status':{},'pods':{}}
        for m in snapshot['metrics']:
            name,labels,number=m['name'],m['labels'],m['value']
            if name=='ma_turns' and labels.get('scope')=='test' and labels.get('status') not in ['succeeded','failed','cancelled']:
                point['turns_by_status'][labels['status']] = number
                point['active_turns'] += number
            if name=='ma_sessions_active' and labels.get('scope')=='test':
                point['active_sessions'] = number
            if name=='ma_sessions_active' and labels.get('scope')=='test' and steady_begin <= snapshot['sampled_at']*1000 <= steady_end:
                active_samples.append((snapshot['sampled_at'],number))
            if name=='ma_pod_cpu' and labels.get('container'):
                cpu[labels.get('container','unknown')]=max(cpu[labels.get('container','unknown')],number)
            if name=='ma_pod_memory' and labels.get('container'):
                memory[labels.get('container','unknown')]=max(memory[labels.get('container','unknown')],number/1024**2)
            unit = {'ma_pod_cpu':'cpu_cores','ma_pod_memory':'memory_mib'}.get(name)
            if unit and labels.get('container'):
                key = labels['pod']+'/'+labels['container']
                amount = number/1024**2 if unit=='memory_mib' else number
                pod_samples[key][unit].append(amount)
                point['pods'].setdefault(key,{})[unit] = amount
            if name in ['ma_actuator_jvm_threads_live','ma_actuator_jvm_threads_peak',
                        'ma_actuator_hikaricp_connections_active','ma_actuator_hikaricp_connections_pending'] or name.startswith('ma_actuator_managed_agents_loadtest_model_'):
                key = labels['pod']+'/'+labels['service']
                unit = name.removeprefix('ma_actuator_')
                pod_samples[key][unit].append(number)
                point['pods'].setdefault(key,{})[unit] = number
            if name=='ma_actuator_hikaricp_connections_pending':
                pending[labels['service']]=max(pending[labels['service']],number)
            if name=='ma_actuator_hikaricp_connections_timeout':
                timeouts[labels['pod']].append(number)
            if name=='ma_pod_restarts':
                restart_samples[labels['pod']].append(number)
        trend.append(point)
    slope=None
    if len(active_samples)>=3:
        origin=active_samples[0][0]
        xs=[x-origin for x,_ in active_samples];ys=[y for _,y in active_samples]
        xm,ym=sum(xs)/len(xs),sum(ys)/len(ys)
        denominator=sum((x-xm)**2 for x in xs)
        slope=sum((x-xm)*(y-ym) for x,y in zip(xs,ys))/denominator if denominator else 0
    physical=defaultdict(list)
    for row in value['physical']:
        physical[row['marker']].append(row)
    tool_errors=0
    if config['scenario']!='short':
        for scope in value['scopes']:
            calls=physical.get('pts-'+scope['session_id'],[])
            if scope['scope_key'] not in sessions:
                # PTS can stop between Session creation and the next user.message request.
                # Such Sessions need no tool, but an actual execution would be unexpected.
                tool_errors += bool(calls)
                continue
            if len(calls)!=1 or calls[0]['state']!='succeeded':tool_errors+=1
    inputs={r['status']:r['count'] for r in value['inputs']}
    root_queue=[(w['starting']-w['received'])/1000 for w in value['worker']
                if w['starting'] is not None and w['received'] is not None]
    worker_intervals = [(w['starting'],w['captured']) for w in value['worker']
                        if w['starting'] is not None]
    physical_intervals = [(w['started'],w['ended']) for w in value['physical']
                          if w.get('started') is not None]
    outstanding=sum(last is None for _,last in intervals)
    failed=statuses.get('failed',0)+statuses.get('cancelled',0)
    total_turns=sum(statuses.values())
    valid_times = sorted(s['sampled_at']*1000 for s in observations if s.get('success')
                         and begin <= s['sampled_at']*1000 <= end)
    coverage_good = bool(valid_times and valid_times[0]-begin <= 30000
                         and end-valid_times[-1] <= 45000
                         and all(b-a <= 60000 for a,b in zip(valid_times,valid_times[1:])))
    usage_totals = defaultdict(float)
    steady_usage = defaultdict(float)
    model_calls = 0
    steady_model_calls = 0
    for turn in value['turns']:
        calls = int(turn.get('model_calls') or 0)
        model_calls += calls
        usage = turn.get('usage') or {}
        for name,number in usage.items():
            if isinstance(number,(float,int)):
                usage_totals[name] += number
        if turn['ended'] is not None and steady_begin <= turn['ended'] < steady_end:
            steady_model_calls += calls
            for name,number in usage.items():
                if isinstance(number,(float,int)):
                    steady_usage[name] += number
    usage_summary = {'committed_model_calls':model_calls,
        'calls_per_admitted_session':model_calls/max(1,len(sessions)),
        'total':dict(usage_totals),'mean_per_admitted_session':{k:v/max(1,len(sessions)) for k,v in usage_totals.items()},
        'steady_committed_calls_per_second':steady_model_calls/seconds,
        'steady_tokens_per_second':{k:v/seconds for k,v in steady_usage.items() if k.endswith('Tokens')}}
    output_by_turn = {(r['scope_key'],r['turn_id']):r for r in value.get('outputs',[])}
    output_errors = 0
    for turn in value['turns']:
        if turn['status']!='succeeded': continue
        row = output_by_turn.get((turn['scope_key'],turn['turn_id']),{})
        output_errors += not row.get('short_valid' if config['scenario']=='short' else 'tool_valid',False)
        if config['scenario']=='fifo' and turn['admission_sequence']==1:
            output_errors += not row.get('injection_valid',False)
    offered_good = offered > 0 and offered/seconds >= config.get('rps',1)*.95
    restart_increments = sum(max(v)-min(v) for v in restart_samples.values())
    acceptable=(not restart_increments and offered_good and not output_errors and coverage_good and end-begin >= 590000 and failed/max(1,total_turns)<=.001 and not outstanding and not fifo_errors and not unsealed
                and not tool_errors and completed >= offered*.98
                and (slope is None or slope<=max(.02,config.get('rps',1)*.05)))
    if config['scenario']=='fifo' and inputs.get('applied',0)!=len(sessions):acceptable=False
    return {'batch':config['batch'],'scenario':config['scenario'],'short_output':config.get('short_output','echo'),'tool_seconds':config.get('seconds'),
            'model_usage':usage_summary,'target_session_rps':config.get('rps'),'created_sessions':len(value['scopes']),
            'admitted_sessions':len(sessions),'turn_statuses':dict(statuses),'outstanding_sessions':outstanding,
            'created_without_admission':len(value['scopes'])-len(sessions),
            'offered_load_sufficient':offered_good,'steady_window_seconds':seconds,'steady_arrival_rps':offered/seconds,
            'steady_completion_rps':completed/seconds,'peak_active_sessions':peak,
            'steady_turn_arrival_rps':turn_arrivals/seconds,
            'steady_turn_completion_rps':turn_completions/seconds,
            'turn_end_to_end_seconds':distribution(turn_latencies),
            'peak_active_turns':turn_peak,
            'sampled_active_turns':distribution([p['active_turns'] for p in trend]),
            'sampled_turns_by_status':{status:distribution([p['turns_by_status'].get(status,0) for p in trend])
                                      for status in sorted({s for p in trend for s in p['turns_by_status']})},
            'pod_load':{pod:{key:distribution(samples) for key,samples in metrics.items()}
                        for pod,metrics in pod_samples.items()},'load_timeline':trend,
            'active_session_growth_per_second':slope,'end_to_end_seconds':distribution(latencies),
            'admission_queue_seconds':distribution(queue),'tool_worker_queue_seconds':distribution(root_queue),
            'peak_worker_root_executions':concurrent_peak(worker_intervals,value['sampled_at']*1000),
            'peak_physical_mcp_calls':concurrent_peak(physical_intervals,value['sampled_at']*1000),
            'output_validation_errors':output_errors,'fifo_violations':fifo_errors,'unsealed_successful_turns':unsealed,'physical_tool_errors':tool_errors,
            'input_statuses':inputs,'cpu_peak_cores_per_pod_by_service':dict(cpu),
            'memory_peak_mib_per_pod_by_service':dict(memory),'hikari_pending_peak':dict(pending),
            'pod_restart_increments':restart_increments,
            'hikari_timeout_increments':sum(max(v)-min(v) for v in timeouts.values()),
            'observer_samples':len(observations),'observer_coverage_sufficient':coverage_good,'business_failures':value['failures'],
            'capacity_candidate_passed':acceptable,'warmup_seconds':warmup/1000}


def run(args):
    retained = []
    if args.observer_url:
        try:
            with urllib.request.urlopen(args.observer_url.rstrip('/')+'/history',timeout=15) as response:
                retained = json.load(response)
            (args.stage/'observer-retained.jsonl').write_text(''.join(json.dumps(s)+'\n' for s in retained))
        except Exception:
            print('Retained observer history unavailable; coverage still requires complete actual samples')
    config_path=args.stage/'configuration.json'
    if config_path.exists():config=json.loads(config_path.read_text())
    else:
        private=json.loads((args.stage/'scene-private.json').read_text())
        config={'batch':private['SceneName'],'scenario':args.scenario,'seconds':args.seconds,'rps':args.rps}
    value=facts(args.kubeconfig,config['batch'])
    (args.stage/'durable-facts.json').write_text(json.dumps(value,indent=2))
    scene_id=(args.stage/'scene-id.txt').read_text()
    plan_id=json.loads((args.stage/'start.json').read_text())['PlanId']
    report=cloud('GetPtsReportDetails',SceneId=scene_id,PlanId=plan_id)
    (args.stage/'pts-report-private.json').write_text(json.dumps(report))
    listing=cloud('GetPtsReportsBySceneId',SceneId=scene_id,PageNumber=1,PageSize=10)
    (args.stage/'report-list.json').write_text(json.dumps(listing))
    metadata=next((v for v in listing.get('ReportOverViewList',[]) if v['ReportId']==plan_id),None)
    if not metadata:raise RuntimeError('PTS has not produced final report metadata yet')
    def stamp(key):
        return datetime.strptime(metadata[key],'%Y-%m-%d %H:%M:%S').replace(tzinfo=ZoneInfo('Asia/Shanghai')).timestamp()*1000
    observations=[]
    path=args.stage/'observer.jsonl'
    if path.exists():observations=[json.loads(line) for line in path.read_text().splitlines() if line]
    observations = list({s['sampled_at']:s for s in observations+retained}.values())
    result=reconcile(value,config,stamp('StartTime'),stamp('EndTime'),observations)
    resource_points = defaultdict(dict)
    resource_path = args.stage.parent/'observations.jsonl'
    if resource_path.exists():
        for line in resource_path.read_text().splitlines():
            record = json.loads(line)
            for item in record.get('cloud_resources',[]):
                if not item.get('success'): continue
                key = item['namespace']+':'+item['instance']+':'+item['metric']
                for point in item['datapoints']:
                    timestamp = point['timestamp']
                    if stamp('StartTime') <= timestamp <= stamp('EndTime'):
                        resource_points[key][timestamp] = point.get('Maximum',point.get('Average'))
    result['cloud_resource_percent'] = {key:{'samples':len(points),'max_one_minute':max(points.values())}
                                       for key,points in resource_points.items() if points}
    result['pts_report_id']=plan_id
    result['pts_scene_id']=scene_id
    result['http_metrics']=report.get('ApiMetricsList',[])
    result['primary_request'] = {'method':'POST','path':'/v1/sessions/{session_id}/events',
        'event_type':'user.message','intent':'new_turn'}
    result['user_message_admission_http_metrics'] = [m for m in result['http_metrics']
        if m.get('ApiName','').startswith('turn.admit')]
    http_good=all(v.get('AllCount',0)>0 and v.get('SuccessRateReq',0)>=.999 for v in result['http_metrics'])
    result['capacity_candidate_passed'] &= http_good
    runner_path = args.stage/'runner-status.json'
    if runner_path.exists():
        result['native_runner_status'] = json.loads(runner_path.read_text())
        result['capacity_candidate_passed'] &= result['native_runner_status'].get('finished',False)
    (args.stage/'analysis.json').write_text(json.dumps(result,indent=2,ensure_ascii=False))
    print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=='__main__':
    import os
    os.umask(0o077)
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stage',type=Path,required=True)
    p.add_argument('--kubeconfig',type=Path,required=True)
    p.add_argument('--scenario',choices=['short','tool','fifo'],default='short')
    p.add_argument('--seconds',type=int,default=5)
    p.add_argument('--rps',type=float,default=1)
    p.add_argument('--observer-url',default='http://127.0.0.1:19113',
                   help='Fetch bounded in-cluster history before reconciliation; empty disables it')
    run(p.parse_args())

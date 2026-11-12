#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""PTS native open-loop Session submission, paired with durable completion observation."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
from pts import cloud


def configuration(args):
    token = json.loads(args.token_file.read_text())['token']
    headers = [{'HeaderName': name, 'HeaderValue': value} for name, value in {
        'Authorization': 'Bearer '+token, 'Content-Type': 'application/json',
        'X-Managed-Agents-Tenant': 'default', 'X-Managed-Agents-Namespace': 'default',
        'Managed-Agents-Extensions': 'turn-inputs-v1'}.items()]
    def api(name, path, body, suffix=None, exports=None):
        value = {'ApiName': name, 'Method': 'POST', 'Url': args.base_url.rstrip('/')+path,
                 'TimeoutInSecond': 30, 'RedirectCountLimit': 0,
                 'HeaderList': headers+([{'HeaderName': 'Idempotency-Key', 'HeaderValue': '${session_id}'+suffix}] if suffix else []),
                 'Body': {'ContentType': 'application/json', 'BodyValue': json.dumps(body, ensure_ascii=False)}}
        if exports:
            value['ExportList'] = [{'ExportName': key, 'ExportType': 'BODY_JSON', 'ExportValue': path}
                                   for key, path in exports.items()]
        return value
    create = api('session.create', '/v1/sessions',
                 {'agent': args.agent, 'environment_id': args.environment, 'title': args.batch,
                  'metadata': {'load_test_batch': args.batch, 'load_test_scenario': args.scenario}},
                 exports={'session_id': 'id'})
    if getattr(args, 'session_create_url', None):
        create['Url'] = args.session_create_url
    prompt = '请不要调用任何工具，仅原样回复 PTS_OK。'
    if getattr(args,'short_output','marker') == 'echo':
        prompt = '请不要调用任何工具，仅原样回复 PTS_OK ${session_id}。'
    if args.scenario != 'short':
        prompt = ('只调用一次 longwait__long_wait，marker=pts-${session_id}，seconds='+str(args.seconds)+
                  '。等待真实结果后完整报告 marker、call_id。不要重复调用，不要伪造结果。')
    def event(text, extension):
        return {'events': [{'type': 'user.message', 'content': [{'type': 'text', 'text': text}], 'managed_agents': extension}]}
    first = api('turn.admit', '/v1/sessions/${session_id}/events', event(prompt, {'intent': 'new_turn'}),
                '-t1', {'turn_id': 'data[0].managed_agents.receipt.id'})
    apis = [create, first]
    session_file_url = getattr(args,'session_file_url',None)
    if session_file_url:
        apis = [first]
    if args.scenario == 'fifo':
        apis += [api('turn.admit.t2', '/v1/sessions/${session_id}/events',
                     event('不要调用任何工具，从上一轮真实结果报告 marker=pts-${session_id} 和 call_id。', {'intent': 'new_turn'}), '-t2'),
                 api('turn.inject', '/v1/sessions/${session_id}/events',
                     event('本轮最终答复包含 ${session_id}-injected。', {'intent': 'inject', 'turn_id': '${turn_id}'}), '-inject')]
    load = {'AgentCount': 1, 'MaxRunningTime': args.minutes, 'TestMode': 'tps_mode',
            'Configuration': {'AllRpsBegin': len(apis)*args.rps, 'AllRpsLimit': len(apis)*args.rps}}
    if args.vpc:
        if not args.vswitch or not args.security_group:
            raise ValueError('VPC stages require vswitch and security-group')
        load['VpcLoadConfig'] = {'RegionId': 'cn-hangzhou', 'VpcId': args.vpc,
                                 'VSwitchId': args.vswitch, 'SecurityGroupId': args.security_group}
    value = {'SceneName': args.batch, 'LoadConfig': load,
            'AdvanceSetting': {'ConnectionTimeoutInSecond': 10, 'LogRate': 1},
            'RelationList': [{'RelationName': args.scenario+'-session', 'ApiList': apis}]}
    if session_file_url:
        filename = getattr(args,'session_file_name','sessions.csv')
        value['FileParameterList'] = [{'FileName':filename,'FileOssAddress':session_file_url}]
        value['RelationList'][0]['FileParameterExplainList'] = [
            {'FileName':filename,'FileParamName':'session_id','BaseFile':True,'CycleOnce':True}]
    return value


def run(args):
    os.umask(0o077)
    args.out.mkdir(parents=True, exist_ok=False)
    value = configuration(args)
    (args.out/'scene-private.json').write_text(json.dumps(value, ensure_ascii=False))
    (args.out/'configuration.json').write_text(json.dumps({
        'batch': args.batch, 'scenario': args.scenario, 'seconds': args.seconds,
        'rps': args.rps, 'minutes': args.minutes, 'base_url': args.base_url,
        'short_output': getattr(args,'short_output','marker'),
        'session_create_url':getattr(args,'session_create_url',None),
        'companion_url':getattr(args,'companion_url',None),
        'business_failure_stop_rate':getattr(args,'business_failure_stop_rate',.001),
        'precreated_sessions':bool(getattr(args,'session_file_url',None))}, indent=2))
    saved = cloud('SavePtsScene', Scene=value)
    scene_id = saved['SceneId']
    (args.out/'scene-id.txt').write_text(scene_id)
    started = cloud('StartPtsScene', SceneId=scene_id)
    (args.out/'start.json').write_text(json.dumps(started))
    plan_id = started['PlanId']
    print(json.dumps({'scene': scene_id, 'plan': plan_id, 'target_session_rps': args.rps}), flush=True)
    previous = None
    baseline_business = None
    missing_observations = 0
    baseline_deadlocks = None
    baseline_restarts = None
    deadline = time.monotonic()+args.minutes*60+900
    try:
        while time.monotonic() < deadline:
            status = cloud('GetPtsSceneRunningStatus', SceneId=scene_id)
            data = cloud('GetPtsSceneRunningData', SceneId=scene_id, PlanId=plan_id)
            with (args.out/'timeline.jsonl').open('a') as stream:
                stream.write(json.dumps({'timestamp': time.time(), 'status': status, 'data': data})+'\n')
            if status.get('Status') != previous:
                print(json.dumps({'status': status, 'data': data}), flush=True)
                previous = status.get('Status')
            if args.observer_url:
                try:
                    with urllib.request.urlopen(args.observer_url+'/snapshot', timeout=10) as response:
                        observation = json.load(response)
                    if not observation.get('success') or time.time()-observation['sampled_at'] > 60:
                        raise RuntimeError('Collector has no fresh successful sample')
                    deadlocks = next((m['value'] for m in observation['metrics'] if m['name']=='ma_pg_deadlocks_total'), None)
                    if baseline_deadlocks is None:
                        baseline_deadlocks = deadlocks
                    elif deadlocks is not None and deadlocks > baseline_deadlocks:
                        raise AssertionError('Database deadlock counter increased during stage')
                    restarts = {m['labels']['pod']:m['value'] for m in observation['metrics']
                                if m['name']=='ma_pod_restarts'}
                    if baseline_restarts is None:
                        baseline_restarts = restarts
                    elif any(count>baseline_restarts.get(pod,0) for pod,count in restarts.items()):
                        raise AssertionError('Pod restart counter increased during stage')
                    counts = {m['labels'].get('status'): m['value'] for m in observation['metrics']
                              if m['name']=='ma_turns' and m['labels'].get('scope')=='test'}
                    if baseline_business is None:
                        baseline_business = counts
                    failed = (counts.get('failed',0)+counts.get('cancelled',0)
                              -baseline_business.get('failed',0)-baseline_business.get('cancelled',0))
                    succeeded = counts.get('succeeded',0)-baseline_business.get('succeeded',0)
                    failure_limit = getattr(args,'business_failure_stop_rate',.001)
                    minimum = 100 if failure_limit>.001 else 20
                    if failed+succeeded >= minimum and failed/(failed+succeeded) > failure_limit:
                        raise AssertionError('Business failure rate exceeded '+str(failure_limit*100)+'%')
                    oldest = [m['value'] for m in observation['metrics']
                              if m['name']=='ma_turn_oldest_seconds' and m['labels'].get('scope')=='test'
                              and m['labels'].get('status') not in ['succeeded','failed','cancelled']]
                    if oldest and max(oldest) > max(180, args.seconds*2+60):
                        raise AssertionError('Accepted work exceeded stage age limit')
                    missing_observations = 0
                    with (args.out/'observer.jsonl').open('a') as stream:
                        stream.write(json.dumps(observation)+'\n')
                except AssertionError:
                    raise
                except Exception:
                    missing_observations += 1
                    if status.get('Status')=='RUNNING' and missing_observations >= 3:
                        raise RuntimeError('Missing telemetry during live load')
                    print('Observer temporarily unavailable', flush=True)
            if data.get('TotalRequestCount', 0) >= 20 and data.get('FailedRequestCount', 0)/data['TotalRequestCount'] > 0.01:
                raise RuntimeError('HTTP failure rate exceeded 1%; stop increasing load')
            if getattr(args,'companion_url',None):
                with urllib.request.urlopen(args.companion_url.rstrip('/')+'/status',timeout=10) as response:
                    companion = json.load(response)
                with (args.out/'sse-companion.jsonl').open('a') as stream:
                    stream.write(json.dumps(companion)+'\n')
                if companion.get('errors') or companion.get('duplicates'):
                    raise RuntimeError('SSE companion observed connection/event delivery failure')
            if status.get('Status') in ['STOPPED', 'FINISHED', 'ERROR', 'FAILED']:
                break
            time.sleep(15)
        else:
            raise RuntimeError('PTS stage exceeded bounded deadline')
    except BaseException as error:
        (args.out/'runner-status.json').write_text(json.dumps({'finished':False,
            'error_type':type(error).__name__,'reason':str(error)[:240]}))
        raise
    else:
        (args.out/'runner-status.json').write_text(json.dumps({'finished':True}))
    finally:
        try:
            cloud('StopPtsScene', SceneId=scene_id)
        except RuntimeError:
            pass
    print('Native PTS stage ended; durable completion facts still require review', flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', required=True)
    p.add_argument('--agent', required=True)
    p.add_argument('--environment', required=True)
    p.add_argument('--token-file', type=Path, required=True)
    p.add_argument('--batch', required=True)
    p.add_argument('--scenario', choices=['short', 'tool', 'fifo'], default='short')
    p.add_argument('--short-output',choices=['marker','echo'],default='marker')
    p.add_argument('--seconds', type=int, choices=range(1, 181), default=5)
    p.add_argument('--rps', type=int, choices=range(1, 101), default=1)
    p.add_argument('--minutes', type=int, choices=range(1, 61), default=10)
    p.add_argument('--vpc')
    p.add_argument('--vswitch')
    p.add_argument('--security-group')
    p.add_argument('--out', required=True, type=Path)
    p.add_argument('--observer-url')
    p.add_argument('--session-create-url',help='Optional preparation proxy; user.message still targets base-url directly')
    p.add_argument('--companion-url',help='Operator-reachable SSE companion health/status URL; stop on failure')
    p.add_argument('--business-failure-stop-rate',type=float,default=.001,
                   help='Operational abort threshold; durable acceptance remains 0.1%% independently')
    p.add_argument('--session-file-url',help='PTS-readable no-header CSV of precreated Session IDs; consume each row once')
    p.add_argument('--session-file-name',default='sessions.csv')
    args = p.parse_args()
    if not 0 < args.business_failure_stop_rate <= .05:
        p.error('Business failure abort rate must be positive and at most 5%')
    run(args)

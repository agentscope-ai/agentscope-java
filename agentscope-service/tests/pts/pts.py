#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Create and execute a bounded PTS JMeter Session stage; preserve private auth locally."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid


def cloud(action, **params):
    command = ['aliyun', 'pts', action, '--region', 'cn-hangzhou']
    for key, value in params.items():
        command += ['--'+key, json.dumps(value) if isinstance(value, (dict, list)) else str(value)]
    attempts = 3 if action.startswith(('Get','List')) else 1
    for attempt in range(attempts):
        try:
            response = subprocess.run(command,capture_output=True,text=True,timeout=60)
            if response.returncode==0:
                break
        except subprocess.TimeoutExpired:
            response = None
        if attempt+1==attempts:
            raise RuntimeError('PTS CLI failed: '+action)
        time.sleep(attempt+1)
    value = json.loads(response.stdout)
    if not value.get('Success'):
        raise RuntimeError('PTS '+action+': '+str(value.get('Code'))+' '+str(value.get('Message')))
    return value


def scene(args):
    token = json.loads(args.token_file.read_text())['token']
    props = {'baseUrl': args.base_url, 'authToken': token, 'agentId': args.agent,
             'environmentId': args.environment, 'scenario': args.scenario, 'toolSeconds': str(args.seconds),
             'batch': args.batch, 'threads': str(args.threads), 'durationSeconds': str(args.duration),
             'rampSeconds': str(args.ramp), 'subresults.disable_renaming': 'true', 'deadlineSeconds': '300', 'pollMillis': '3000'}
    return {'SceneName': args.batch, 'TestFile': args.file_name,
            'FileList': [{'FileName': args.file_name, 'FileOssAddress': args.jmx_url}],
            'JMeterProperties': [{'Name': key, 'Value': value} for key, value in props.items()],
            'Mode': 'CONCURRENCY', 'Concurrency': args.threads, 'StartConcurrency': 1,
            'AgentCount': 1, 'Duration': args.duration, 'RampUp': args.ramp, 'Steps': min(5, args.threads),
            'RegionalCondition': [{'Region': 'cn-hangzhou', 'Amount': 1}], 'IsVpcTest': False}


def run(args):
    os.umask(0o077)
    if args.seconds is None:
        args.seconds = 30 if args.scenario=='fifo' else 5
    args.out.mkdir(parents=True, exist_ok=False)
    config = scene(args)
    (args.out/'scene-private.json').write_text(json.dumps(config))
    saved = cloud('SaveOpenJMeterScene', OpenJMeterScene=config)
    scene_id = saved['SceneId']
    (args.out/'scene-id.txt').write_text(scene_id)
    detail = cloud('GetOpenJMeterScene', SceneId=scene_id)
    if not detail.get('Scene', {}).get('FileList'):
        raise RuntimeError('PTS did not import JMX; no load was started')
    (args.out/'configuration.json').write_text(json.dumps({key: value for key, value in vars(args).items()
                                                       if key not in ['token_file', 'jmx_url']}, default=str, indent=2))
    started = cloud('StartTestingJMeterScene', SceneId=scene_id)
    (args.out/'start.json').write_text(json.dumps(started))
    print(json.dumps({'scene': scene_id, 'start': started}), flush=True)
    previous = None
    finished = False
    try:
        deadline = time.monotonic()+args.duration+900
        while time.monotonic() < deadline:
            value = cloud('GetJMeterSceneRunningData', SceneId=scene_id)
            (args.out/'running.json').write_text(json.dumps(value))
            with (args.out/'timeline.jsonl').open('a') as stream:
                stream.write(json.dumps({'timestamp': time.time(), **value})+'\n')
            data = value.get('RunningData', {})
            if not data and previous is not None:
                raise RuntimeError('PTS engine disappeared without a terminal state; inspect final report before accepting results')
            state = data.get('Status')
            if state != previous:
                print(json.dumps({'scene': scene_id, 'state': state, 'data': data}), flush=True)
                previous = state
            if data.get('HasError'):
                raise RuntimeError('PTS engine reported an error')
            if state in ['FINISHED', 'STOPPED', 'TEST_FINISHED', 'FINISH', 'END', 'ENDED']:
                finished = True
                break
            if args.observer_url:
                try:
                    with urllib.request.urlopen(args.observer_url+'/snapshot', timeout=10) as response:
                        snapshot = json.load(response)
                    with (args.out/'observer.jsonl').open('a') as stream:
                        stream.write(json.dumps(snapshot)+'\n')
                except Exception:
                    print('Observer endpoint temporarily unavailable', flush=True)
            time.sleep(15)
        else:
            raise RuntimeError('Stage exceeded its bounded deadline')
    finally:
        if not finished:
            cloud('StopTestingJMeterScene', SceneId=scene_id)
    reports = cloud('ListJMeterReports', PageNumber=1, PageSize=10, SceneId=scene_id)
    (args.out/'reports.json').write_text(json.dumps(reports))
    print('Stage ended; report metadata saved', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--environment', required=True)
    parser.add_argument('--token-file', required=True, type=Path)
    parser.add_argument('--jmx-url', required=True)
    parser.add_argument('--file-name', default='sessions-v2.jmx')
    parser.add_argument('--scenario', choices=['short', 'tool', 'fifo'], default='short')
    parser.add_argument('--seconds', type=int, choices=range(1, 181),
                        help='Tool duration; defaults to 30 for FIFO suspension/injection, 5 otherwise')
    parser.add_argument('--threads', type=int, choices=range(1, 513), default=1)
    parser.add_argument('--duration', type=int, default=600)
    parser.add_argument('--ramp', type=int, default=30)
    parser.add_argument('--batch', default='pts-'+uuid.uuid4().hex[:12])
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--observer-url')
    run(parser.parse_args())

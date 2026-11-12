#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Bounded sequential PTS stages; a failed guard or reconciliation prevents promotion."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
from pts import cloud
from capture_cluster import run as capture


def drain(url, timeout=300):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        with urllib.request.urlopen(url+'/snapshot', timeout=10) as response:
            value = json.load(response)
        if not value.get('success') or time.time()-value['sampled_at']>60:
            raise RuntimeError('Cannot drain without fresh successful telemetry')
        active = next((m['value'] for m in value['metrics'] if m['name']=='ma_sessions_active'
                       and m['labels'].get('scope')=='test'), None)
        if active is None:
            raise RuntimeError('Test Session gauge is missing')
        if active==0:
            return
        time.sleep(15)
    raise RuntimeError('Accepted test Sessions did not drain within the bounded wait')


def wait_for_report(stage):
    scene = (stage/'scene-id.txt').read_text()
    plan = json.loads((stage/'start.json').read_text())['PlanId']
    deadline = time.monotonic()+180
    while time.monotonic() < deadline:
        report = cloud('GetPtsReportsBySceneId',SceneId=scene,PageNumber=1,PageSize=10)
        if any(r['ReportId']==plan for r in report.get('ReportOverViewList',[])):
            return
        time.sleep(10)
    raise RuntimeError('PTS final report metadata is not available; no promotion')


def run_stage(args, rate):
    scripts = Path(__file__).resolve().parent
    name = args.prefix+'-'+args.scenario+'-'+str(rate)
    if len(name)>30:
        raise ValueError('Stage name exceeds 30 characters')
    stage = args.out/name
    if stage.exists():
        raise RuntimeError('Stage already exists; inspect its actual state before rerunning')
    drain(args.observer_url)
    before = args.out/(name+'-cluster-before.json')
    capture(argparse.Namespace(kubeconfig=args.kubeconfig,namespace='agencore-ma',out=before))
    command = [sys.executable,str(scripts/'native.py'),'--base-url',args.base_url,
        '--agent',args.agent,'--environment',args.environment,'--token-file',str(args.token_file),
        '--batch',name,'--scenario',args.scenario,'--short-output',args.short_output,
        '--seconds',str(args.seconds),'--rps',str(rate),'--minutes',str(args.minutes),
        '--observer-url',args.observer_url,'--out',str(stage)]
    if args.vpc:
        command += ['--vpc',args.vpc,'--vswitch',args.vswitch,'--security-group',args.security_group]
    if args.session_create_url:
        command += ['--session-create-url',args.session_create_url]
    if args.companion_url:
        command += ['--companion-url',args.companion_url]
    command += ['--business-failure-stop-rate',str(args.business_failure_stop_rate)]
    print(json.dumps({'stage':name,'target_session_rps':rate,'event':'starting'}),flush=True)
    with (args.out/(name+'.log')).open('w') as output:
        result = subprocess.run(command,stdout=output,stderr=output,timeout=args.minutes*60+1000)
    drain(args.observer_url,max(300,args.seconds*3))
    wait_for_report(stage)
    with (stage/'analysis.log').open('w') as output:
        subprocess.run([sys.executable,str(scripts/'analyze.py'),'--stage',str(stage),
                        '--kubeconfig',str(args.kubeconfig)],stdout=output,stderr=output,
                        check=True,timeout=1800)
    capture(argparse.Namespace(kubeconfig=args.kubeconfig,namespace='agencore-ma',out=stage/'cluster-after.json'))
    summary = json.loads((stage/'analysis.json').read_text())
    summary['native_runner_exit'] = result.returncode
    summary['stage'] = str(stage)
    if result.returncode:
        summary['capacity_candidate_passed'] = False
    (stage/'analysis.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False))
    return summary


def run(args):
    os.umask(0o077)
    args.out.mkdir(parents=True,exist_ok=True)
    records = []
    deadline = time.monotonic()+args.max_total_minutes*60
    manifest = args.out/(args.prefix+'-manifest.json')
    if manifest.exists():
        raise RuntimeError('Ladder manifest already exists; refusing to overwrite evidence')
    for rate in args.rates:
        if time.monotonic()>=deadline:
            raise RuntimeError('Ladder wall-clock budget exhausted')
        summary = run_stage(args,rate)
        records.append(summary)
        manifest.write_text(json.dumps(records,indent=2,ensure_ascii=False))
        print(json.dumps({k:summary.get(k) for k in ['batch','capacity_candidate_passed',
            'steady_completion_rps','peak_active_sessions','turn_statuses','output_validation_errors']},ensure_ascii=False),flush=True)
        if not summary.get('capacity_candidate_passed'):
            raise RuntimeError('Stage did not pass; inspect bottleneck before any further increase')


if __name__=='__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rates',type=lambda value:[int(v) for v in value.split(',')],required=True)
    p.add_argument('--prefix',required=True)
    p.add_argument('--base-url',required=True)
    p.add_argument('--agent',required=True)
    p.add_argument('--environment',required=True)
    p.add_argument('--token-file',type=Path,required=True)
    p.add_argument('--kubeconfig',type=Path,required=True)
    p.add_argument('--observer-url',required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--scenario',choices=['short','tool','fifo'],default='short')
    p.add_argument('--short-output',choices=['marker','echo'],default='echo')
    p.add_argument('--seconds',type=int,choices=range(1,181),default=5)
    p.add_argument('--minutes',type=int,choices=range(10,61),default=10)
    p.add_argument('--max-total-minutes',type=int,default=180)
    p.add_argument('--vpc')
    p.add_argument('--vswitch')
    p.add_argument('--security-group')
    p.add_argument('--session-create-url')
    p.add_argument('--companion-url')
    p.add_argument('--business-failure-stop-rate',type=float,default=.001)
    args = p.parse_args()
    if any(rate<1 or rate>100 for rate in args.rates) or args.rates!=sorted(set(args.rates)):
        p.error('Rates must be increasing unique integers from 1 to 100')
    run(args)

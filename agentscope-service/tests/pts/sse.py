#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Bounded SSE companion: establish streams before PTS admits precreated Sessions."""
import argparse
import asyncio
from collections import Counter
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import resource
import time
import httpx


def event_frame(lines):
    """Parse one SSE frame, preserving multi-line data and ignoring heartbeat comments."""
    data = [line[5:].removeprefix(' ') for line in lines if line.startswith('data:')]
    if not data:
        return None
    value = json.loads('\n'.join(data))
    if not isinstance(value,dict) or not isinstance(value.get('type'),str) or not value.get('id'):
        raise ValueError('SSE data is not a protocol event')
    digest = hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    return {'event_id':value.get('id'),'type':value.get('type'),
            'processed_at':value.get('processed_at'),'payload_sha256':digest}


def delay_seconds(processed_at, received_at):
    if not processed_at:
        return None
    return received_at-datetime.fromisoformat(processed_at.replace('Z','+00:00')).timestamp()


async def run(args):
    args.out.mkdir(parents=True,exist_ok=False)
    sessions = [line.strip() for line in args.sessions.read_text().splitlines() if line.strip()]
    if not sessions or len(sessions)!=len(set(sessions)) or len(sessions)>args.max_streams:
        raise ValueError('Require unique Session IDs within the explicit stream limit')
    soft,hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    needed = len(sessions)+256
    if soft!=resource.RLIM_INFINITY and soft<needed:
        if hard!=resource.RLIM_INFINITY and hard<needed:
            raise RuntimeError('SSE companion file-descriptor hard limit is insufficient')
        resource.setrlimit(resource.RLIMIT_NOFILE,(needed,hard))
    token = os.environ.get('PTS_TOKEN')
    if args.token_file:
        token = json.loads(args.token_file.read_text())['token']
    if not token:
        raise ValueError('Provide token-file or PTS_TOKEN')
    headers = {'Authorization':'Bearer '+token,'Accept':'text/event-stream',
        'X-Managed-Agents-Tenant':'default','X-Managed-Agents-Namespace':'default',
        'Managed-Agents-Extensions':'turn-inputs-v1'}
    states = {sid:{'connected':False,'open':False,'events':0,'terminals':0,'duplicates':0} for sid in sessions}
    counts = Counter()
    slots = asyncio.Semaphore(args.connect_concurrency)
    stopping = asyncio.Event()
    started = time.monotonic()
    def record(value):
        with (args.out/'events.jsonl').open('a') as output:
            output.write(json.dumps(value)+'\n')
    async def subscribe(client,sid):
        state = states[sid]
        seen = set()
        response = None
        try:
            async with slots:
                response = await client.send(client.build_request('GET',args.base_url.rstrip('/')+
                    '/v1/sessions/'+sid+'/events/stream',headers=headers),stream=True)
                if response.status_code!=200 or 'text/event-stream' not in response.headers.get('content-type',''):
                    await response.aclose()
                    raise RuntimeError('SSE HTTP '+str(response.status_code))
                # HTTP 200 alone does not establish the captured durable boundary. Wait for :connected.
                lines = response.aiter_lines()
                async with asyncio.timeout(15):
                    async for line in lines:
                        if line.startswith(':') and line[1:].strip()=='connected':
                            break
                    else:
                        raise RuntimeError('Missing connected frame')
                state.update(connected=True,open=True,connected_at=time.time())
                record({'session_id':sid,'connected_at':state['connected_at']})
            try:
                frame = []
                async for line in lines:
                    if line:
                        frame.append(line)
                        continue
                    event = event_frame(frame)
                    frame = []
                    if event is None:
                        continue
                    now = time.time()
                    state['events'] += 1
                    counts[event['type']] += 1
                    eid = event['event_id']
                    if eid and eid in seen:
                        state['duplicates'] += 1
                    if eid:
                        seen.add(eid)
                    state.setdefault('first_event_at',now)
                    if event['type'] in ['managed_agents.turn.completed','managed_agents.turn.failed','managed_agents.turn.cancelled']:
                        state['terminals'] += 1
                    record({'session_id':sid,'received_at':now,**event,
                            'delivery_delay_seconds':delay_seconds(event['processed_at'],now)})
                if not stopping.is_set():
                    raise RuntimeError('Unexpected stream EOF')
            finally:
                await response.aclose()
        except asyncio.CancelledError:
            raise
        except Exception as error:
            state['error'] = type(error).__name__
            record({'session_id':sid,'error':type(error).__name__,'at':time.time()})
        finally:
            state['open'] = False
            if response is not None:
                await response.aclose()
    timeout = httpx.Timeout(connect=10,read=35,write=10,pool=15)
    limits = httpx.Limits(max_connections=args.max_streams,max_keepalive_connections=args.max_streams)
    async with httpx.AsyncClient(timeout=timeout,limits=limits,trust_env=False) as client:
        tasks = [asyncio.create_task(subscribe(client,sid)) for sid in sessions]
        try:
            while time.monotonic()-started < args.duration:
                connected = sum(s['connected'] for s in states.values())
                opened = sum(s['open'] for s in states.values())
                errors = sum('error' in s for s in states.values())
                summary = {'sampled_at':time.time(),'sessions':len(sessions),'connected':connected,
                    'open':opened,'errors':errors,'event_types':dict(counts),'states':states,
                    'file_descriptor_limit':resource.getrlimit(resource.RLIMIT_NOFILE)[0]}
                (args.out/'summary.json').write_text(json.dumps(summary))
                with (args.out/'timeline.jsonl').open('a') as output:
                    output.write(json.dumps({k:v for k,v in summary.items() if k!='states'})+'\n')
                if errors:
                    (args.out/'ready.json').unlink(missing_ok=True)
                    raise RuntimeError('SSE connection/delivery failed; inspect private event records')
                if connected==len(sessions) and opened==len(sessions):
                    if not (args.out/'ready.json').exists():
                        (args.out/'ready.json').write_text(json.dumps({'ready_at':time.time(),'streams':opened}))
                elif time.monotonic()-started>args.ready_timeout:
                    raise RuntimeError('Not all streams reached the connected boundary before deadline')
                await asyncio.sleep(5)
        finally:
            stopping.set()
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks,return_exceptions=True)


if __name__=='__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--sessions',type=Path,required=True,help='No-header CSV: one Session ID per line')
    parser.add_argument('--token-file',type=Path)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--max-streams',type=int,default=2000)
    parser.add_argument('--connect-concurrency',type=int,default=32)
    parser.add_argument('--ready-timeout',type=int,default=300)
    parser.add_argument('--duration',type=int,default=1800)
    asyncio.run(run(parser.parse_args()))

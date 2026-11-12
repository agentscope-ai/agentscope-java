#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Prepare an isolated Session CSV before a user.message-only PTS comparison."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import time
import uuid
import httpx


async def run(args):
    args.out.mkdir(parents=True,exist_ok=False)
    token = os.environ.get('PTS_TOKEN')
    if args.token_file:
        token = json.loads(args.token_file.read_text())['token']
    if not token:
        raise ValueError('Provide token-file or PTS_TOKEN')
    headers = {'Authorization':'Bearer '+token,'Content-Type':'application/json',
        'X-Managed-Agents-Tenant':'default','X-Managed-Agents-Namespace':'default',
        'Managed-Agents-Extensions':'turn-inputs-v1'}
    slots = asyncio.Semaphore(args.concurrency)
    ids = []
    started = time.time()
    async def create(client,index):
        async with slots:
            response = await client.post(args.base_url.rstrip('/')+'/v1/sessions',headers={
                **headers,'Idempotency-Key':str(uuid.uuid4())},json={
                'agent':args.agent,'environment_id':args.environment,'title':args.batch,
                'metadata':{'load_test_batch':args.batch,'load_test_scenario':args.scenario}})
            response.raise_for_status()
            sid = response.json()['id']
            ids.append(sid)
            with (args.out/'sessions.csv').open('a') as output:
                output.write(sid+'\n')
    async with httpx.AsyncClient(timeout=30,trust_env=False) as client:
        await asyncio.gather(*(create(client,index) for index in range(args.count)))
    if len(set(ids))!=args.count:
        raise RuntimeError('Session preparation did not produce unique IDs')
    (args.out/'preparation.json').write_text(json.dumps({'batch':args.batch,'sessions':len(ids),
        'started_at':started,'finished_at':time.time(),'scenario':args.scenario,
        'note':'Session creation is preparation, excluded from subsequent user.message-only PTS load.'}))
    print(json.dumps({'prepared_sessions':len(ids),'batch':args.batch}),flush=True)


if __name__=='__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--agent',required=True)
    parser.add_argument('--environment',required=True)
    parser.add_argument('--batch',required=True)
    parser.add_argument('--token-file',type=Path)
    parser.add_argument('--count',type=int,choices=range(1,20001),required=True)
    parser.add_argument('--concurrency',type=int,choices=range(1,65),default=8)
    parser.add_argument('--scenario',choices=['short','tool','fifo'],default='short')
    parser.add_argument('--out',type=Path,required=True)
    asyncio.run(run(parser.parse_args()))

#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Private PTS preparation proxy: subscribe before releasing each newly created Session."""
import asyncio
from collections import Counter
from contextlib import asynccontextmanager
import json
import os
from pathlib import Path
import time
import httpx
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse, Response
from starlette.routing import Route
from sse import event_frame, delay_seconds


class Bridge:
    def __init__(self, base_url, out, max_streams=1000, transport=None):
        self.base_url, self.out, self.max_streams = base_url.rstrip('/'), out, max_streams
        out.mkdir(parents=True, exist_ok=True)
        self.client = httpx.AsyncClient(transport=transport, trust_env=False,
            timeout=httpx.Timeout(connect=10, read=35, write=10, pool=10),
            limits=httpx.Limits(max_connections=max_streams+64, max_keepalive_connections=64))
        self.states, self.tasks, self.counts = {}, set(), Counter()
        self.closing = False

    def record(self, value):
        with (self.out/'events.jsonl').open('a') as output:
            output.write(json.dumps(value)+'\n')

    def summary(self):
        values = list(self.states.values())
        return {'sampled_at':time.time(), 'sessions':len(values),
            'connected':sum(s['connected'] for s in values),
            'open':sum(s['open'] for s in values),
            'errors':sum('error' in s for s in values),
            'completed_streams':sum(s.get('complete',False) for s in values),
            'duplicates':sum(s['duplicates'] for s in values), 'event_types':dict(self.counts)}

    async def subscribe(self, sid, headers, ready):
        state = self.states[sid]
        seen = set()
        try:
            async with self.client.stream('GET',self.base_url+'/v1/sessions/'+sid+'/events/stream',
                    headers={**headers,'Accept':'text/event-stream'}) as response:
                response.raise_for_status()
                if 'text/event-stream' not in response.headers.get('content-type',''):
                    raise RuntimeError('Not an SSE response')
                lines = response.aiter_lines()
                async with asyncio.timeout(15):
                    async for line in lines:
                        if line.startswith(':') and line[1:].strip()=='connected':
                            break
                    else:
                        raise RuntimeError('Missing connected frame')
                state.update(connected=True,open=True)
                self.record({'session_id':sid,'connected_at':time.time()})
                ready.set_result(True)
                frame, terminal = [], False
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
                    self.counts[event['type']] += 1
                    if event['event_id'] in seen:
                        state['duplicates'] += 1
                    seen.add(event['event_id'])
                    state['received'][event['event_id']] = event['payload_sha256']
                    self.record({'session_id':sid,'received_at':now,**event,
                        'delivery_delay_seconds':delay_seconds(event['processed_at'],now)})
                    if event['type'] in ['managed_agents.turn.completed','managed_agents.turn.failed',
                                         'managed_agents.turn.cancelled']:
                        terminal = True
                        state['terminal'] = event['type']
                    # This durable frame follows the terminal and sealed output events.
                    if terminal and event['type']=='session.status_idle':
                        state['complete'] = True
                        break
                if not state.get('complete') and not self.closing:
                    raise RuntimeError('Unexpected SSE EOF')
        except asyncio.CancelledError:
            raise
        except Exception as error:
            state['error'] = type(error).__name__
            self.record({'session_id':sid,'error':type(error).__name__,'at':time.time()})
            if not ready.done():
                ready.set_exception(RuntimeError('SSE subscription failed'))
        finally:
            state['open'] = False

    async def create(self, request: Request):
        headers = {name:value for name,value in request.headers.items() if name.lower() in
            ['authorization','content-type','idempotency-key','x-managed-agents-tenant',
             'x-managed-agents-namespace','managed-agents-extensions']}
        if 'authorization' not in headers:
            return JSONResponse({'error':'authorization required'},status_code=401)
        mode = request.path_params['mode']
        if mode not in ['baseline','sse']:
            return JSONResponse({'error':'unknown preparation mode'},status_code=404)
        if mode=='sse' and sum(s['open'] for s in self.states.values())>=self.max_streams:
            return JSONResponse({'error':'companion stream capacity'},status_code=503)
        result = await self.client.post(self.base_url+'/v1/sessions',headers=headers,
            content=await request.body())
        if result.is_success and mode=='sse':
            sid = result.json()['id']
            if sid in self.states:
                return JSONResponse({'error':'duplicate prepared Session'},status_code=409)
            self.states[sid] = {'connected':False,'open':False,'events':0,'duplicates':0,'received':{}}
            ready = asyncio.get_running_loop().create_future()
            task = asyncio.create_task(self.subscribe(sid,headers,ready))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
            try:
                await asyncio.wait_for(asyncio.shield(ready),20)
            except Exception:
                return JSONResponse({'error':'SSE not ready; do not admit user.message'},status_code=502)
        return Response(result.content,status_code=result.status_code,media_type='application/json')

    async def persist(self):
        while True:
            value = self.summary()
            (self.out/'summary.json').write_text(json.dumps(value))
            with (self.out/'timeline.jsonl').open('a') as output:
                output.write(json.dumps(value)+'\n')
            await asyncio.sleep(5)

    async def close(self):
        self.closing = True
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks,return_exceptions=True)
        (self.out/'states.json').write_text(json.dumps(self.states))
        await self.client.aclose()


def application(bridge):
    @asynccontextmanager
    async def lifespan(app):
        writer = asyncio.create_task(bridge.persist())
        try:
            yield
        finally:
            writer.cancel()
            await asyncio.gather(writer,return_exceptions=True)
            await bridge.close()
    async def status(request):
        return JSONResponse(bridge.summary())
    async def facts(request):
        ids = await request.json()
        if not isinstance(ids,list) or len(ids)>100:
            return JSONResponse({'error':'bounded Session list required'},status_code=400)
        return JSONResponse({sid:bridge.states.get(sid) for sid in ids})
    async def artifact(request):
        archive = bridge.out/'evidence.tar.gz'
        if not archive.exists():
            return JSONResponse({'error':'evidence archive not prepared'},status_code=404)
        return FileResponse(archive,media_type='application/gzip')
    return Starlette(routes=[Route('/{mode:str}/v1/sessions',bridge.create,methods=['POST']),
        Route('/status',status),Route('/facts',facts,methods=['POST']),
        Route('/artifact',artifact)],lifespan=lifespan)


if __name__=='__main__':
    import uvicorn
    os.umask(0o077)
    # Bind only inside the test VPC. No public load balancer or credentials in files/logs.
    bridge = Bridge(os.environ['BASE_URL'],Path(os.environ.get('OUT','/results')))
    uvicorn.run(application(bridge),host='0.0.0.0',port=8080,access_log=False)

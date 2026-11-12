#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Fixed in-flight business Sessions. Real user.message, durable terminal observation, real MCP."""
import argparse
import asyncio
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import signal
import time
import uuid
import httpx
import psycopg
from psycopg.rows import dict_row

TERMINAL = {'succeeded','failed','cancelled'}


def scope_key(session):
    values = ['default','default',session]
    identity = ''.join(str(len(v))+':'+v for v in values)
    return base64.urlsafe_b64encode(hashlib.sha256(identity.encode()).digest()).decode().rstrip('=')


class ClosedLoop:
    def __init__(self, args, token, client=None):
        self.args = args
        self.out = args.out
        self.out.mkdir(parents=True,exist_ok=False)
        self.stop = asyncio.Event()
        self.filled = asyncio.Event()
        self.reserve = asyncio.Queue(maxsize=max(64,args.concurrency))
        self.waiters = {}
        self.counters = Counter()
        self.statuses = Counter()
        self.errors = Counter()
        self.ready_workers = set()
        self.begin = None
        self.end = None
        self.ramp_begin = None
        self.started = time.time()
        self.headers = {'Authorization':'Bearer '+token,'Content-Type':'application/json',
            'X-Managed-Agents-Tenant':'default','X-Managed-Agents-Namespace':'default',
            'Managed-Agents-Extensions':('session-queue-v1' if getattr(args,'direct_store',False)
                else 'session-queue-v1' if getattr(args,'session_queue',False) else 'turn-inputs-v1')}
        self.client = client or httpx.AsyncClient(base_url=args.base_url.rstrip('/'),headers=self.headers,
            trust_env=False,timeout=httpx.Timeout(30,connect=10,pool=30),
            limits=httpx.Limits(max_connections=args.concurrency+128,max_keepalive_connections=128))

    def record(self, name, value):
        with (self.out/name).open('a') as output:
            output.write(json.dumps(value)+'\n')

    def snapshot(self):
        return {'sampled_at':time.time(),'target_concurrency':self.args.concurrency,
            'observed_pending_receipts':len(self.waiters),'prepared_sessions':self.reserve.qsize(),
            'counters':dict(self.counters),'terminal_statuses':dict(self.statuses),'errors':dict(self.errors),
            'hold_started_at':self.begin,'hold_ended_at':self.end,'stopping':self.stop.is_set()}

    async def api(self, method, path, body=None, key=None):
        attempts=getattr(self.args,'http_attempts',3)
        for attempt in range(attempts):
            try:
                response = await self.client.request(method,path,json=body,
                    headers={'Idempotency-Key':key} if key else None)
                self.counters['http_requests']+=1
                self.counters['http_status_'+str(response.status_code)]+=1
                error={}
                if not response.is_success:
                    try:
                        error=response.json().get('error',{})
                    except ValueError:
                        error={}
                    if not isinstance(error,dict):error={}
                    self.record('http-errors.jsonl',{'at':time.time(),'method':method,'path':path.split('?')[0],
                        'status':response.status_code,'attempt':attempt+1,
                        'type':error.get('type'),'code':error.get('code'),
                        'message':str(error.get('message',''))[:200],
                        'request_id':response.headers.get('request-id')})
                retryable_conflict=response.status_code==409 and error.get('code')=='request_in_progress'
                if response.status_code>=500 or response.status_code==429 or retryable_conflict:
                    self.counters['http_retries']+=1
                    if attempt==attempts-1:raise RuntimeError('HTTP '+str(response.status_code))
                else:
                    if not response.is_success:raise ValueError('HTTP '+str(response.status_code))
                    return response.json()
            except httpx.TransportError:
                self.counters['http_transport_errors']+=1
                if attempt==attempts-1:raise
            await asyncio.sleep(.2*(attempt+1))
        raise RuntimeError('HTTP retries exhausted')

    async def create(self):
        request_id = self.args.batch+'-'+uuid.uuid4().hex
        value = await self.api('POST','/v1/sessions',{'agent':self.args.agent,
            'environment_id':self.args.environment,'title':request_id,
            'metadata':{'load_test_batch':self.args.batch,'load_test_scenario':'tool' if self.args.tool else 'short',
                        'load_test_mode':'closed_loop','synthetic_model':'true'}},request_id+'-create')
        if getattr(self.args,'session_queue',False) and value.get('managed_agents',{}).get('scheduler_mode')!='db_queue_v1':
            raise RuntimeError('Session did not opt into db_queue_v1')
        if getattr(self.args,'direct_store',False) and (
                value.get('managed_agents',{}).get('commit_mode')!='direct'
                or value.get('managed_agents',{}).get('scheduler_mode')!='db_queue_v1'):
            raise RuntimeError('Session did not confirm direct commits with db_queue_v1')
        self.counters['sessions_created']+=1
        return value['id'],request_id

    async def prepare(self):
        while not self.stop.is_set():
            if self.reserve.full():
                await asyncio.sleep(.02);continue
            try:
                item=await self.create()
                await self.reserve.put(item)
            except Exception as error:
                self.errors['session_create_'+type(error).__name__]+=1
                self.stop.set();raise

    async def worker(self, index):
        try:
            await asyncio.sleep(max(0,self.ramp_begin+self.args.ramp*index/self.args.concurrency-time.time()))
            while not self.stop.is_set():
                try:
                    sid,request_id=await asyncio.wait_for(self.reserve.get(),.25)
                except asyncio.TimeoutError:
                    continue
                if self.stop.is_set():break
                marker='pts-'+sid
                scope=scope_key(sid)
                future=asyncio.get_running_loop().create_future()
                identity={'session_id':sid,'request_id':request_id,'marker':marker,'scope_key':scope,
                          'turn_id':None,'submitted_at':time.time()}
                self.waiters[scope]=(identity,future)
                try:
                    receipt=await self.api('POST','/v1/sessions/'+sid+'/events',{'events':[{
                        'type':'user.message','content':[{'type':'text','text':'LOADTEST '+json.dumps({
                            'request_id':request_id,'marker':marker})}],
                        'managed_agents':{'intent':'new_turn'}}]},request_id+'-turn')
                    identity['turn_id']=receipt['data'][0]['managed_agents']['receipt']['id']
                    identity['receipt_at']=time.time()
                    identity['http_admission_seconds']=identity['receipt_at']-identity['submitted_at']
                    self.counters['turns_admitted']+=1
                    self.ready_workers.add(index)
                    if len(self.ready_workers)==self.args.concurrency and self.begin is None and not self.stop.is_set():
                        self.begin=time.time();self.filled.set()
                    self.record('admissions.jsonl',identity)
                except Exception as error:
                    # An uncertain POST may already have admitted a Turn. Keep its business slot
                    # and stable identity; stop replacement submissions and reconcile through SQL.
                    self.errors['admission_'+type(error).__name__]+=1
                    self.stop.set()
                try:
                    terminal=await asyncio.wait_for(asyncio.shield(future),self.args.max_age)
                except asyncio.TimeoutError:
                    self.errors['terminal_deadline']+=1;self.stop.set();return
                identity['turn_id']=terminal['turn_id']
                self.statuses[terminal['status']]+=1
                self.counters['turns_terminal']+=1
                physical=terminal.get('physical',[])
                if terminal['status']=='succeeded':
                    if self.args.tool and (len(physical)!=1 or physical[0]['state']!='succeeded' or not physical[0].get('call_id')):
                        self.errors['physical_tool_validation']+=1;self.stop.set()
                    path='/v1/sessions/'+sid+'/turns/'+identity['turn_id']+'/output?limit=100'
                    output=[]
                    while path:
                        page=await self.api('GET',path);output.extend(page['data'])
                        path=('/v1/sessions/'+sid+'/turns/'+identity['turn_id']+'/output?limit=100&after='+page['next_cursor']) if page.get('has_more') else None
                    serialized=json.dumps(output,ensure_ascii=False)
                    if marker not in serialized or 'PTS_OK' not in serialized or any(not p.get('call_id') or p['call_id'] not in serialized for p in physical):
                        self.errors['committed_output_validation']+=1;self.stop.set()
                    self.counters['outputs_verified']+=1
                self.record('terminals.jsonl',{**identity,**terminal,'observed_at':time.time()})
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.errors['worker_'+type(error).__name__]+=1;self.stop.set()

    async def observe(self):
        async with await psycopg.AsyncConnection.connect(os.environ['DATABASE_URI'],autocommit=True,
                row_factory=dict_row,connect_timeout=10,
                options='-c default_transaction_read_only=on -c statement_timeout=5000') as db:
            while True:
                scopes=list(self.waiters)
                if scopes:
                    cursor=await db.execute('SELECT scope_key,turn_id,status,created_at FROM ma.turn WHERE scope_key=ANY(%s)',(scopes,))
                    rows=await cursor.fetchall()
                    finished=[row for row in rows if row['status'] in TERMINAL]
                    physical={}
                    if finished and self.args.tool:
                        keys=[row['scope_key'] for row in finished]
                        cursor=await db.execute("""SELECT status AS state,
                            result::jsonb#>>'{structuredContent,marker}' AS marker,
                            result::jsonb#>>'{structuredContent,call_id}' AS call_id,
                            'hands_captured_result' AS evidence_source FROM ma.hands_execution
                            WHERE scope_key=ANY(%s) AND tool_name='mcp.call'""",(keys,))
                        for row in await cursor.fetchall():physical.setdefault(row['marker'],[]).append(row)
                    for row in finished:
                        entry=self.waiters.pop(row['scope_key'],None)
                        if entry:
                            identity,future=entry
                            if identity['turn_id'] is not None and identity['turn_id']!=row['turn_id']:
                                self.errors['turn_identity_mismatch']+=1;self.stop.set();continue
                            if not future.done():future.set_result({**row,'physical':physical.get(identity['marker'],[])})
                    self.record('durable-concurrency.jsonl',{'sampled_at':time.time(),
                        'active_turns':sum(row['status'] not in TERMINAL for row in rows),
                        'statuses':dict(Counter(row['status'] for row in rows))})
                    ages=[time.time()-row['created_at']/1000 for row in rows if row['status'] not in TERMINAL]
                    if ages and max(ages)>self.args.max_age:
                        self.errors['durable_work_age']+=1;self.stop.set()
                await asyncio.sleep(self.args.completion_poll)

    async def monitor(self):
        baseline_deadlocks=None
        baseline_restarts=None
        while True:
            if self.args.observer_url:
                response=await self.client.get(self.args.observer_url.rstrip('/')+'/snapshot')
                response.raise_for_status();snapshot=response.json()
                if not snapshot.get('success') or time.time()-snapshot['sampled_at']>60:
                    raise RuntimeError('Missing fresh cluster telemetry')
                self.record('observer.jsonl',snapshot)
                deadlocks=next(m['value'] for m in snapshot['metrics'] if m['name']=='ma_pg_deadlocks_total')
                restarts={m['labels']['pod']:m['value'] for m in snapshot['metrics'] if m['name']=='ma_pod_restarts'}
                if baseline_deadlocks is None:baseline_deadlocks=deadlocks;baseline_restarts=restarts
                if deadlocks>baseline_deadlocks:raise RuntimeError('Database deadlock counter increased')
                if any(v>baseline_restarts.get(k,0) for k,v in restarts.items()):raise RuntimeError('Pod restart counter increased')
            value=self.snapshot()
            self.record('timeline.jsonl',value)
            (self.out/'running.json').write_text(json.dumps(value))
            if self.errors:raise RuntimeError('Closed-loop validation failure')
            terminal=sum(self.statuses.values())
            if terminal>=100 and (terminal-self.statuses.get('succeeded',0))/terminal>self.args.business_failure_stop_rate:
                raise RuntimeError('Business failure rate exceeded the stop threshold')
            await asyncio.sleep(3)

    async def wait_for_start(self):
        gate=getattr(self.args,'start_at_file',None)
        if gate is None:return
        (self.out/'barrier-ready.json').write_text(json.dumps({'ready_at':time.time()}))
        while True:
            if self.stop.is_set():raise RuntimeError('Stopped before synchronized start')
            start_at=float(gate.read_text().strip())
            if start_at>0:break
            await asyncio.sleep(.1)
        (self.out/'barrier-seen.json').write_text(json.dumps({'start_at':start_at,'seen_at':time.time()}))
        while time.time()<start_at:
            if self.stop.is_set():raise RuntimeError('Stopped before synchronized start')
            await asyncio.sleep(min(.1,start_at-time.time()))

    async def run(self):
        config={k:str(v) if isinstance(v,Path) else v for k,v in vars(self.args).items() if k!='token_file'}
        (self.out/'configuration.json').write_text(json.dumps(config,indent=2))
        tasks=[];workers=[]
        try:
            # Prepare the first pool outside the measured window; it contains no admitted Turns.
            initial=await asyncio.gather(*(self.create() for _ in range(min(32,self.args.concurrency))))
            for item in initial:await self.reserve.put(item)
            producers=[asyncio.create_task(self.prepare()) for _ in range(32)]
            tasks.extend(producers)
            while self.reserve.qsize()<self.args.concurrency:
                if any(t.done() for t in producers):raise RuntimeError('Session preparation stopped')
                await asyncio.sleep(.1)
            observation=asyncio.create_task(self.observe());monitor=asyncio.create_task(self.monitor())
            tasks.extend([observation,monitor])
            await self.wait_for_start()
            self.ramp_begin=time.time()
            workers=[asyncio.create_task(self.worker(i)) for i in range(self.args.concurrency)]
            deadline=time.monotonic()+120
            while not self.filled.is_set():
                if self.stop.is_set() or observation.done() or monitor.done() or time.monotonic()>deadline:
                    raise RuntimeError('Unable to establish target business concurrency')
                await asyncio.sleep(.1)
            deadline=time.monotonic()+self.args.hold
            while time.monotonic()<deadline:
                if self.stop.is_set() or observation.done() or monitor.done():
                    raise RuntimeError('Closed-loop guard stopped the hold')
                await asyncio.sleep(.25)
        except Exception as error:
            self.errors['controller_'+type(error).__name__]+=1
            self.record('control-errors.jsonl',{'at':time.time(),'reason':str(error)[:200]})
        finally:
            self.end=time.time();self.stop.set()
            for task in tasks[:32]:task.cancel()
            if workers:
                done,pending=await asyncio.wait(workers,timeout=self.args.drain)
                for task in pending:task.cancel()
                await asyncio.gather(*workers,return_exceptions=True)
            for task in tasks:task.cancel()
            results=await asyncio.gather(*tasks,return_exceptions=True)
            for error in results:
                if isinstance(error,Exception) and not isinstance(error,asyncio.CancelledError):
                    self.errors['background_'+type(error).__name__]+=1
                    self.record('background-errors.jsonl',{'at':time.time(),'type':type(error).__name__,'reason':str(error)[:200]})
            summary={**self.snapshot(),'started_at':self.started,'finished_at':time.time(),
                'hold_seconds':self.end-self.begin if self.begin else 0,
                'unresolved':[identity for identity,future in self.waiters.values()]}
            (self.out/'summary.json').write_text(json.dumps(summary,indent=2))
            print(json.dumps({k:summary[k] for k in ['target_concurrency','hold_seconds','counters','terminal_statuses','errors','observed_pending_receipts']}),flush=True)
            await self.client.aclose()
        if self.errors or self.waiters:raise RuntimeError('Closed-loop run requires failure reconciliation')


async def main(args):
    token=os.environ.get('PTS_TOKEN')
    if args.token_file:token=json.loads(args.token_file.read_text())['token']
    if not token:raise ValueError('Provide PTS_TOKEN or token-file')
    run=ClosedLoop(args,token)
    for name in [signal.SIGINT,signal.SIGTERM]:asyncio.get_running_loop().add_signal_handler(name,run.stop.set)
    await run.run()


if __name__=='__main__':
    os.umask(0o077)
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url',required=True);p.add_argument('--agent',required=True)
    p.add_argument('--environment',required=True);p.add_argument('--batch',required=True)
    p.add_argument('--token-file',type=Path);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--concurrency',type=int,choices=range(1,10001),default=1000)
    p.add_argument('--hold',type=int,default=600);p.add_argument('--ramp',type=float,default=5)
    p.add_argument('--start-at-file',type=Path,help='Shared Unix timestamp gate; zero waits for all drivers')
    p.add_argument('--max-age',type=int,default=180);p.add_argument('--drain',type=int,default=240)
    p.add_argument('--completion-poll',type=float,default=.2)
    p.add_argument('--http-attempts',type=int,choices=range(1,11),default=3,
        help='Bounded original-key retries; report every failed HTTP attempt separately')
    p.add_argument('--session-queue',action='store_true',help='Use and verify db_queue_v1 Sessions')
    p.add_argument('--direct-store',action='store_true',help='Verify direct commits and the database queue')
    p.add_argument('--observer-url');p.add_argument('--tool',action='store_true')
    p.add_argument('--business-failure-stop-rate',type=float,default=.01,
        help='Early stop threshold; final acceptance still requires at least 99.9%% success')
    args=p.parse_args()
    if args.hold<1 or args.ramp<0 or args.completion_poll<.05:p.error('Invalid timing')
    if not 0<=args.business_failure_stop_rate<=1:p.error('Invalid failure stop threshold')
    asyncio.run(main(args))

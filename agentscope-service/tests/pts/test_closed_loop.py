# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import asyncio
import json
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
import httpx
from closed_loop import ClosedLoop,scope_key


class ClosedLoopTest(unittest.IsolatedAsyncioTestCase):
    def args(self,path,**changes):
        values=dict(out=Path(path)/'stage',concurrency=3,base_url='http://fixture',batch='fixture',
            agent='agent',environment='environment',tool=False,ramp=0,hold=.15,drain=1,
            max_age=1,completion_poll=.05,observer_url=None,token_file=None,business_failure_stop_rate=.01)
        return SimpleNamespace(**(values|changes))

    async def test_shared_start_gate_waits_for_release_and_future_timestamp(self):
        with tempfile.TemporaryDirectory() as path:
            gate=Path(path)/'start';gate.write_text('0')
            run=ClosedLoop(self.args(path,start_at_file=gate),'token')
            waiting=asyncio.create_task(run.wait_for_start())
            try:
                await asyncio.sleep(.03)
                self.assertTrue((run.out/'barrier-ready.json').exists())
                self.assertFalse(waiting.done())
                start=time.time()+.2;gate.write_text(str(start))
                await asyncio.sleep(.05);self.assertFalse(waiting.done())
                await asyncio.wait_for(waiting,1)
                self.assertGreaterEqual(time.time(),start)
                self.assertEqual(run.counters['turns_admitted'],0)
            finally:
                waiting.cancel();await asyncio.gather(waiting,return_exceptions=True)
                await run.client.aclose()

    async def test_stopping_before_shared_start_does_not_admit_requests(self):
        with tempfile.TemporaryDirectory() as path:
            gate=Path(path)/'start';gate.write_text('0')
            run=ClosedLoop(self.args(path,start_at_file=gate),'token')
            run.stop.set()
            try:
                with self.assertRaisesRegex(RuntimeError,'Stopped before'):
                    await run.wait_for_start()
                self.assertEqual(run.counters['turns_admitted'],0)
            finally:await run.client.aclose()

    async def test_closed_loop_replaces_only_terminal_sessions_and_keeps_business_slots_bounded(self):
        counters={'created':0,'admitted':0,'peak':0}
        async def http(request):
            if request.url.path=='/v1/sessions':
                self.assertTrue(all(isinstance(v,str) for v in json.loads(request.content)['metadata'].values()))
                counters['created']+=1
                return httpx.Response(200,json={'id':'session-'+str(counters['created'])})
            if request.method=='POST':
                body=json.loads(request.content)
                self.assertEqual(body['events'][0]['type'],'user.message')
                counters['admitted']+=1
                return httpx.Response(200,json={'data':[{'managed_agents':{'receipt':{'id':'turn-'+str(counters['admitted'])}}}]})
            sid=request.url.path.split('/')[3]
            return httpx.Response(200,json={'data':[{'text':'PTS_OK marker=pts-'+sid}],'has_more':False})
        class Fixture(ClosedLoop):
            async def observe(this):
                while True:
                    await asyncio.sleep(.02)
                    counters['peak']=max(counters['peak'],len(this.waiters))
                    for key,(identity,future) in list(this.waiters.items()):
                        if identity['turn_id']:
                            this.waiters.pop(key)
                            future.set_result({'turn_id':identity['turn_id'],'status':'succeeded','physical':[]})
        with tempfile.TemporaryDirectory() as path:
            client=httpx.AsyncClient(base_url='http://fixture',transport=httpx.MockTransport(http))
            run=Fixture(self.args(path),'private-token',client)
            await run.run()
            self.assertEqual(counters['peak'],3)
            self.assertGreater(counters['admitted'],3)
            self.assertEqual(counters['admitted'],run.counters['outputs_verified'])
            self.assertFalse(run.waiters)
            self.assertFalse(run.errors)
            self.assertNotIn('private-token',(Path(path)/'stage/configuration.json').read_text())

    async def test_preparation_keeps_a_full_bounded_spare_pool_during_hold(self):
        async def http(request):
            return httpx.Response(200,json={'id':'prepared'})
        with tempfile.TemporaryDirectory() as path:
            run=ClosedLoop(self.args(path,concurrency=100),'token',httpx.AsyncClient(
                base_url='http://fixture',transport=httpx.MockTransport(http)))
            run.ramp_begin=1
            task=asyncio.create_task(run.prepare())
            try:
                async with asyncio.timeout(2):
                    while run.reserve.qsize()<100:await asyncio.sleep(.01)
                await asyncio.sleep(.05)
                self.assertEqual(run.reserve.qsize(),100)
                self.assertEqual(run.counters['sessions_created'],100)
            finally:
                run.stop.set();await task;await run.client.aclose()

    async def test_uncertain_admission_retries_original_identity_and_does_not_refill_a_lost_slot(self):
        keys=[]
        async def http(request):
            if request.url.path=='/v1/sessions':return httpx.Response(200,json={'id':'one'})
            keys.append(request.headers['Idempotency-Key'])
            return httpx.Response(503,json={'error':'unavailable'})
        class Fixture(ClosedLoop):
            async def observe(this):await asyncio.Event().wait()
        with tempfile.TemporaryDirectory() as path:
            run=Fixture(self.args(path,concurrency=1,max_age=.05,drain=.1), 'token',
                httpx.AsyncClient(base_url='http://fixture',transport=httpx.MockTransport(http)))
            with self.assertRaises(RuntimeError):await run.run()
            self.assertEqual(len(keys),3)
            self.assertEqual(len(set(keys)),1)
            self.assertEqual(len(run.waiters),1)
            self.assertEqual(run.counters['turns_admitted'],0)

    async def test_queue_mode_is_explicit_and_checked_on_session_creation(self):
        for mode,accepted in [('db_queue_v1',True),('broker_fifo_v1',False)]:
            async def http(request):
                self.assertEqual(request.headers['Managed-Agents-Extensions'],'session-queue-v1')
                return httpx.Response(200,json={'id':'queue-session','managed_agents':{'scheduler_mode':mode}})
            with tempfile.TemporaryDirectory() as path:
                args=self.args(path,session_queue=True)
                client=httpx.AsyncClient(base_url='http://fixture',transport=httpx.MockTransport(http),
                    headers={'Managed-Agents-Extensions':'session-queue-v1'})
                run=ClosedLoop(args,'token',client)
                if accepted:self.assertEqual((await run.create())[0],'queue-session')
                else:
                    with self.assertRaisesRegex(RuntimeError,'db_queue_v1'):await run.create()
                await client.aclose()

    async def test_direct_store_requires_the_current_receipt(self):
        for mode in ['direct','session_store_v1']:
            async def http(request):
                self.assertEqual(request.headers['Managed-Agents-Extensions'],'session-queue-v1')
                return httpx.Response(200,json={'id':'store-session','managed_agents':{
                    'scheduler_mode':'db_queue_v1','commit_mode':mode}})
            with tempfile.TemporaryDirectory() as path:
                run=ClosedLoop(self.args(path,direct_store=True),'token')
                await run.client.aclose()
                run.client=httpx.AsyncClient(base_url='http://fixture',headers=run.headers,
                    transport=httpx.MockTransport(http))
                if mode=='direct':self.assertEqual((await run.create())[0],'store-session')
                else:
                    with self.assertRaisesRegex(RuntimeError,'direct'):await run.create()
                await run.client.aclose()

    def test_scope_identity_is_stable_and_matches_complete_wire_scope(self):
        # Vector captured from ManagedScope.messageGroup in a real Runtime acceptance execution.
        self.assertEqual(scope_key('c87db44f-3cdb-5f5d-a612-dd1f17e73fc6'),
                         'SAh4Wf-BxDjpODpt3zGeqJK7JpbPSzl7lwnUyeJ6RRA')
        self.assertEqual(scope_key('session'),scope_key('session'))
        self.assertEqual(len(scope_key('session')),43)
        self.assertNotEqual(scope_key('session'),scope_key('other'))

    async def test_http_failure_records_status_and_bounded_error_without_credentials(self):
        async def http(request):
            return httpx.Response(400,json={'error':{'type':'invalid_request_error','message':'Session is not ready'}})
        with tempfile.TemporaryDirectory() as path:
            run=ClosedLoop(self.args(path),'private-token',httpx.AsyncClient(
                base_url='http://fixture',transport=httpx.MockTransport(http)))
            with self.assertRaisesRegex(ValueError,'HTTP 400'):
                await run.api('POST','/v1/sessions/one/events',{},'same-key')
            record=(Path(path)/'stage/http-errors.jsonl').read_text()
            self.assertEqual(json.loads(record)['status'],400)
            self.assertIn('Session is not ready',record)
            self.assertNotIn('private-token',record)
            await run.client.aclose()

    async def test_only_explicit_in_progress_conflict_retries_the_original_key(self):
        for code,retries in [('request_in_progress',True),('turn_content_conflict',False)]:
            keys=[]
            async def http(request):
                keys.append(request.headers['Idempotency-Key'])
                return (httpx.Response(409,json={'error':{'type':'conflict_error','code':code}})
                        if len(keys)==1 else httpx.Response(200,json={'accepted':True}))
            with tempfile.TemporaryDirectory() as path:
                run=ClosedLoop(self.args(path),'token',httpx.AsyncClient(
                    base_url='http://fixture',transport=httpx.MockTransport(http)))
                if retries:
                    self.assertEqual(await run.api('POST','/events',{},'original-key'),{'accepted':True})
                    self.assertEqual(keys,['original-key','original-key'])
                    self.assertEqual(run.counters['http_retries'],1)
                else:
                    with self.assertRaisesRegex(ValueError,'HTTP 409'):
                        await run.api('POST','/events',{},'original-key')
                    self.assertEqual(keys,['original-key'])
                await run.client.aclose()

    async def test_explicit_retry_budget_preserves_identity_and_reports_every_attempt(self):
        keys=[]
        async def http(request):
            keys.append(request.headers['Idempotency-Key'])
            return httpx.Response(409,json={'error':{'code':'request_in_progress'}})
        with tempfile.TemporaryDirectory() as path:
            run=ClosedLoop(self.args(path,http_attempts=6),'token',httpx.AsyncClient(
                base_url='http://fixture',transport=httpx.MockTransport(http)))
            with self.assertRaisesRegex(RuntimeError,'HTTP 409'):
                await run.api('POST','/events',{},'original-key')
            self.assertEqual(keys,['original-key']*6)
            self.assertEqual(run.counters['http_retries'],6)
            self.assertEqual(len((run.out/'http-errors.jsonl').read_text().splitlines()),6)
            await run.client.aclose()

    async def test_early_stop_uses_its_explicit_threshold(self):
        with tempfile.TemporaryDirectory() as path:
            run=ClosedLoop(self.args(path),'token')
            run.statuses.update(succeeded=199,failed=1)
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(run.monitor(),.02)
            run.statuses.update(failed=3)
            with self.assertRaisesRegex(RuntimeError,'stop threshold'):
                await run.monitor()
            await run.client.aclose()

    async def test_late_admission_after_stop_cannot_start_a_hold_window(self):
        release=asyncio.Event()
        async def http(request):
            if request.url.path=='/v1/sessions':return httpx.Response(200,json={'id':'one'})
            if request.method=='POST':
                await release.wait()
                return httpx.Response(200,json={'data':[{'managed_agents':{'receipt':{'id':'turn'}}}]})
            return httpx.Response(200,json={'data':[{'text':'PTS_OK marker=pts-one'}],'has_more':False})
        class Fixture(ClosedLoop):
            async def monitor(this):
                await asyncio.sleep(.02);this.stop.set()
                await asyncio.sleep(.15);release.set()
                await asyncio.Event().wait()
            async def observe(this):
                while True:
                    await asyncio.sleep(.01)
                    for key,(identity,future) in list(this.waiters.items()):
                        if identity['turn_id']:
                            this.waiters.pop(key);future.set_result({'turn_id':'turn','status':'succeeded','physical':[]})
        with tempfile.TemporaryDirectory() as path:
            run=Fixture(self.args(path,concurrency=1),'token',httpx.AsyncClient(
                base_url='http://fixture',transport=httpx.MockTransport(http)))
            with self.assertRaises(RuntimeError):await run.run()
            self.assertIsNone(run.begin)
            self.assertEqual(json.loads((run.out/'summary.json').read_text())['hold_seconds'],0)


if __name__=='__main__':unittest.main()

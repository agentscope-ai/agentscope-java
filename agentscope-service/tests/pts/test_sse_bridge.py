# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import asyncio
from pathlib import Path
import tempfile
import unittest
import httpx
from sse_bridge import Bridge, application


class BridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_session_cannot_be_admitted_until_stream_boundary_and_closes_after_idle(self):
        connected, completed = asyncio.Event(), asyncio.Event()
        class Stream(httpx.AsyncByteStream):
            async def __aiter__(self):
                await connected.wait()
                yield b':connected\n\n'
                await completed.wait()
                yield b'data: {"id":"a","type":"assistant.message"}\n\n'
                yield b'data: {"id":"b","type":"managed_agents.turn.completed"}\n\n'
                yield b'data: {"id":"c","type":"session.status_idle"}\n\n'
                await asyncio.sleep(100)
        async def handler(request):
            self.assertEqual(request.headers['authorization'],'Bearer fixture')
            if request.method=='POST':
                return httpx.Response(201,json={'id':'one'})
            return httpx.Response(200,headers={'content-type':'text/event-stream'},stream=Stream())
        with tempfile.TemporaryDirectory() as directory:
            bridge = Bridge('http://upstream',Path(directory),transport=httpx.MockTransport(handler))
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=application(bridge)),
                                        base_url='http://bridge') as client:
                create = asyncio.create_task(client.post('/sse/v1/sessions',
                    headers={'Authorization':'Bearer fixture'},json={}))
                await asyncio.sleep(.02)
                self.assertFalse(create.done())
                connected.set()
                response = await asyncio.wait_for(create,1)
                self.assertEqual(response.status_code,201)
                self.assertEqual(bridge.summary()['open'],1)
                completed.set()
                await asyncio.gather(*bridge.tasks)
                self.assertEqual(bridge.summary()['completed_streams'],1)
                self.assertEqual(bridge.summary()['open'],0)
                self.assertEqual(bridge.summary()['errors'],0)
            await bridge.close()

    async def test_failed_stream_boundary_prevents_returning_a_usable_session(self):
        async def handler(request):
            if request.method=='POST':
                return httpx.Response(201,json={'id':'one'})
            return httpx.Response(403)
        with tempfile.TemporaryDirectory() as directory:
            bridge = Bridge('http://upstream',Path(directory),transport=httpx.MockTransport(handler))
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=application(bridge)),
                                        base_url='http://bridge') as client:
                response = await client.post('/sse/v1/sessions',
                    headers={'Authorization':'Bearer fixture'},json={})
                self.assertEqual(response.status_code,502)
                self.assertEqual(bridge.summary()['errors'],1)
            await bridge.close()

# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import asyncio
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import httpx
from sse import event_frame, delay_seconds, run


class SseTest(unittest.TestCase):
    def test_comments_are_not_events_and_multiline_data_is_preserved(self):
        self.assertIsNone(event_frame([':connected']))
        value = event_frame(['event: assistant.message','data: {"id":"one",',
            'data: "type":"assistant.message","processed_at":"1970-01-01T00:00:01Z"}'])
        self.assertEqual(value['event_id'],'one')
        self.assertEqual(value['type'],'assistant.message')
        self.assertEqual(len(value['payload_sha256']),64)
        self.assertEqual(delay_seconds(value['processed_at'],1.25),.25)
        self.assertIsNone(delay_seconds(None,1.25))
        with self.assertRaisesRegex(ValueError,'protocol event'):
            event_frame(['event: error','data: {"code":"forbidden"}'])


class SseConnectionTest(unittest.IsolatedAsyncioTestCase):
    async def test_readiness_waits_for_connected_frame_and_unexpected_eof_fails(self):
        connected, finish = asyncio.Event(), asyncio.Event()
        class Stream(httpx.AsyncByteStream):
            async def __aiter__(self):
                await connected.wait()
                yield b':connected\n\n'
                yield b'data: {"id":"one","type":"managed_agents.turn.completed"}\n\n'
                await finish.wait()
        async def handler(request):
            return httpx.Response(200,headers={'content-type':'text/event-stream'},stream=Stream())
        real_client, real_sleep = httpx.AsyncClient, asyncio.sleep
        async def fast_sleep(seconds):
            await real_sleep(.005)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'sessions.csv').write_text('one\n')
            (root/'token.json').write_text(json.dumps({'token':'fixture'}))
            args = SimpleNamespace(out=root/'sse',sessions=root/'sessions.csv',token_file=root/'token.json',
                max_streams=1,connect_concurrency=1,base_url='http://fixture',duration=5,ready_timeout=3)
            with patch('sse.httpx.AsyncClient',side_effect=lambda **kw:real_client(
                    transport=httpx.MockTransport(handler),**kw)), patch('sse.asyncio.sleep',side_effect=fast_sleep):
                task = asyncio.create_task(run(args))
                await real_sleep(.03)
                self.assertFalse((args.out/'ready.json').exists())
                connected.set()
                for _ in range(100):
                    if (args.out/'ready.json').exists():break
                    await real_sleep(.005)
                self.assertTrue((args.out/'ready.json').exists())
                finish.set()
                with self.assertRaisesRegex(RuntimeError,'connection/delivery'):
                    await task
            self.assertFalse((args.out/'ready.json').exists())
            summary = json.loads((args.out/'summary.json').read_text())
            self.assertEqual(summary['errors'],1)
            self.assertEqual(summary['states']['one']['terminals'],1)


if __name__=='__main__':
    unittest.main()

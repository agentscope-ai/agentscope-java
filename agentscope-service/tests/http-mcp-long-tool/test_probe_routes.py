# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import os
import unittest
from unittest.mock import AsyncMock, patch

import server


class ProbeRouteTest(unittest.IsolatedAsyncioTestCase):
    async def test_liveness_and_readiness_are_process_local(self):
        self.assertEqual((await server.health(None)).status_code, 200)
        with patch.object(server.health_monitor, 'snapshot', return_value={'ready': True}):
            self.assertEqual((await server.ready(None)).status_code, 200)
        with patch.object(server.health_monitor, 'snapshot', return_value={'ready': False}):
            self.assertEqual((await server.ready(None)).status_code, 503)

    async def test_diagnostics_requires_authentication_but_probes_do_not(self):
        called = []
        async def app(scope, receive, send):
            called.append(scope['path'])
        async def receive():
            return {'type': 'http.request'}
        sent = []
        async def send(message):
            sent.append(message)
        wrapper = server.BearerAuth(app, 'test-token')
        for path in ['/healthz', '/readyz', '/diagnostics']:
            await wrapper({'type':'http','path':path,'headers':[]}, receive, send)
        self.assertEqual(called, ['/healthz', '/readyz'])
        self.assertEqual(sent[0]['status'], 401)
        await wrapper({'type':'http','path':'/diagnostics',
                       'headers':[(b'authorization',b'Bearer test-token')]}, receive, send)
        self.assertEqual(called[-1], '/diagnostics')


if __name__ == '__main__':
    unittest.main()

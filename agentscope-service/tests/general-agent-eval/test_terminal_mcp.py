# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest

try:
    import httpx
    from sandbox_eval.terminal_mcp import TerminalMCP
except ImportError:
    TerminalMCP = None

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown


class Bridge:
    closed = False
    def __init__(self, failure=False):
        self.failure = failure
        self.calls = []

    async def call(self, name, arguments):
        self.calls.append((name, arguments))
        if self.failure:
            raise ExecutionOutcomeUnknown('Lost command receipt')
        return {'result': {'status': 'exited', 'return_code': 7, 'stdout': 'evidence'}}

    async def close(self):
        self.closed = True


@unittest.skipIf(TerminalMCP is None, 'Run in the pinned Harbor/MCP runner environment')
class TerminalMCPTest(unittest.IsolatedAsyncioTestCase):
    async def test_catalog_real_exit_and_trial_identity(self):
        server = TerminalMCP('secret')
        bridge = Bridge()
        server.bind('trial-one', bridge)
        async with server.inner.router.lifespan_context(server.inner):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server),
                    base_url='http://terminal.cluster', headers={'Authorization': 'Bearer secret',
                    'X-Eval-Trial': 'trial-one', 'Accept': 'application/json, text/event-stream'}) as client:
                response = await client.post('/mcp', json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(len(response.json()['result']['tools']), 4)
                response = await client.post('/mcp', json={'jsonrpc': '2.0', 'id': 2,
                    'method': 'tools/call', 'params': {'name': 'terminal_start',
                    'arguments': {'operation_id': 'op-one', 'command': 'exit 7'}}})
                self.assertFalse(response.json()['result'].get('isError', False))
                self.assertIn('"return_code": 7', response.json()['result']['content'][0]['text'])
                self.assertEqual(len(bridge.calls), 1)
                for headers in [{'X-Eval-Trial': 'other'}, {'Authorization': 'Bearer wrong'}]:
                    response = await client.post('/mcp', headers=headers,
                        json={'jsonrpc': '2.0', 'id': 3, 'method': 'tools/list'})
                    self.assertEqual(response.status_code, 403)

    async def test_unknown_outcome_closes_only_affected_trial(self):
        server = TerminalMCP('secret')
        broken, healthy = Bridge(True), Bridge()
        server.bind('broken', broken)
        server.bind('healthy', healthy)
        body = {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                'params': {'name': 'terminal_start', 'arguments': {'operation_id': 'op', 'command': 'echo once'}}}
        async with server.inner.router.lifespan_context(server.inner):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server),
                    base_url='http://terminal.cluster', headers={'Authorization': 'Bearer secret',
                    'Accept': 'application/json, text/event-stream'}) as client:
                response = await client.post('/mcp', headers={'X-Eval-Trial': 'broken'}, json=body)
                self.assertTrue(response.json()['result']['isError'])
                self.assertTrue(broken.closed)
                await client.post('/mcp', headers={'X-Eval-Trial': 'broken'}, json=body)
                self.assertEqual(len(broken.calls), 1)
                response = await client.post('/mcp', headers={'X-Eval-Trial': 'healthy'}, json=body)
                self.assertFalse(response.json()['result'].get('isError', False))
                self.assertEqual(len(healthy.calls), 1)


if __name__ == '__main__':
    unittest.main()

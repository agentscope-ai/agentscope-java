#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""HTTP contract checks against a running fixture container; no model calls."""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import os
import time
import unittest
import urllib.error
import urllib.request
import uuid


BASE = os.environ.get("MCP_TEST_BASE", "http://127.0.0.1:18888")
TOKEN = os.environ["MCP_TEST_TOKEN"]
PEER = os.environ.get('MCP_TEST_PEER_BASE')
RECOVERY_META = 'io.agentcore.managed/execution-recovery'


def rpc(method, params=None, base=BASE, timeout=10):
    body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}
    headers = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json",
               "Accept": "application/json, text/event-stream",
               "MCP-Protocol-Version": "2025-06-18"}
    request = urllib.request.Request(base + "/mcp", json.dumps(body).encode(), headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


class LongToolHttpTest(unittest.TestCase):
    def test_health_and_authentication(self):
        with urllib.request.urlopen(BASE + "/healthz", timeout=5) as response:
            self.assertEqual(json.load(response)["status"], "UP")
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(urllib.request.Request(BASE + "/mcp", b"{}"), timeout=5)
        self.assertEqual(error.exception.code, 401)
        error.exception.close()

    def test_initialize_and_discovery(self):
        result = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                    "clientInfo": {"name": "fixture-check", "version": "1"}})
        self.assertEqual(result["result"]["protocolVersion"], "2025-06-18")
        tools = {t['name']: t for t in rpc('tools/list')['result']['tools']}
        self.assertEqual(set(tools), {'long_wait'})
        self.assertNotIn(RECOVERY_META, tools['long_wait'].get('_meta', {}))

    def test_real_wait_and_concurrent_requests(self):
        def call(marker):
            result = rpc("tools/call", {"name": "long_wait",
                                        "arguments": {"marker": marker, "seconds": 2}})["result"]
            self.assertFalse(result.get("isError", False))
            return json.loads(result["content"][0]["text"])
        start = time.monotonic()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(call, ["concurrent-a", "concurrent-b"]))
        elapsed = time.monotonic() - start
        self.assertGreaterEqual(elapsed, 2)
        self.assertLess(elapsed, 3.8, "Waiting tools must not block the HTTP event loop")
        self.assertEqual({r["marker"] for r in results}, {"concurrent-a", "concurrent-b"})
        self.assertEqual(len({r["call_id"] for r in results}), 2)
        self.assertTrue(all(datetime.fromisoformat(r["finished_at"]) >
                            datetime.fromisoformat(r["started_at"]) for r in results))

    def test_fast_tool_preserves_two_hundred_millisecond_duration_and_replay(self):
        execution = 'http-fast-' + str(uuid.uuid4())
        params = {'name': 'long_wait', 'arguments': {'marker': execution, 'seconds': .2},
                  '_meta': {RECOVERY_META: {'version': 1, 'execution_id': execution}}}
        started = time.monotonic()
        result = rpc('tools/call', params)['result']
        self.assertFalse(result.get('isError', False))
        evidence = result['structuredContent']
        self.assertEqual(evidence['seconds'], .2)
        self.assertGreaterEqual(time.monotonic()-started, .2)
        self.assertGreaterEqual((datetime.fromisoformat(evidence['finished_at'])-
                                datetime.fromisoformat(evidence['started_at'])).total_seconds(), .2)
        self.assertNotEqual(rpc('tools/call', params)['result']['structuredContent']['call_id'], evidence['call_id'])
        changed = {**params, 'arguments': {'marker': execution, 'seconds': .3}}
        self.assertFalse(rpc('tools/call', changed)['result']['isError'])

    def test_wait_is_bounded(self):
        result = rpc("tools/call", {"name": "long_wait",
                                    "arguments": {"marker": "invalid", "seconds": 181}})
        self.assertTrue(result["result"]["isError"])

    @unittest.skipUnless(PEER, 'Set MCP_TEST_PEER_BASE for two independent performance servers')
    def test_two_processes_have_no_shared_storage_or_deduplication(self):
        params = {'name': 'long_wait', 'arguments': {'marker': 'same-marker', 'seconds': .2}}
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda base: rpc('tools/call', params, base)['result'], [BASE, PEER]))
        self.assertEqual(len({r['structuredContent']['call_id'] for r in results}), 2)

    def test_no_persistent_execution_lookup_is_exposed(self):
        result = rpc('tools/call', {'name': 'managed_execution_get', 'arguments': {'execution_id': 'absent'}})
        self.assertTrue(result['result']['isError'])


if __name__ == "__main__":
    unittest.main()

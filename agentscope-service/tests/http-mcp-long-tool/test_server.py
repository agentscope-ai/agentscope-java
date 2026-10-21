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
RECOVERY_META = 'io.agentscope/execution-recovery'


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
        self.assertEqual(set(tools), {'long_wait', 'managed_execution_get'})
        self.assertEqual(tools['long_wait']['_meta'][RECOVERY_META],
                         {'version': 1, 'queryTool': 'managed_execution_get'})
        self.assertTrue(tools['managed_execution_get']['annotations']['readOnlyHint'])

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

    def test_wait_is_bounded(self):
        result = rpc("tools/call", {"name": "long_wait",
                                    "arguments": {"marker": "invalid", "seconds": 181}})
        self.assertTrue(result["result"]["isError"])

    @unittest.skipUnless(PEER, 'Set MCP_TEST_PEER_BASE to a second process sharing the execution database')
    def test_two_processes_deduplicate_and_reject_changed_arguments(self):
        execution = 'http-dedup-' + str(uuid.uuid4())
        params = {'name': 'long_wait', 'arguments': {'marker': execution, 'seconds': 2},
                  '_meta': {RECOVERY_META: {'version': 1, 'execution_id': execution}}}
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda base: rpc('tools/call', params, base)['result'], [BASE, PEER]))
        self.assertEqual(results[0], results[1])
        changed = {**params, 'arguments': {'marker': execution, 'seconds': 3}}
        self.assertTrue(rpc('tools/call', changed, PEER)['result']['isError'])
        found = rpc('tools/call', {'name': 'managed_execution_get', 'arguments': {'execution_id': execution}}, PEER)
        state = found['result']['structuredContent']
        self.assertEqual(state['state'], 'succeeded')
        self.assertEqual(state['result'], results[0])

    @unittest.skipUnless(PEER, 'Set MCP_TEST_PEER_BASE to a second process sharing the execution database')
    def test_disconnected_caller_can_query_the_original_result_from_a_peer(self):
        execution = 'http-disconnect-' + str(uuid.uuid4())
        params = {'name': 'long_wait', 'arguments': {'marker': execution, 'seconds': 3},
                  '_meta': {RECOVERY_META: {'version': 1, 'execution_id': execution}}}
        with self.assertRaises(TimeoutError):
            rpc('tools/call', params, BASE, timeout=0.5)
        query = {'name': 'managed_execution_get', 'arguments': {'execution_id': execution}}
        running = rpc('tools/call', query, PEER)['result']['structuredContent']
        self.assertEqual(running['state'], 'running')
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            found = rpc('tools/call', query, PEER)['result']['structuredContent']
            if found['state'] == 'succeeded':
                break
            time.sleep(0.1)
        self.assertEqual(found['state'], 'succeeded')
        self.assertEqual(found['execution_id'], execution)
        self.assertEqual(rpc('tools/call', params, PEER)['result'], found['result'])

    def test_unknown_execution_lookup_has_no_side_effect(self):
        execution = 'never-admitted-' + str(uuid.uuid4())
        state = rpc('tools/call', {'name': 'managed_execution_get',
                                 'arguments': {'execution_id': execution}})['result']['structuredContent']
        self.assertEqual(state, {'version': 1, 'execution_id': execution, 'state': 'not_found'})


if __name__ == "__main__":
    unittest.main()

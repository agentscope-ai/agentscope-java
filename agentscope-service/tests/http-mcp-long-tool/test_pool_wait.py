# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Storage-free execution, concurrency and cancellation regressions."""
import asyncio
import json
import unittest
from unittest.mock import Mock, patch
import server


class WaitTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.patcher = patch.object(server, 'trace', Mock())
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    async def test_repeated_marker_is_an_independent_call_not_a_persistent_identity(self):
        first, second = await asyncio.gather(server.long_wait('same', .1), server.long_wait('same', .1))
        self.assertNotEqual(first.structuredContent['call_id'], second.structuredContent['call_id'])
        self.assertEqual(first.structuredContent, json.loads(first.content[0].text))
        self.assertFalse(server.active)

    async def test_thousands_of_waits_share_no_execution_semaphore_or_connections(self):
        baseline = server.counters['completed']
        results = await asyncio.gather(*(server.long_wait('m' + str(i), .1) for i in range(2000)))
        self.assertEqual(len({r.structuredContent['call_id'] for r in results}), 2000)
        self.assertGreaterEqual(server.counters['peak_active'], 2000)
        self.assertEqual(server.counters['completed'] - baseline, 2000)
        self.assertFalse(server.active)

    async def test_http_cancellation_does_not_cancel_physical_wait(self):
        waiter = asyncio.create_task(server.long_wait('disconnect', .1))
        while not server.active:
            await asyncio.sleep(0)
        physical = next(iter(server.active))
        waiter.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await waiter
        self.assertFalse(physical.cancelled())
        await physical
        self.assertFalse(server.active)

    async def test_schema_does_not_advertise_durable_recovery_or_lookup_tool(self):
        tools = await server.mcp.list_tools()
        self.assertEqual([t.name for t in tools], ['long_wait'])
        self.assertNotIn('io.agentcore.managed/execution-recovery', tools[0].meta or {})
        self.assertFalse(hasattr(server, 'store'))

    async def test_startup_and_shutdown_need_no_database_and_drain_active_calls(self):
        app = server.create_app('local').app
        async with app.router.lifespan_context(app):
            task = asyncio.create_task(server.long_wait('drain', .1))
            while not server.active:
                await asyncio.sleep(0)
        self.assertTrue(task.done())
        self.assertFalse(server.active)

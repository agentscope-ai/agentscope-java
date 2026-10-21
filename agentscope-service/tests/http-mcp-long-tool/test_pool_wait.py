# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Model-free lifecycle and cancellation regressions; no database required."""
import asyncio
from datetime import datetime, timezone
import os
import unittest
from unittest.mock import AsyncMock, Mock, patch

os.environ.setdefault('MCP_EXECUTION_DATABASE_URI', 'postgresql://unused/unused')
import server


class WaitTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.ctx = Mock()
        self.ctx.request_context.meta.model_dump.return_value = {server.RECOVERY_META: {"version": 1, "execution_id": "e"}}
        self.store = AsyncMock()
        self.row = {'execution_id': 'e', 'call_id': 'c', 'marker': 'm', 'seconds': 1,
                    'pod': 'p', 'started_at': datetime.now(timezone.utc), 'result': None}
        self.store.admit.return_value = (self.row, True)
        self.patcher = patch.object(server, 'store', self.store)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    async def test_local_completion_does_not_poll(self):
        result = await server.long_wait('m', 1, self.ctx)
        self.assertEqual(result.structuredContent['call_id'], 'c')
        self.store.get.assert_not_awaited()
        self.store.complete.assert_awaited_once()
        self.assertFalse(server.active)

    async def test_duplicate_local_callers_share_one_physical_execution(self):
        self.store.admit.side_effect = [(self.row, True), (self.row, False)]
        first = asyncio.create_task(server.long_wait('m', 1, self.ctx))
        while not server.active:
            await asyncio.sleep(0)
        second = asyncio.create_task(server.long_wait('m', 1, self.ctx))
        results = await asyncio.gather(first, second)
        self.assertEqual(results[0], results[1])
        self.store.complete.assert_awaited_once()
        self.store.get.assert_not_awaited()

    async def test_failed_startup_closes_pool(self):
        self.store.open.side_effect = RuntimeError('startup unavailable')
        app = server.create_app('test').app
        with self.assertRaisesRegex(RuntimeError, 'startup unavailable'):
            async with app.router.lifespan_context(app):
                self.fail('Startup must fail before serving requests')
        self.store.close.assert_awaited_once()

    async def test_disconnect_does_not_cancel_execution_or_result_capture(self):
        waiter = asyncio.create_task(server.long_wait('m', 1, self.ctx))
        while not server.active:
            await asyncio.sleep(0)
        physical = server.active['e']
        waiter.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await waiter
        self.assertFalse(physical.cancelled())
        await physical
        self.store.complete.assert_awaited_once()

    async def test_peer_waits_before_polling_and_reads_durable_result(self):
        self.store.admit.return_value = (self.row, False)
        wire = {'content': [], 'structuredContent': {'call_id': 'original'}, 'isError': False}
        self.store.get.return_value = {**self.row, 'result': wire}
        with patch.object(server.asyncio, 'sleep', new_callable=AsyncMock) as sleep:
            result = await server.long_wait('m', 1, self.ctx)
        sleep.assert_awaited_once_with(1)
        self.store.get.assert_awaited_once_with('e')
        self.store.complete.assert_not_awaited()
        self.assertEqual(result.structuredContent['call_id'], 'original')

    async def test_result_capture_error_propagates_and_cleans_tracking(self):
        self.store.complete.side_effect = RuntimeError('database unavailable')
        with self.assertRaisesRegex(RuntimeError, 'database unavailable'):
            await server.long_wait('m', 1, self.ctx)
        self.assertFalse(server.active)

    async def test_shutdown_drains_before_closing_pool(self):
        app = server.create_app('test').app
        async with app.router.lifespan_context(app):
            task = asyncio.create_task(server.long_wait('m', 1, self.ctx))
            while not server.active:
                await asyncio.sleep(0)
        self.assertTrue(task.done())
        self.store.open.assert_awaited_once()
        self.store.complete.assert_awaited_once()
        self.store.close.assert_awaited_once()


if __name__ == '__main__':
    unittest.main()

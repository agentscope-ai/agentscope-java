# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Real PostgreSQL pool regressions; initialize execution-store.sql before running."""
import asyncio
import os
import unittest
import uuid
from execution_store import ExecutionStore


@unittest.skipUnless(os.environ.get('MCP_POOL_TEST_DSN'), 'Requires isolated fixture PostgreSQL')
class PoolTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.store = ExecutionStore(os.environ['MCP_POOL_TEST_DSN'])
        await self.store.open()
        self.addAsyncCleanup(self.store.close)

    async def test_concurrent_operations_reuse_bounded_connections(self):
        async def execution(_):
            identity = str(uuid.uuid4())
            row, created = await self.store.admit(identity, 'pool', 1)
            self.assertTrue(created)
            await self.store.complete(identity, {'content': [], 'isError': False})
            self.assertEqual((await self.store.get(identity))['state'], 'succeeded')
        await asyncio.gather(*(execution(i) for i in range(64)))
        stats = self.store.pool.get_stats()
        self.assertLessEqual(stats['connections_num'], 16)
        self.assertEqual(stats['requests_waiting'], 0)

    async def test_exception_and_cancellation_return_connections(self):
        ready = asyncio.Event()
        async def borrower():
            async with self.store.pool.connection():
                ready.set()
                await asyncio.Event().wait()
        task = asyncio.create_task(borrower())
        await ready.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        with self.assertRaisesRegex(RuntimeError, 'test'):
            async with self.store.pool.connection():
                raise RuntimeError('test')
        await self.store.check()
        stats = self.store.pool.get_stats()
        self.assertEqual(stats['pool_available'], stats['pool_size'])


if __name__ == '__main__':
    unittest.main()

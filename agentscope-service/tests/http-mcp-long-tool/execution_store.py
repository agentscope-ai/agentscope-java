# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Shared, append-only invocation identity and immutable results for the MCP recovery fixture."""
import hashlib
import json
import os
import uuid

from psycopg_pool import AsyncConnectionPool
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


class ExecutionStore:
    def __init__(self, dsn):
        self.pool = AsyncConnectionPool(
            dsn, open=False, min_size=2,
            max_size=int(os.environ.get('MCP_DATABASE_POOL_SIZE', '16')),
            timeout=10, max_waiting=256,
            kwargs={'autocommit': True, 'row_factory': dict_row, 'connect_timeout': 10})

    async def open(self):
        await self.pool.open(wait=True, timeout=30)

    async def close(self):
        await self.pool.close()

    async def check(self):
        async with self.pool.connection() as connection:
            await connection.execute('SELECT execution_id FROM mcp_fixture.execution LIMIT 0')

    async def admit(self, execution_id, marker, seconds):
        request_hash = hashlib.sha256(json.dumps({'tool': 'long_wait', 'marker': marker, 'seconds': seconds},
                                                sort_keys=True).encode()).hexdigest()
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                'INSERT INTO mcp_fixture.execution (execution_id,request_hash,marker,seconds,call_id,pod) '
                'VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT (execution_id) DO NOTHING RETURNING *',
                (execution_id, request_hash, marker, seconds, str(uuid.uuid4()), os.environ.get('HOSTNAME', 'local')))
            row = await cursor.fetchone()
            created = row is not None
            if not created:
                cursor = await connection.execute('SELECT * FROM mcp_fixture.execution WHERE execution_id=%s',
                                                  (execution_id,))
                row = await cursor.fetchone()
            if row['request_hash'] != request_hash:
                raise ValueError('execution_id already admitted with different arguments')
            return row, created

    async def get(self, execution_id):
        async with self.pool.connection() as connection:
            cursor = await connection.execute('SELECT * FROM mcp_fixture.execution WHERE execution_id=%s',
                                              (execution_id,))
            return await cursor.fetchone()

    async def complete(self, execution_id, result):
        async with self.pool.connection() as connection:
            await connection.execute(
                "UPDATE mcp_fixture.execution SET state='succeeded',result=%s WHERE execution_id=%s AND state='running'",
                (Jsonb(result), execution_id))

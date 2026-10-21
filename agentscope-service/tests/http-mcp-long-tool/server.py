#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Cluster-only MCP fixture: wait without shell, files, or business side effects."""

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hmac
import json
import os
from typing import Annotated
import uuid

from mcp.server.fastmcp import Context, FastMCP
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import Field
from starlette.responses import JSONResponse
import uvicorn
from execution_store import ExecutionStore


mcp = FastMCP("AgentScope Service long tool test", stateless_http=True, json_response=True,
              host="0.0.0.0", port=8000)
RECOVERY_META = 'io.agentscope/execution-recovery'
store = ExecutionStore(os.environ['MCP_EXECUTION_DATABASE_URI'])
active = {}
POLL_SECONDS = float(os.environ.get('MCP_EXECUTION_POLL_SECONDS', '1'))
if not 0.1 <= POLL_SECONDS <= 30:
    raise ValueError('MCP_EXECUTION_POLL_SECONDS must be between 0.1 and 30')


def timestamp():
    return datetime.now(timezone.utc).isoformat()


async def perform(row):
    result = {'call_id': row['call_id'], 'marker': row['marker'], 'seconds': row['seconds'],
              'pod': row['pod'], 'started_at': row['started_at'].isoformat()}
    print(json.dumps({'event': 'long_wait.started', 'execution_id': row['execution_id'], **result}), flush=True)
    await asyncio.sleep(row['seconds'])
    result['finished_at'] = timestamp()
    wire = CallToolResult(content=[TextContent(type='text', text=json.dumps(result))],
                          structuredContent=result, isError=False)
    await store.complete(row['execution_id'], wire.model_dump(by_alias=True, exclude_none=True))
    print(json.dumps({'event': 'long_wait.completed', 'execution_id': row['execution_id'], **result}), flush=True)
    return wire


@mcp.tool(meta={RECOVERY_META: {'version': 1, 'queryTool': 'managed_execution_get'}})
async def long_wait(
    marker: Annotated[str, Field(min_length=1, max_length=120)],
    seconds: Annotated[int, Field(ge=1, le=180)] = 90,
    ctx: Context = None,
) -> CallToolResult:
    """Actually wait for the requested seconds, then return immutable invocation evidence.

    Call once per requested test. No shell, files, or external business actions are performed.
    """
    meta = ctx.request_context.meta if ctx else None
    recovery = meta.model_dump().get(RECOVERY_META) if meta else None
    if recovery is not None:
        if recovery.get('version') != 1 or not isinstance(recovery.get('execution_id'), str):
            raise ValueError('Invalid execution recovery metadata')
        execution_id = recovery['execution_id']
        if not 1 <= len(execution_id) <= 256:
            raise ValueError('Invalid execution identity')
    else:
        execution_id = 'direct_' + str(uuid.uuid4())
    row, created = await store.admit(execution_id, marker, seconds)
    if created:
        task = asyncio.create_task(perform(row))
        active[execution_id] = task
        def completed(done):
            active.pop(execution_id, None)
            if not done.cancelled() and done.exception() is not None:
                print(json.dumps({'event': 'long_wait.failed', 'execution_id': execution_id}), flush=True)
        task.add_done_callback(completed)
    # Shield the physical execution from HTTP cancellation. Local waiters need no polling.
    task = active.get(execution_id)
    if task is not None:
        return await asyncio.shield(task)
    while row['result'] is None:
        await asyncio.sleep(POLL_SECONDS)
        row = await store.get(execution_id)
    return CallToolResult.model_validate(row['result'])


@mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, idempotentHint=True))
async def managed_execution_get(execution_id: Annotated[str, Field(min_length=1, max_length=256)]) -> CallToolResult:
    """Read an original execution without starting or repeating it."""
    row = await store.get(execution_id)
    if row:
        print(json.dumps({'event': 'execution.queried', 'execution_id': execution_id,
                          'marker': row['marker'], 'call_id': row['call_id'], 'state': row['state'],
                          'pod': os.environ.get('HOSTNAME', 'local')}), flush=True)
    result = {'version': 1, 'execution_id': execution_id, 'state': row['state'] if row else 'not_found',
              **({'result': row['result']} if row and row['result'] is not None else {})}
    return CallToolResult(content=[TextContent(type='text', text=json.dumps(result))],
                          structuredContent=result, isError=False)


@mcp.custom_route("/healthz", methods=["GET"])
async def health(_request):
    await store.check()
    return JSONResponse({"status": "UP"})


class BearerAuth:
    def __init__(self, app, token):
        self.app = app
        self.expected = ("Bearer " + token).encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope["path"] != "/healthz":
            supplied = dict(scope.get("headers", [])).get(b"authorization", b"")
            if not hmac.compare_digest(supplied, self.expected):
                await JSONResponse({"error": "Unauthorized"}, status_code=401)(scope, receive, send)
                return
        await self.app(scope, receive, send)


def create_app(token):
    app = mcp.streamable_http_app()
    original_lifespan = app.router.lifespan_context

    @asynccontextmanager
    async def lifespan(application):
        try:
            await store.open()
            async with original_lifespan(application) as state:
                yield state
        finally:
            # Drain admitted operations before closing their result-capture connections.
            try:
                if active:
                    await asyncio.gather(*list(active.values()), return_exceptions=True)
            finally:
                await store.close()

    app.router.lifespan_context = lifespan
    return BearerAuth(app, token)


if __name__ == "__main__":
    token = os.environ["MCP_TEST_TOKEN"]
    if not token:
        raise ValueError("MCP_TEST_TOKEN must not be empty")
    uvicorn.run(create_app(token), host="0.0.0.0", port=8000,
                access_log=False)

#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Storage-free MCP performance fixture. No durable execution or recovery contract."""
import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hmac
import json
import os
from typing import Annotated
import uuid

from mcp.server.fastmcp import FastMCP
from mcp.types import CallToolResult, TextContent
from pydantic import Field
from starlette.responses import JSONResponse
import uvicorn
from health import HealthMonitor
from telemetry import TraceSink

mcp = FastMCP("Managed Agents performance tool", stateless_http=True, json_response=True,
              host="0.0.0.0", port=8000)
active = set()
trace = TraceSink()
counters = {'started': 0, 'completed': 0, 'cancelled': 0, 'peak_active': 0}
health_monitor = HealthMonitor(active, counters, trace)


def timestamp():
    return datetime.now(timezone.utc).isoformat()


async def perform(marker, seconds):
    call_id = str(uuid.uuid4())
    result = {'call_id': call_id, 'marker': marker, 'seconds': seconds,
              'pod': os.environ.get('HOSTNAME', 'local'), 'started_at': timestamp()}
    counters['started'] += 1
    trace.emit({'event': 'long_wait.started', 'execution_id': call_id, **result})
    try:
        await asyncio.sleep(seconds)
    except asyncio.CancelledError:
        counters['cancelled'] += 1
        trace.emit({'event': 'long_wait.cancelled', 'execution_id': call_id, **result})
        raise
    result['finished_at'] = timestamp()
    counters['completed'] += 1
    trace.emit({'event': 'long_wait.completed', 'execution_id': call_id, **result})
    return CallToolResult(content=[TextContent(type='text', text=json.dumps(result))],
                          structuredContent=result, isError=False)


@mcp.tool()
async def long_wait(
    marker: Annotated[str, Field(min_length=1, max_length=120)],
    seconds: Annotated[float, Field(ge=0.1, le=180, allow_inf_nan=False)] = 90,
) -> CallToolResult:
    """Wait asynchronously and return a unique call ID and actual timestamps.

    Every call is independent. No cross-call deduplication or persistent result lookup.
    """
    task = asyncio.create_task(perform(marker, seconds))
    active.add(task)
    counters['peak_active'] = max(counters['peak_active'], len(active))
    task.add_done_callback(active.discard)
    # An HTTP disconnect does not change the simulated external execution.
    # The result is discarded once its waiter/task is gone; a process restart loses all state.
    return await asyncio.shield(task)


@mcp.custom_route("/healthz", methods=["GET"])
async def health(_request):
    return JSONResponse({"status": "UP"})


@mcp.custom_route("/readyz", methods=["GET"])
async def ready(_request):
    state = health_monitor.snapshot()
    return JSONResponse(state, status_code=200 if state['ready'] else 503)


@mcp.custom_route("/diagnostics", methods=["GET"])
async def diagnostics(_request):
    return JSONResponse(health_monitor.snapshot())


class BearerAuth:
    def __init__(self, app, token):
        self.app = app
        self.expected = ("Bearer " + token).encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope["path"] not in ("/healthz", "/readyz"):
            supplied = dict(scope.get("headers", [])).get(b"authorization", b"")
            if not hmac.compare_digest(supplied, self.expected):
                await JSONResponse({"error": "Unauthorized"}, status_code=401)(scope, receive, send)
                return
        await self.app(scope, receive, send)


def create_app(token):
    if not token:
        raise ValueError("MCP_TEST_TOKEN must not be empty")
    app = mcp.streamable_http_app()
    original_lifespan = app.router.lifespan_context

    @asynccontextmanager
    async def lifespan(application):
        trace.start()
        health_monitor.start()
        try:
            async with original_lifespan(application) as state:
                yield state
        finally:
            await health_monitor.close()
            if active:
                await asyncio.gather(*list(active), return_exceptions=True)
            await asyncio.to_thread(trace.close)

    app.router.lifespan_context = lifespan
    return BearerAuth(app, token)


if __name__ == "__main__":
    uvicorn.run(create_app(os.environ["MCP_TEST_TOKEN"]), host="0.0.0.0", port=8000,
                access_log=False)

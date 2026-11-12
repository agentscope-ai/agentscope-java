# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Synthetic, local-only side effects for runtime restart and ordering acceptance."""
import asyncio
from contextvars import ContextVar
import hmac
import json
import os
import time

from mcp.server.fastmcp import FastMCP
from mcp.types import CallToolResult, TextContent, Tool
import uvicorn
from evaluation_gateway import CaseStore


trial = ContextVar('fixture_trial')
store = CaseStore(os.environ['EVAL_GATEWAY_DB'])
server = FastMCP('acceptance', stateless_http=True, json_response=True, host='0.0.0.0', port=8003)


@server._mcp_server.list_tools()
async def tools():
    return [Tool(name='record_and_wait', description='Record the supplied value exactly once, wait, and return it. '
                 'This is a non-idempotent local fixture. Do not call it again to check progress.',
                 inputSchema={'type': 'object', 'properties': {'value': {'type': 'string'},
                              'seconds': {'type': 'integer', 'minimum': 0, 'maximum': 40}},
                              'required': ['value', 'seconds'], 'additionalProperties': False}),
            Tool(name='result_fixture', description='Return a deterministic test result. Error and overflow variants are intentional; never retry them.',
                 inputSchema={'type': 'object', 'properties': {'variant': {'type': 'string',
                     'enum': ['nested', 'long', 'empty', 'error', 'overflow']}}, 'required': ['variant'], 'additionalProperties': False})]


@server._mcp_server.call_tool()
async def call(name, arguments):
    if name == 'result_fixture':
        store.active(trial=trial.get())
        variant = arguments.get('variant')
        if variant == 'nested':
            return CallToolResult(content=[TextContent(type='text', text='nested fixture')],
                                  structuredContent={'nested': [None, {'escaped': 'a\\b\n', 'empty': []}]})
        if variant in {'long', 'overflow'}:
            return CallToolResult(content=[TextContent(type='text', text='x' * (50000 if variant == 'long' else 70000))])
        if variant == 'empty':
            return CallToolResult(content=[], structuredContent={})
        if variant == 'error':
            return CallToolResult(content=[TextContent(type='text', text='Expected fixture error. Do not retry.')], isError=True)
        raise ValueError('Unknown result fixture variant')
    if name != 'record_and_wait' or not isinstance(arguments.get('value'), str) or \
            type(arguments.get('seconds')) is not int or not 0 <= arguments['seconds'] <= 40:
        raise ValueError('Invalid fixture request')
    identity = trial.get()
    store.active(trial=identity)
    with store.db() as db:
        row = db.execute('INSERT INTO fixture_calls(trial,value,started) VALUES (?,?,?)',
                         (identity, arguments['value'], time.time())).lastrowid
    await asyncio.sleep(arguments['seconds'])
    with store.db() as db:
        db.execute('UPDATE fixture_calls SET finished=? WHERE id=?', (time.time(), row))
    return [TextContent(type='text', text=json.dumps({'value': arguments['value'], 'record': row}))]


class Auth:
    async def __call__(self, scope, receive, send):
        headers = dict(scope.get('headers', []))
        if scope['type'] == 'http':
            expected = ('Bearer ' + os.environ['EVAL_TOOL_KEY']).encode()
            if not hmac.compare_digest(headers.get(b'authorization', b''), expected):
                await send({'type': 'http.response.start', 'status': 403, 'headers': []})
                await send({'type': 'http.response.body', 'body': b'Forbidden'})
                return
            token = trial.set(headers.get(b'x-eval-trial', b'').decode())
            try:
                await app(scope, receive, send)
            finally:
                trial.reset(token)
        else:
            await app(scope, receive, send)


app = server.streamable_http_app()
if __name__ == '__main__':
    uvicorn.run(Auth(), host='0.0.0.0', port=8003)

# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Fenced MCP server embedded in the Harbor controller, outside task Pods."""
from contextvars import ContextVar
import hmac
import json

from mcp.server.fastmcp import FastMCP
from mcp.types import CallToolResult, TextContent, Tool

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown
from sandbox_eval.terminal_bridge import TOOL_CATALOG


class TerminalMCP:
    def __init__(self, tool_key):
        self.tool_key = tool_key
        self.bindings = {}
        self.unknown = {}
        self.trial = ContextVar('terminal_trial')
        self.server = FastMCP('terminal', stateless_http=True, json_response=True, host='0.0.0.0')

        @self.server._mcp_server.list_tools()
        async def list_tools():
            self._active()
            return [Tool(**tool) for tool in TOOL_CATALOG]

        @self.server._mcp_server.call_tool()
        async def call_tool(name, arguments):
            identity, bridge = self._active()
            try:
                result = await bridge.call(name, arguments)
            except ExecutionOutcomeUnknown as error:
                self.unknown[identity] = str(error)
                await bridge.close()
                raise
            if result.get('result', {}).get('status') == 'unknown':
                self.unknown[identity] = 'Terminal operation has no confirmed receipt'
                await bridge.close()
            return CallToolResult(content=[TextContent(type='text', text=json.dumps(result))],
                                  isError='error' in result or identity in self.unknown)

        self.inner = self.server.streamable_http_app()

    def bind(self, identity, bridge):
        if not identity or identity in self.bindings:
            raise ValueError('Trial already registered or invalid')
        self.bindings[identity] = bridge

    def _active(self):
        identity = self.trial.get()
        bridge = self.bindings.get(identity)
        if bridge is None or bridge.closed or identity in self.unknown:
            raise ValueError('No active terminal for this trial')
        return identity, bridge

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.inner(scope, receive, send)
        headers = dict(scope.get('headers', []))
        identity = headers.get(b'x-eval-trial', b'').decode()
        if not hmac.compare_digest(headers.get(b'authorization', b''),
                                   ('Bearer ' + self.tool_key).encode()) or identity not in self.bindings:
            await send({'type': 'http.response.start', 'status': 403, 'headers': []})
            await send({'type': 'http.response.body', 'body': b'Forbidden'})
            return
        token = self.trial.set(identity)
        try:
            await self.inner(scope, receive, send)
        finally:
            self.trial.reset(token)

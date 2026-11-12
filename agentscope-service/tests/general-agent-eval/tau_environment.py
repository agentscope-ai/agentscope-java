# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""One isolated tau-bench environment slot. Control and MCP have separate keys."""
import hmac
import json
import os
import threading
from contextvars import ContextVar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from environment_leases import EnvironmentLeases

import uvicorn
from mcp.server.fastmcp import FastMCP
from mcp.types import TextContent, Tool
from tau_bench.envs import get_env
from tau_bench.types import Action, RESPOND_ACTION_NAME


class BridgeUser:
    """User simulation runs in the trusted Runner, never in the tested agent."""
    observation = ""

    def reset(self, instruction=None):
        return self.observation

    def step(self, content):
        return self.observation

    def get_total_cost(self):
        return 0


class Slot:
    def __init__(self):
        self.lock = threading.RLock()
        self.env = None
        self.done = True

    def reset(self, task, split, trial):
        if not self.done:
            raise ValueError("Cannot reset an active trial")
        self.env = get_env("retail", "human", "unused", split, task_index=task)
        self.env.user = BridgeUser()
        self.env.reset(task)
        self.done, self.reward, self.reward_info = False, 0.0, None
        self.trial, self.actions, self.limit = trial, [], 30
        return {"instruction": self.env.task.instruction, "wiki": self.env.wiki,
                "initial_hash": self.env.get_data_hash(), "tools": self.env.tools_info}

    def step(self, name, arguments):
        if self.done:
            raise ValueError("Trial is terminal")
        action = Action(name=name, kwargs=arguments)
        self.actions.append(action.model_dump())
        response = self.env.step(action)
        self.done = response.done or len(self.actions) >= self.limit
        if response.done:
            self.reward = response.reward
            self.reward_info = response.info.reward_info.model_dump()
        return response.observation

    def result(self):
        return {"trial": self.trial, "done": self.done, "reward": self.reward,
                "reward_info": self.reward_info, "actions": self.actions}


slot = Slot()
request_trial = ContextVar('evaluation_trial', default=None)
leases = EnvironmentLeases(os.environ.get('EVAL_LEASE_DB', '/eval/environment-leases.sqlite'))
mcp = FastMCP("tau-retail", stateless_http=True, json_response=True,
              host="0.0.0.0", port=8000)


@mcp._mcp_server.list_tools()
async def list_tools():
    with slot.lock:
        if request_trial.get() != getattr(slot, 'trial', None):
            raise ValueError('Stale evaluation trial')
        if slot.env is None:
            return []
        return [Tool(name=t["function"]["name"], description=t["function"]["description"],
                     inputSchema=t["function"]["parameters"]) for t in slot.env.tools_info]


@mcp._mcp_server.call_tool()
async def call_tool(name, arguments):
    with slot.lock:
        if request_trial.get() != getattr(slot, 'trial', None):
            raise ValueError('Stale evaluation trial')
        return [TextContent(type="text", text=slot.step(name, arguments))]


class Control(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        expected = "Bearer " + os.environ["EVAL_CONTROL_KEY"]
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            self.send_error(403)
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            with slot.lock:
                if self.path.startswith('/lease/'):
                    operation = self.path.rsplit('/', 1)[1]
                    if operation == 'release' and body.get('slot') == 'tau' and not slot.done:
                        raise ValueError('Cannot release an active tau trial')
                    result = leases.operate(operation, body.get('slot'), body.get('owner'))
                else:
                    leases.operate('check', 'tau', body.get('owner'))
                if self.path == "/reset":
                    result = slot.reset(body["task"], body["split"], body["trial"])
                elif self.path == "/respond":
                    slot.env.user.observation = body["user_observation"]
                    slot.step(RESPOND_ACTION_NAME, {"content": body["content"]})
                    result = slot.result()
                elif self.path == "/result":
                    result = slot.result()
                elif self.path == "/abort":
                    slot.done = True
                    result = slot.result()
                elif not self.path.startswith("/lease/"):
                    self.send_error(404)
                    return
            data = json.dumps(result).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as error:
            self.send_error(400, str(error))


class ToolAuth:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            actual = dict(scope.get("headers", [])).get(b"authorization", b"")
            expected = ("Bearer " + os.environ["EVAL_TOOL_KEY"]).encode()
            if not hmac.compare_digest(actual, expected):
                await send({"type": "http.response.start", "status": 403, "headers": []})
                await send({"type": "http.response.body", "body": b"Forbidden"})
                return
            # A late call from a previous case must never mutate the next case's database.
            trial = dict(scope.get('headers', [])).get(b'x-eval-trial', b'').decode()
            with slot.lock:
                current_trial = getattr(slot, 'trial', None)
            if not current_trial or trial != current_trial:
                await send({'type': 'http.response.start', 'status': 409, 'headers': []})
                await send({'type': 'http.response.body', 'body': b'Stale evaluation trial'})
                return
            token = request_trial.set(trial)
            try:
                await self.app(scope, receive, send)
            finally:
                request_trial.reset(token)
        else:
            await self.app(scope, receive, send)


if __name__ == "__main__":
    control = ThreadingHTTPServer(("0.0.0.0", 8001), Control)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    uvicorn.run(ToolAuth(mcp.streamable_http_app()), host="0.0.0.0", port=8000)

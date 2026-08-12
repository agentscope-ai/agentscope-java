# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""内嵌 HTTP 合约服务（控制面 → 数据面按需拉取 + 命令，contract.md /
sdk-design §4）。零依赖：标准库 ``http.server``，后台线程服务。

端点（与 Go ``connector.ContractServer`` 逐一对齐）：

    GET  /agentscope/info
    GET  /agentscope/health
    GET  /agentscope/sessions
    GET  /agentscope/sessions/{id}/state
    GET  /agentscope/sessions/{id}/context
    GET  /agentscope/sessions/{id}/messages?offset=&limit=
    GET  /agentscope/subagents
    GET  /agentscope/workspaces
    POST /agentscope/sessions/{id}/compress
    POST /agentscope/sessions/{id}/terminate
    POST /agentscope/sessions/{id}/abort
    GET  /agentscope/sessions/{id}/tasks

provider 协议（由 ``SessionBridge`` 实现）：``info()`` / ``sessions()`` /
``session_state(id)`` / ``context(id)`` / ``messages(id, offset, limit)`` /
``subagents()`` / ``workspaces()`` / ``compress(id)`` / ``terminate(id)`` /
``abort(id)`` / ``tasks(id)``，
返回 JSON 可序列化 dict/list；抛 ``ContractNotFoundError`` → 404，
``NotImplementedError`` → 501，其余异常 → 500。
"""
from __future__ import annotations

import json
import socketserver
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Optional, Tuple
from urllib.parse import parse_qs, urlparse


class ContractNotFoundError(Exception):
    """provider 抛出此异常表示资源不存在（→ 404）。"""


class _ContractThreadingHTTPServer(ThreadingHTTPServer):
    """跳过 ``HTTPServer.server_bind`` 中的 ``getfqdn`` 反查。

    标准库在绑定时对 host 做 FQDN 解析，空 host（全接口监听）在无 DNS 的
    环境里会阻塞约 5s；合约服务不需要 canonical name。
    """

    def server_bind(self) -> None:
        socketserver.TCPServer.server_bind(self)
        host, port = self.server_address[:2]
        self.server_name = host or "0.0.0.0"
        self.server_port = port


def _query_int(qs: dict, key: str, default: int) -> int:
    raw = qs.get(key, [""])[0]
    try:
        value = int(raw)
        return value if value >= 0 else default
    except (TypeError, ValueError):
        return default


class ContractHTTPServer:
    """绑定即服务的数据面合约 HTTP 服务（后台线程）。"""

    def __init__(self, host: str, port: int, provider: Any) -> None:
        handler_cls = _make_handler(provider)
        self._server = _ContractThreadingHTTPServer((host, port), handler_cls)
        self._server.daemon_threads = True
        self._thread: Optional[threading.Thread] = None

    @property
    def address(self) -> Tuple[str, int]:
        """实际绑定地址（``port=0`` 时有用）。"""
        return self._server.server_address[:2]

    @property
    def port(self) -> int:
        return self._server.server_address[1]

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(
            target=self._server.serve_forever, name="aistio-contract-http", daemon=True
        )
        self._thread.start()

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None


def _make_handler(provider: Any) -> type:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args: Any) -> None:  # 静默访问日志
            pass

        # ─── 响应 helpers ───

        def _json(self, status: int, obj: Any) -> None:
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _error(self, status: int, msg: str) -> None:
            self._json(status, {"error": msg})

        # ─── 路由 ───

        def _route(self, method: str) -> None:
            parsed = urlparse(self.path)
            parts = [p for p in parsed.path.split("/") if p]
            if not parts or parts[0] != "agentscope":
                return self._error(404, "not found")
            try:
                if parts == ["agentscope", "info"] and method == "GET":
                    return self._json(200, provider.info())
                if parts == ["agentscope", "health"] and method == "GET":
                    return self._json(200, {"status": "ok"})
                if parts == ["agentscope", "sessions"] and method == "GET":
                    return self._json(200, {"sessions": provider.sessions() or []})
                if parts == ["agentscope", "subagents"] and method == "GET":
                    return self._json(200, {"subagents": provider.subagents() or []})
                if parts == ["agentscope", "workspaces"] and method == "GET":
                    return self._json(200, {"workspaces": provider.workspaces() or []})
                if len(parts) == 4 and parts[0] == "agentscope" and parts[1] == "sessions":
                    session_id, action = parts[2], parts[3]
                    if action == "state" and method == "GET":
                        return self._json(200, provider.session_state(session_id))
                    if action == "context" and method == "GET":
                        return self._json(200, provider.context(session_id))
                    if action == "messages" and method == "GET":
                        qs = parse_qs(parsed.query)
                        offset = _query_int(qs, "offset", 0)
                        limit = _query_int(qs, "limit", 100)
                        return self._json(200, provider.messages(session_id, offset, limit))
                    if action == "compress" and method == "POST":
                        provider.compress(session_id)
                        return self._json(
                            200,
                            {
                                "sessionId": session_id,
                                "command": "compress",
                                "status": "initiated",
                            },
                        )
                    if action == "terminate" and method == "POST":
                        provider.terminate(session_id)
                        return self._json(
                            200,
                            {
                                "sessionId": session_id,
                                "command": "terminate",
                                "status": "initiated",
                            },
                        )
                    if action == "abort" and method == "POST":
                        provider.abort(session_id)
                        return self._json(
                            200,
                            {
                                "sessionId": session_id,
                                "command": "abort",
                                "status": "initiated",
                            },
                        )
                    if action == "tasks" and method == "GET":
                        return self._json(200, {"tasks": provider.tasks(session_id) or []})
                return self._error(404, "not found")
            except ContractNotFoundError as exc:
                return self._error(404, str(exc) or "not found")
            except NotImplementedError as exc:
                return self._error(501, str(exc) or "data plane does not support this operation")
            except Exception as exc:
                return self._error(500, str(exc))

        def do_GET(self) -> None:  # noqa: N802 (stdlib naming)
            self._route("GET")

        def do_POST(self) -> None:  # noqa: N802
            self._route("POST")

    return Handler

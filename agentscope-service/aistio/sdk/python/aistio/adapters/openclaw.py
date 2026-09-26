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

"""OpenClaw 适配器（P2）：Gateway RPC 轮询（零侵入路径 B，
framework-integration §3.5 OpenClaw 节）。

``target`` 可以是：
- Gateway URL（``str``，含 ``openclaw`` 字样）—— SDK 尝试经 ``websockets``
  建立最小 RPC 客户端（依赖在场时才可用）；
- 已构造的 gateway client（任何带 ``call(method, params)`` 方法的对象，
  例如 OpenClaw Plugin 内封装好的会话 API 句柄）—— 直接使用。

OpenClaw Session 数据模型（§3.5 表）：元数据在 ``session_nodes``、对话在
``transcript_events``，经 ``sessions.list`` / ``sessions.get`` /
``sessions.preview`` RPC 访问。
"""
from __future__ import annotations

import inspect
import json
from typing import Any, Callable, Dict, List, Optional

from ..context import ContextMessage, ContextSnapshot
from ..events import MessageItem, MessagePage, SessionEvent
from ..inventory import SubagentInfo, WorkspaceInfo
from .base import FrameworkAdapter


class _WebSocketGatewayClient:
    """最小 OpenClaw Gateway WebSocket RPC 客户端（websockets 库在场时可用）。

    协议形状：JSON-RPC 风格 ``{"method": ..., "params": ...}`` 请求 /
    ``{"result": ...}`` 响应。仅覆盖只读轮询所需的 ``call`` 能力。
    """

    def __init__(self, url: str, token: Optional[str] = None) -> None:
        self._url = url
        self._token = token
        self._ws: Any = None
        self._req_id = 0

    async def _ensure_conn(self) -> Any:
        if self._ws is not None:
            return self._ws
        import websockets  # 延迟导入：可选依赖

        headers = {}
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        self._ws = await websockets.connect(self._url, additional_headers=headers or None)
        return self._ws

    async def call(self, method: str, params: Optional[Dict[str, Any]] = None) -> Any:
        ws = await self._ensure_conn()
        self._req_id += 1
        await ws.send(
            json.dumps({"id": self._req_id, "method": method, "params": params or {}})
        )
        raw = await ws.recv()
        payload = json.loads(raw)
        if "error" in payload:
            raise RuntimeError(f"gateway RPC {method} failed: {payload['error']}")
        return payload.get("result")

    async def close(self) -> None:
        if self._ws is not None:
            try:
                await self._ws.close()
            finally:
                self._ws = None


class OpenClawAdapter(FrameworkAdapter):
    """OpenClaw Gateway / Plugin 适配器。"""

    def __init__(self, token: Optional[str] = None) -> None:
        self._token = token
        self._client: Any = None
        self._owns_client = False

    def framework_name(self) -> str:
        return "openclaw"

    def can_handle(self, target: Any) -> bool:
        if isinstance(target, str):
            return "openclaw" in target.lower()
        return callable(getattr(target, "call", None))

    # ─── 拦截（轮询式，无框架内 hook；事件由 bridge 周期快照近似）───

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        if isinstance(target, str):
            self._client = _WebSocketGatewayClient(target, token=self._token)
            self._owns_client = True
        else:
            self._client = target
            self._owns_client = False

    def detach(self) -> None:
        client, self._client = self._client, None
        if self._owns_client and client is not None:
            close = getattr(client, "close", None)
            if close is not None:
                result = close()
                # detach 是同步接口；协程交由调用方事件循环调度。
                if inspect.isawaitable(result):
                    try:
                        import asyncio

                        asyncio.get_event_loop().create_task(result)  # noqa: RUF006
                    except RuntimeError:
                        pass
        self._owns_client = False

    async def _rpc(self, method: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if self._client is None:
            raise RuntimeError("openclaw adapter not attached")
        result = self._client.call(method, params or {})
        if inspect.isawaitable(result):
            result = await result
        return result

    # ─── Level 4：sessions.get + sessions.preview 重建生效 Context ───

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        session = await self._rpc("sessions.get", {"sessionId": session_id})
        preview = await self._rpc("sessions.preview", {"sessionId": session_id})
        events = (preview or {}).get("events", []) or []
        messages = [
            ContextMessage(role=e.get("role", "assistant"), content=e.get("content", "") or "")
            for e in events
        ]
        session = session or {}
        framework_state = {
            "status": session.get("status"),
            "model": session.get("model"),
            "modelProvider": session.get("modelProvider"),
            "createdVia": session.get("createdVia"),
        }
        framework_state = {k: v for k, v in framework_state.items() if v is not None}
        return ContextSnapshot(
            session_id=session_id,
            messages=messages,
            framework=self.framework_name(),
            framework_state=SessionEvent.encode_meta(framework_state) if framework_state else b"",
        )

    # ─── Level 3：transcript 全文分页 ───

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        preview = await self._rpc("sessions.preview", {"sessionId": session_id})
        events = (preview or {}).get("events", []) or []
        items = [
            MessageItem(
                seq=idx,
                role=e.get("role", "assistant"),
                content=e.get("content", "") or "",
                tool_name=e.get("toolName", "") or "",
            )
            for idx, e in enumerate(events, start=1)
        ]
        page = items[offset : offset + limit] if offset >= 0 else items[:limit]
        return MessagePage(
            session_id=session_id, messages=page, offset=offset, limit=limit, total=len(items)
        )

    # ─── Inventory（平台能力允许时）───

    async def list_subagents(self) -> List[SubagentInfo]:
        try:
            result = await self._rpc("agents.list", {})
        except Exception:
            return []
        agents = (result or {}).get("agents", result if isinstance(result, list) else []) or []
        return [
            SubagentInfo(
                name=a.get("name", "") or a.get("agentId", ""),
                description=a.get("description", "") or "",
                url=a.get("url", "") or "",
            )
            for a in agents
        ]

    async def workspace_info(self) -> List[WorkspaceInfo]:
        try:
            result = await self._rpc("workspaces.list", {})
        except Exception:
            return []
        workspaces = (
            (result or {}).get("workspaces", result if isinstance(result, list) else []) or []
        )
        return [
            WorkspaceInfo(
                path=w.get("path", ""),
                mode=w.get("mode", "") or "",
                size_bytes=int(w.get("sizeBytes") or 0),
                owner_ref=w.get("ownerRef", "") or "",
            )
            for w in workspaces
        ]

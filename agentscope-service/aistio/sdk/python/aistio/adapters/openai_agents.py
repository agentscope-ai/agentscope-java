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

"""OpenAI Agents SDK 适配器（P3，按需）：包装 session backend 的
``add_items`` 旁路复制（framework-integration §8 openai_agents.py）。

OpenAI Agents SDK 的 Session 协议形状（duck-typed）：
``session_id`` 属性 + ``async add_items(items)`` / ``async get_items([limit])``
/ ``async pop_item()`` / ``async clear_session()``。
"""
from __future__ import annotations

import inspect
from typing import Any, Callable, List, Optional

from ..context import ContextMessage, ContextSnapshot
from ..events import EVENT_MESSAGE, MessageItem, MessagePage, SessionEvent
from .base import COMMAND_TERMINATE, FrameworkAdapter, get_field


def _item_role(item: Any) -> str:
    return get_field(item, "role", "assistant") or "assistant"


def _item_content(item: Any) -> str:
    content = get_field(item, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        # OpenAI content parts：[{"type": "text"|"input_text"|"output_text", "text": ...}]
        parts = []
        for block in content:
            text = get_field(block, "text")
            if isinstance(text, str):
                parts.append(text)
        return "\n".join(parts)
    return str(content) if content is not None else ""


class _InterceptingSessionBackend:
    """装饰器：包装原有 session backend，``add_items`` 旁路发事件。"""

    def __init__(self, inner: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._inner = inner
        self._emit = emit

    async def add_items(self, items: Any) -> None:
        result = self._inner.add_items(items)
        if inspect.isawaitable(result):
            await result
        try:
            session_id = getattr(self._inner, "session_id", "") or ""
            for item in items or []:
                self._emit(
                    SessionEvent(
                        session_id=session_id,
                        seq=0,
                        event_type=EVENT_MESSAGE,
                        role=_item_role(item),
                        content=_item_content(item),
                    )
                )
        except Exception:
            pass

    def __getattr__(self, name: str) -> Any:
        return getattr(self._inner, name)


class OpenAIAgentsAdapter(FrameworkAdapter):
    """OpenAI Agents SDK session backend 适配器。

    ``target`` 可以是 session backend 本身（``SQLiteSession`` 等），也可以是
    持有 ``session`` 属性的 Agent 对象。
    """

    def __init__(self) -> None:
        self._target: Any = None
        self._holder: Any = None  # 实际被替换 session 属性的对象
        self._original: Any = None

    def framework_name(self) -> str:
        return "openai-agents"

    def framework_version(self) -> str:
        try:
            from importlib.metadata import version

            return version("openai-agents")
        except Exception:
            return ""

    def can_handle(self, target: Any) -> bool:
        mod = (type(target).__module__ or "").lower()
        if "openai" in mod or "agents" in mod:
            return True
        if callable(getattr(target, "add_items", None)) and callable(
            getattr(target, "get_items", None)
        ):
            return True
        session = getattr(target, "session", None)
        return session is not None and callable(getattr(session, "add_items", None))

    # ─── 拦截 ───

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._target = target
        if callable(getattr(target, "add_items", None)):
            # target 本身就是 session backend：无法整体替换引用，改为包装其方法。
            self._holder = target
            self._original = target.add_items

            async def add_items(items: Any, _orig=self._original, _emit=emit, _self=self) -> None:
                result = _orig(items)
                if inspect.isawaitable(result):
                    await result
                try:
                    session_id = getattr(target, "session_id", "") or ""
                    for item in items or []:
                        _emit(
                            SessionEvent(
                                session_id=session_id,
                                seq=0,
                                event_type=EVENT_MESSAGE,
                                role=_item_role(item),
                                content=_item_content(item),
                            )
                        )
                except Exception:
                    pass

            target.add_items = add_items
            return

        session = getattr(target, "session", None)
        if session is not None:
            self._holder = target
            self._original = session
            target.session = _InterceptingSessionBackend(session, emit)

    def detach(self) -> None:
        if self._holder is None:
            return
        try:
            if self._holder is self._target and self._original is not None and hasattr(
                self._holder, "add_items"
            ):
                self._holder.add_items = self._original
            elif self._original is not None:
                self._holder.session = self._original
        except Exception:
            pass
        self._target = None
        self._holder = None
        self._original = None

    def _backend(self) -> Any:
        if self._holder is None:
            return None
        if self._holder is self._target and callable(getattr(self._holder, "get_items", None)):
            return self._holder
        return getattr(self._holder, "session", self._original)

    # ─── Level 4 / Level 3 ───

    async def _get_items(self) -> List[Any]:
        backend = self._backend()
        if backend is None:
            return []
        fn = getattr(backend, "get_items", None)
        if fn is None:
            return []
        try:
            result = fn()
        except TypeError:
            result = fn(None)
        if inspect.isawaitable(result):
            result = await result
        return list(result or [])

    def _session_id(self) -> str:
        backend = self._backend()
        return getattr(backend, "session_id", "") or ""

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        items = await self._get_items()
        messages = [
            ContextMessage(role=_item_role(i), content=_item_content(i)) for i in items
        ]
        return ContextSnapshot(
            session_id=session_id or self._session_id(),
            messages=messages,
            framework=self.framework_name(),
        )

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        items = await self._get_items()
        history = [
            MessageItem(seq=idx, role=_item_role(i), content=_item_content(i))
            for idx, i in enumerate(items, start=1)
        ]
        page = history[offset : offset + limit] if offset >= 0 else history[:limit]
        return MessagePage(
            session_id=session_id or self._session_id(),
            messages=page,
            offset=offset,
            limit=limit,
            total=len(history),
        )

    # ─── 命令 ───

    async def handle_command(
        self, session_id: str, command: str, params: Optional[bytes] = None
    ) -> None:
        if command != COMMAND_TERMINATE:
            raise ValueError(f"unsupported command: {command!r}")
        backend = self._backend()
        fn = getattr(backend, "clear_session", None) if backend is not None else None
        if fn is None:
            raise NotImplementedError("openai-agents: no clear_session on session backend")
        result = fn()
        if inspect.isawaitable(result):
            await result

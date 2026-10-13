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

"""Google ADK 适配器（P1）：装饰 ``SessionService.append_event`` 旁路复制
（framework-integration §3.5 ADK 节）。

零硬依赖：不 import ``google.adk``，duck-typing 识别 ``SessionService``。

ADK 的 ``session.state`` 是 Agent 长期状态，与 messages 一样影响行为，放入
``framework_state`` 上报。
"""
from __future__ import annotations

import functools
import inspect
from typing import Any, Callable, List, Optional

from ..context import ContextMessage, ContextSnapshot
from ..events import (
    EVENT_MESSAGE,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    MessageItem,
    MessagePage,
    SessionEvent,
)
from .base import COMMAND_COMPRESS, COMMAND_TERMINATE, FrameworkAdapter, get_field


def _event_role(event: Any) -> str:
    content = get_field(event, "content")
    role = get_field(content, "role")
    if role:
        # Gemini 风格的 "model" 归一化为合约词汇 "assistant"。
        return "assistant" if str(role) == "model" else str(role)
    author = get_field(event, "author", "") or ""
    return "user" if author == "user" else "assistant"


def _event_text(event: Any) -> str:
    content = get_field(event, "content")
    parts = get_field(content, "parts")
    if not parts:
        return ""
    texts = []
    for part in parts:
        text = get_field(part, "text")
        if isinstance(text, str) and text:
            texts.append(text)
    return "\n".join(texts)


def _event_function_calls(event: Any) -> List[Any]:
    content = get_field(event, "content")
    parts = get_field(content, "parts") or []
    calls = []
    for part in parts:
        fc = get_field(part, "function_call")
        if fc is not None:
            calls.append(fc)
    return calls


def _event_function_responses(event: Any) -> List[Any]:
    content = get_field(event, "content")
    parts = get_field(content, "parts") or []
    responses = []
    for part in parts:
        fr = get_field(part, "function_response")
        if fr is not None:
            responses.append(fr)
    return responses


class ADKAdapter(FrameworkAdapter):
    """Google ADK ``SessionService`` 适配器。"""

    def __init__(self) -> None:
        self._service: Any = None
        self._original_append: Any = None

    def framework_name(self) -> str:
        return "adk"

    def framework_version(self) -> str:
        try:
            from importlib.metadata import version

            return version("google-adk")
        except Exception:
            return ""

    def can_handle(self, target: Any) -> bool:
        mod = (type(target).__module__ or "").lower()
        if "adk" in mod:
            return True
        # duck-typing：SessionService 形状（append_event + get_session）。
        return callable(getattr(target, "append_event", None)) and callable(
            getattr(target, "get_session", None)
        )

    # ─── 拦截 ───

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._service = target
        original = getattr(target, "append_event", None)
        self._original_append = original
        if original is None:
            return

        @functools.wraps(original)
        async def intercepted(session: Any, event: Any, *args: Any, **kwargs: Any) -> Any:
            # 1. 主路径先成功。
            if inspect.iscoroutinefunction(original):
                result = await original(session, event, *args, **kwargs)
            else:
                result = original(session, event, *args, **kwargs)
            # 2. 旁路发事件（失败静默忽略）。
            self._emit_event(session, event, emit)
            return result

        target.append_event = intercepted

    def detach(self) -> None:
        if self._service is not None and self._original_append is not None:
            try:
                self._service.append_event = self._original_append
            except Exception:
                pass
        self._service = None
        self._original_append = None

    @staticmethod
    def _emit_event(session: Any, event: Any, emit: Callable[[SessionEvent], None]) -> None:
        try:
            session_id = get_field(session, "id", "") or ""
            for fc in _event_function_calls(event):
                emit(
                    SessionEvent(
                        session_id=session_id,
                        seq=0,
                        event_type=EVENT_TOOL_CALL,
                        role="assistant",
                        tool_name=get_field(fc, "name", "") or "",
                        tool_input=SessionEvent.encode_tool_input(get_field(fc, "args")),
                    )
                )
            for fr in _event_function_responses(event):
                emit(
                    SessionEvent(
                        session_id=session_id,
                        seq=0,
                        event_type=EVENT_TOOL_RESULT,
                        role="tool",
                        tool_name=get_field(fr, "name", "") or "",
                        tool_output=_safe_str(get_field(fr, "response")),
                    )
                )
            text = _event_text(event)
            if text:
                emit(
                    SessionEvent(
                        session_id=session_id,
                        seq=0,
                        event_type=EVENT_MESSAGE,
                        role=_event_role(event),
                        content=text,
                    )
                )
        except Exception:
            pass

    # ─── Level 4：生效 Context ───

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        session = await self._get_session(session_id)
        if session is None:
            return ContextSnapshot(session_id=session_id, framework=self.framework_name())

        events = get_field(session, "events", None)
        if events is None:
            events = await self._list_events(session_id)
        messages = [
            ContextMessage(role=_event_role(e), content=_event_text(e))
            for e in events or []
            if _event_text(e)
        ]
        state = get_field(session, "state")
        framework_state = SessionEvent.encode_meta(_jsonable(state)) if state else b""
        return ContextSnapshot(
            session_id=session_id,
            messages=messages,
            framework=self.framework_name(),
            framework_state=framework_state,
        )

    async def _get_session(self, session_id: str) -> Any:
        svc = self._service
        if svc is None:
            return None
        fn = getattr(svc, "get_session", None)
        if fn is None:
            return None
        try:
            result = fn(session_id)
        except TypeError:
            try:
                result = fn(session_id=session_id)
            except TypeError:
                # ADK 实际签名含 app_name / user_id；尽力缺省。
                result = fn(app_name="", user_id="", session_id=session_id)
        if inspect.isawaitable(result):
            result = await result
        return result

    async def _list_events(self, session_id: str) -> List[Any]:
        svc = self._service
        fn = getattr(svc, "list_events", None) if svc is not None else None
        if fn is None:
            return []
        try:
            result = fn(session_id)
        except TypeError:
            result = fn(session_id=session_id)
        if inspect.isawaitable(result):
            result = await result
        return list(result or [])

    # ─── Level 3：完整历史 ───

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        session = await self._get_session(session_id)
        events = get_field(session, "events", None)
        if events is None:
            events = await self._list_events(session_id)
        items: List[MessageItem] = []
        for idx, e in enumerate(events or [], start=1):
            items.append(
                MessageItem(
                    seq=idx,
                    role=_event_role(e),
                    content=_event_text(e),
                    occurred_at=_epoch_ms(get_field(e, "timestamp")),
                )
            )
        page = items[offset : offset + limit] if offset >= 0 else items[:limit]
        return MessagePage(
            session_id=session_id, messages=page, offset=offset, limit=limit, total=len(items)
        )

    # ─── 命令 ───

    async def handle_command(
        self, session_id: str, command: str, params: Optional[bytes] = None
    ) -> None:
        svc = self._service
        if command == COMMAND_TERMINATE:
            for name in ("delete_session", "terminate_session", "close_session"):
                fn = getattr(svc, name, None) if svc is not None else None
                if fn is None:
                    continue
                try:
                    result = fn(session_id)
                except TypeError:
                    result = fn(session_id=session_id)
                if inspect.isawaitable(result):
                    await result
                return
            raise NotImplementedError("adk: no session termination method found")
        if command == COMMAND_COMPRESS:
            for name in ("compress_session", "compact_session"):
                fn = getattr(svc, name, None) if svc is not None else None
                if fn is None:
                    continue
                result = fn(session_id)
                if inspect.isawaitable(result):
                    await result
                return
            raise NotImplementedError("adk: no session compaction method found")
        raise ValueError(f"unsupported command: {command!r}")


def _safe_str(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return _jsonable(value).__str__()


def _jsonable(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return repr(value)


def _epoch_ms(ts: Any) -> int:
    if ts is None:
        return 0
    if isinstance(ts, (int, float)):
        v = float(ts)
        return int(v * 1000) if v < 1e12 else int(v)
    return 0

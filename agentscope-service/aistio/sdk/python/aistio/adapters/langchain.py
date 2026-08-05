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

"""LangChain / LangGraph 适配器（P1）：注册 CallbackHandler 旁路观测，
Context 从 Checkpointer 的 graph state 重建（framework-integration §3.5 /
§5.3 LangGraph 节）。

零硬依赖：优先继承 ``langchain_core.callbacks.BaseCallbackHandler``（框架在
场时保证回调协议兼容），不在场时退化为普通 duck-typed 类，模块仍可导入。

LangGraph 的 Context 不只是 messages：Graph State 的自定义 Channel（长期记
忆、中间计算结果）也影响 Agent 行为，一并放入 ``framework_state``。
"""
from __future__ import annotations

import inspect
from typing import Any, Callable, Dict, List, Optional

from ..context import ContextMessage, ContextSnapshot
from ..events import (
    EVENT_MESSAGE,
    EVENT_SESSION_END,
    EVENT_SESSION_START,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    MessageItem,
    MessagePage,
    SessionEvent,
)
from .base import FrameworkAdapter, get_field

try:  # 框架在场时继承真正的回调基类。
    from langchain_core.callbacks import BaseCallbackHandler as _BaseHandler
except Exception:  # pragma: no cover - 环境无 langchain 时

    class _BaseHandler:  # type: ignore[no-redef]
        pass


def _session_from_metadata(kwargs: Dict[str, Any]) -> str:
    metadata = kwargs.get("metadata") or {}
    for key in ("session_id", "thread_id", "conversation_id"):
        value = metadata.get(key)
        if value:
            return str(value)
    return "default"


class _AistioCallbackHandler(_BaseHandler):
    """LangChain 回调链上的 aistio Observer（天然旁路）。"""

    def __init__(self, emit: Callable[[SessionEvent], None]) -> None:
        self._emit = emit

    def _safe_emit(self, event: SessionEvent) -> None:
        try:
            self._emit(event)
        except Exception:
            pass

    # ─── LLM ───

    def on_chat_model_start(self, serialized: Any, messages: Any, **kwargs: Any) -> None:
        session_id = _session_from_metadata(kwargs)
        # 最后一条 incoming 消息视为 user 输入。
        try:
            last = messages[-1][-1] if messages and messages[-1] else None
            content = get_field(last, "content", "") or ""
            role = get_field(last, "type", "user") or "user"
            if content:
                self._safe_emit(
                    SessionEvent(
                        session_id=session_id,
                        seq=0,
                        event_type=EVENT_MESSAGE,
                        role=_normalize_role(role),
                        content=content,
                    )
                )
        except Exception:
            pass

    def on_llm_end(self, response: Any, **kwargs: Any) -> None:
        session_id = _session_from_metadata(kwargs)
        try:
            generations = get_field(response, "generations", []) or []
            text = ""
            if generations and generations[0]:
                text = get_field(generations[0][0], "text", "") or ""
            usage = get_field(response, "llm_output", {}) or {}
            usage = usage.get("token_usage") or usage.get("usage") or {}
            self._safe_emit(
                SessionEvent(
                    session_id=session_id,
                    seq=0,
                    event_type=EVENT_MESSAGE,
                    role="assistant",
                    content=text,
                    tokens_in=int(usage.get("prompt_tokens") or usage.get("input_tokens") or 0),
                    tokens_out=int(
                        usage.get("completion_tokens") or usage.get("output_tokens") or 0
                    ),
                )
            )
        except Exception:
            pass

    # ─── Tool ───

    def on_tool_start(self, serialized: Any, input_str: str, **kwargs: Any) -> None:
        self._safe_emit(
            SessionEvent(
                session_id=_session_from_metadata(kwargs),
                seq=0,
                event_type=EVENT_TOOL_CALL,
                role="assistant",
                tool_name=(serialized or {}).get("name", "") if isinstance(serialized, dict) else str(get_field(serialized, "name", "") or ""),
                content=input_str or "",
            )
        )

    def on_tool_end(self, output: Any, **kwargs: Any) -> None:
        self._safe_emit(
            SessionEvent(
                session_id=_session_from_metadata(kwargs),
                seq=0,
                event_type=EVENT_TOOL_RESULT,
                role="tool",
                tool_output=output if isinstance(output, str) else str(output),
            )
        )

    # ─── Chain（会话边界近似）───

    def on_chain_start(self, serialized: Any, inputs: Any, **kwargs: Any) -> None:
        if kwargs.get("parent_run_id") is not None:
            return  # 只在最外层 chain 记 session_start
        self._safe_emit(
            SessionEvent(
                session_id=_session_from_metadata(kwargs),
                seq=0,
                event_type=EVENT_SESSION_START,
            )
        )

    def on_chain_end(self, outputs: Any, **kwargs: Any) -> None:
        if kwargs.get("parent_run_id") is not None:
            return
        self._safe_emit(
            SessionEvent(
                session_id=_session_from_metadata(kwargs),
                seq=0,
                event_type=EVENT_SESSION_END,
            )
        )


def _normalize_role(role: str) -> str:
    mapping = {
        "human": "user",
        "ai": "assistant",
        "system": "system",
        "tool": "tool",
        "function": "tool",
        "user": "user",
        "assistant": "assistant",
    }
    return mapping.get((role or "").lower(), role or "assistant")


class LangChainAdapter(FrameworkAdapter):
    """LangChain ``Chain`` / LangGraph graph 适配器。"""

    def __init__(self, checkpointer: Any = None) -> None:
        # checkpointer 可显式注入；缺省从 target 上探测（compiled graph 常见
        # 属性 ``checkpointer`` / ``checkpointer`` 配置）。
        self._checkpointer = checkpointer
        self._target: Any = None
        self._handler: Optional[_AistioCallbackHandler] = None
        self._attached_to_callbacks = False

    def framework_name(self) -> str:
        return "langchain"

    def framework_version(self) -> str:
        try:
            from importlib.metadata import version

            return version("langchain-core")
        except Exception:
            return ""

    def can_handle(self, target: Any) -> bool:
        mod = (type(target).__module__ or "").lower()
        return mod.startswith(("langchain", "langgraph"))

    # ─── 拦截 ───

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._target = target
        self._handler = _AistioCallbackHandler(emit)
        if self._checkpointer is None:
            self._checkpointer = getattr(target, "checkpointer", None)

        callbacks = getattr(target, "callbacks", None)
        if callbacks is None:
            try:
                target.callbacks = [self._handler]
                self._attached_to_callbacks = True
            except Exception:
                self._attached_to_callbacks = False
        elif isinstance(callbacks, list):
            callbacks.append(self._handler)
            self._attached_to_callbacks = True
        else:
            # CallbackManager 等形状：尽力而为。
            try:
                callbacks.add_handler(self._handler)
                self._attached_to_callbacks = True
            except Exception:
                self._attached_to_callbacks = False

    def detach(self) -> None:
        if self._target is not None and self._handler is not None and self._attached_to_callbacks:
            callbacks = getattr(self._target, "callbacks", None)
            try:
                if isinstance(callbacks, list) and self._handler in callbacks:
                    callbacks.remove(self._handler)
                elif hasattr(callbacks, "remove_handler"):
                    callbacks.remove_handler(self._handler)
            except Exception:
                pass
        self._target = None
        self._handler = None
        self._attached_to_callbacks = False

    # ─── Level 4：从 Checkpointer 重建生效 Context ───

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        state = await self._read_state(session_id)
        channel_values = get_field(state, "channel_values", None)
        if channel_values is None and isinstance(state, dict):
            channel_values = state.get("channel_values", state)
        channel_values = channel_values or {}

        messages: List[ContextMessage] = []
        for m in channel_values.get("messages", []) or []:
            role = _normalize_role(get_field(m, "type", "") or get_field(m, "role", ""))
            messages.append(ContextMessage(role=role, content=get_field(m, "content", "") or ""))

        custom_state = {k: v for k, v in channel_values.items() if k != "messages"}
        framework_state = SessionEvent.encode_meta(_jsonable(custom_state)) if custom_state else b""
        return ContextSnapshot(
            session_id=session_id,
            messages=messages,
            framework=self.framework_name(),
            framework_state=framework_state,
        )

    async def _read_state(self, session_id: str) -> Any:
        cp = self._checkpointer
        if cp is None:
            return {}
        config = {"configurable": {"thread_id": session_id}}
        for name in ("aget", "get"):
            fn = getattr(cp, name, None)
            if fn is None:
                continue
            try:
                result = fn(config)
            except TypeError:
                result = fn(config=config)
            if inspect.isawaitable(result):
                result = await result
            if result is not None:
                return result
        return {}

    # ─── Level 3：完整历史（自 graph state 的 messages channel）───

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        state = await self._read_state(session_id)
        channel_values = get_field(state, "channel_values", None)
        if channel_values is None and isinstance(state, dict):
            channel_values = state.get("channel_values", state)
        raw = (channel_values or {}).get("messages", []) or []
        items = [
            MessageItem(
                seq=idx,
                role=_normalize_role(get_field(m, "type", "") or get_field(m, "role", "")),
                content=get_field(m, "content", "") or "",
                tool_name=get_field(m, "name", "") or "",
            )
            for idx, m in enumerate(raw, start=1)
        ]
        page = items[offset : offset + limit] if offset >= 0 else items[:limit]
        return MessagePage(
            session_id=session_id, messages=page, offset=offset, limit=limit, total=len(items)
        )


def _jsonable(value: Any) -> Any:
    """尽力把 graph state 转为 JSON 可序列化结构。"""
    if isinstance(value, dict):
        return {str(k): _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return repr(value)

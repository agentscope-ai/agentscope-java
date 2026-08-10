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

"""Claude Agent SDK 适配器（P0）：装饰 ``SessionStore`` 旁路复制事件
（framework-integration §3.4 / §3.5 Claude 节）。

零硬依赖：不 import ``claude_agent_sdk``，全部 duck-typing —— 未安装该框架时
本模块照样可导入；``can_handle`` 依据类型模块名/类名识别。

生效 Context 语义（§3.5）：Compaction 后 Agent 看到的是 ``summary + 后续新
消息``，``extract_context()`` 重建的是**生效 Context**而非完整历史。
"""
from __future__ import annotations

import inspect
from typing import Any, Callable, List, Optional

from ..context import ContextMessage, ContextSnapshot
from ..events import (
    EVENT_COMPACTION,
    EVENT_MESSAGE,
    MessageItem,
    MessagePage,
    SessionEvent,
)
from .base import COMMAND_COMPRESS, COMMAND_TERMINATE, FrameworkAdapter, get_field


def _session_id_of(key: Any) -> str:
    if isinstance(key, str):
        return key
    return get_field(key, "session_id", "") or ""


def _entry_type(entry: Any) -> str:
    return get_field(entry, "type", "") or get_field(entry, "role", "") or ""


def _entry_content(entry: Any) -> str:
    content = get_field(entry, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        # Claude SDK 的 content blocks：[{"type": "text", "text": ...}, ...]
        parts = []
        for block in content:
            text = get_field(block, "text")
            if isinstance(text, str):
                parts.append(text)
        return "\n".join(parts)
    return str(content) if content is not None else ""


class _InterceptingSessionStore:
    """装饰器：包装原有 SessionStore，旁路复制事件到 aistio。

    - ``append()`` 先写原有 store（主路径，必须成功），再旁路发事件；
    - ``load()`` 直接走原有 store，不经过 aistio；
    - 旁路上报失败静默忽略；其余方法/属性全部透传。
    """

    def __init__(self, inner: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._inner = inner
        self._emit = emit

    async def append(self, key: Any, entries: Any) -> None:
        await self._inner.append(key, entries)
        self._emit_entries(key, entries)

    async def load(self, key: Any) -> Any:
        return await self._inner.load(key)

    def _emit_entries(self, key: Any, entries: Any) -> None:
        try:
            session_id = _session_id_of(key)
            for entry in entries or []:
                etype = _entry_type(entry)
                if etype == "summary":
                    self._emit(
                        SessionEvent(
                            session_id=session_id,
                            seq=0,  # 由 SessionBridge 统一分配
                            event_type=EVENT_COMPACTION,
                            role="system",
                            content=get_field(entry, "summary") or _entry_content(entry),
                        )
                    )
                else:
                    self._emit(
                        SessionEvent(
                            session_id=session_id,
                            seq=0,
                            event_type=EVENT_MESSAGE,
                            role=etype or "assistant",
                            content=_entry_content(entry),
                            framework_meta=SessionEvent.encode_meta(
                                {"entry_type": get_field(entry, "type", "") or None}
                            ),
                        )
                    )
        except Exception:
            pass  # 旁路失败，静默忽略

    def __getattr__(self, name: str) -> Any:
        # 未覆写的方法/属性全部透传到内部 store。
        return getattr(self._inner, name)


class ClaudeAgentSDKAdapter(FrameworkAdapter):
    """Claude Agent SDK（``ClaudeSDKClient``）适配器。"""

    def __init__(self) -> None:
        self._target: Any = None
        self._store: Any = None  # 当前生效的（可能被包装过的）store
        self._original_store: Any = None
        self._options: Any = None

    def framework_name(self) -> str:
        return "claude-agent-sdk"

    def framework_version(self) -> str:
        try:
            from importlib.metadata import version

            return version("claude-agent-sdk")
        except Exception:
            return ""

    def can_handle(self, target: Any) -> bool:
        cls = type(target)
        mod = (cls.__module__ or "").lower()
        name = cls.__name__
        if "claude" in mod:
            return True
        if name in ("ClaudeSDKClient", "ClaudeClient"):
            return True
        # duck-typing：有 options.session_store 形状也视为可处理。
        options = getattr(target, "options", None)
        return options is not None and hasattr(options, "session_store")

    # ─── 拦截 ───

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._target = target
        options = getattr(target, "options", None)
        self._options = options
        store = getattr(options, "session_store", None) if options is not None else None
        if store is None:
            # 框架默认 store 不可见时，仍可通过 target 上的会话 API 观测。
            self._store = None
            self._original_store = None
            return
        self._original_store = store
        self._store = _InterceptingSessionStore(store, emit)
        options.session_store = self._store

    def detach(self) -> None:
        if self._options is not None and self._original_store is not None:
            try:
                self._options.session_store = self._original_store
            except Exception:
                pass
        self._target = None
        self._store = None
        self._original_store = None
        self._options = None

    # ─── Level 4：生效 Context ───

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        entries = await self._load_entries(session_id)
        messages: List[ContextMessage] = []
        is_compacted = False
        compaction_summary = ""
        for entry in entries:
            etype = _entry_type(entry)
            if etype == "summary":
                is_compacted = True
                compaction_summary = get_field(entry, "summary") or _entry_content(entry)
                # 重置视图：压缩摘要 + 后续新消息才是生效 Context。
                messages = [
                    ContextMessage(role="system", content=compaction_summary, is_compaction=True)
                ]
            else:
                messages.append(
                    ContextMessage(role=etype or "assistant", content=_entry_content(entry))
                )
        return ContextSnapshot(
            session_id=session_id,
            messages=messages,
            is_compacted=is_compacted,
            compaction_summary=compaction_summary,
            framework=self.framework_name(),
            framework_state=b'{"session_type":"store-backed"}',
        )

    async def _load_entries(self, session_id: str) -> List[Any]:
        store = self._original_store
        if store is None:
            return []
        key = {"session_id": session_id}
        result = store.load(key)
        if inspect.isawaitable(result):
            result = await result
        return list(result or [])

    # ─── Level 3：完整历史 ───

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        entries = await self._load_entries(session_id)
        items: List[MessageItem] = []
        for idx, entry in enumerate(entries, start=1):
            items.append(
                MessageItem(
                    seq=idx,
                    role=_entry_type(entry) or "assistant",
                    content=_entry_content(entry),
                    occurred_at=_epoch_ms(get_field(entry, "timestamp")),
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
        if command == COMMAND_COMPRESS:
            await self._call_first(
                ("compress_session", "compact_session", "compress", "compact"), session_id
            )
            return
        if command == COMMAND_TERMINATE:
            await self._call_first(
                ("terminate_session", "close_session", "end_session", "delete_session"),
                session_id,
            )
            return
        raise ValueError(f"unsupported command: {command!r}")

    async def _call_first(self, names: tuple, session_id: str) -> None:
        """在 target / store 上按候选名找第一个可用的会话操作方法并调用。"""
        for holder in (self._target, self._original_store):
            if holder is None:
                continue
            for name in names:
                fn = getattr(holder, name, None)
                if fn is None:
                    continue
                result = fn(session_id)
                if inspect.isawaitable(result):
                    await result
                return
        raise NotImplementedError(
            f"{self.framework_name()}: no session operation found for {names!r}"
        )


def _epoch_ms(ts: Any) -> int:
    """尽力把时间戳（float 秒 / int 毫秒 / ISO 字符串）转为 unix ms。"""
    if ts is None:
        return 0
    if isinstance(ts, (int, float)):
        # 启发：秒级时间戳 < 10^12。
        v = float(ts)
        return int(v * 1000) if v < 1e12 else int(v)
    if isinstance(ts, str):
        try:
            from datetime import datetime

            return int(datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp() * 1000)
        except ValueError:
            return 0
    return 0

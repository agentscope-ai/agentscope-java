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

"""Level-4 effective-context model (mirrors ``asdp.ContextReport`` /
``prober.ContextSnapshot``) plus the incremental :class:`ContextTracker`
used by ``SessionBridge``.

``context_hash`` = 生效内容（system_prompt + messages + tools）规范 JSON 的
SHA-256 前 16 hex（sdk-design §3.1）。控制面据此判断 Context 是否变化，无需
拉取全文。
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any, List, Optional

from ._util import now_ms, rfc3339
from .events import (
    EVENT_COMPACTION,
    EVENT_MESSAGE,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    ROLE_ASSISTANT,
    ROLE_SYSTEM,
    ROLE_TOOL,
    SessionEvent,
)
from .proto import asdp_pb2

#: context_hash 取 SHA-256 hex 摘要的前 16 个字符。
HASH_LEN = 16


@dataclass
class ContextMessage:
    """One effective-context message (mirrors ``prober.ContextMessage``)."""

    role: str
    content: str
    is_compaction: bool = False

    def to_dict(self) -> dict:
        d: dict = {"role": self.role, "content": self.content}
        if self.is_compaction:
            d["isCompaction"] = True
        return d


@dataclass
class ToolInfo:
    """One tool currently available to the agent (mirrors ``prober.ToolInfo``)."""

    name: str
    description: str = ""
    parameters: Any = None  # JSON-able input schema

    def to_dict(self) -> dict:
        d: dict = {"name": self.name}
        if self.description:
            d["description"] = self.description
        if self.parameters is not None:
            d["parameters"] = self.parameters
        return d


@dataclass
class ContextSnapshot:
    """Level-4 effective context snapshot.

    同时承担两种编码：
    - ``to_proto()``     → ASDP ``ContextReport`` 上行推送；
    - ``to_json_dict()`` → HTTP 合约 ``GET /agentscope/sessions/{id}/context`` 响应。
    """

    session_id: str
    context_hash: str = ""
    captured_at: int = 0  # unix ms
    system_prompt: str = ""
    messages: List[ContextMessage] = field(default_factory=list)
    tools: List[ToolInfo] = field(default_factory=list)
    is_compacted: bool = False
    compaction_summary: str = ""
    original_message_count: int = 0
    compacted_at: int = 0  # unix ms
    total_tokens: int = 0
    max_tokens: int = 0
    framework: str = ""
    framework_state: Optional[bytes] = None  # 框架私有 JSON

    def __post_init__(self) -> None:
        if self.captured_at <= 0:
            self.captured_at = now_ms()

    # ─── hash ───

    def canonical_payload(self) -> bytes:
        """Stable encoding of the *effective* content used for hashing."""
        obj = {
            "systemPrompt": self.system_prompt,
            "messages": [m.to_dict() for m in self.messages],
            "tools": [t.to_dict() for t in self.tools],
        }
        return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
            "utf-8"
        )

    def compute_hash(self) -> str:
        return hashlib.sha256(self.canonical_payload()).hexdigest()[:HASH_LEN]

    def refresh_hash(self) -> str:
        """Recompute and store ``context_hash``; returns the new value."""
        self.context_hash = self.compute_hash()
        return self.context_hash

    # ─── encoders ───

    def to_proto(self) -> "asdp_pb2.ContextReport":
        messages_json = json.dumps(
            [m.to_dict() for m in self.messages], ensure_ascii=False
        ).encode("utf-8")
        tools_json = json.dumps([t.to_dict() for t in self.tools], ensure_ascii=False).encode(
            "utf-8"
        )
        return asdp_pb2.ContextReport(
            session_id=self.session_id,
            context_hash=self.context_hash or self.compute_hash(),
            captured_at=self.captured_at,
            system_prompt=self.system_prompt,
            messages=messages_json,
            tools=tools_json,
            is_compacted=self.is_compacted,
            compaction_summary=self.compaction_summary,
            original_message_count=self.original_message_count,
            compacted_at=self.compacted_at,
            total_tokens=self.total_tokens,
            max_tokens=self.max_tokens,
            framework=self.framework,
            framework_state=self.framework_state or b"",
        )

    def to_json_dict(self) -> dict:
        d: dict = {
            "sessionId": self.session_id,
            "contextHash": self.context_hash or self.compute_hash(),
            "messages": [m.to_dict() for m in self.messages],
        }
        ts = rfc3339(self.captured_at)
        if ts:
            d["capturedAt"] = ts
        if self.system_prompt:
            d["systemPrompt"] = self.system_prompt
        if self.tools:
            d["tools"] = [t.to_dict() for t in self.tools]
        if self.is_compacted:
            d["isCompacted"] = True
        if self.compaction_summary:
            d["compactionSummary"] = self.compaction_summary
        if self.original_message_count:
            d["originalMessageCount"] = self.original_message_count
        compacted_ts = rfc3339(self.compacted_at)
        if compacted_ts:
            d["compactedAt"] = compacted_ts
        if self.total_tokens:
            d["totalTokens"] = self.total_tokens
        if self.max_tokens:
            d["maxTokens"] = self.max_tokens
        if self.framework:
            d["framework"] = self.framework
        if self.framework_state:
            try:
                d["frameworkState"] = json.loads(self.framework_state.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                d["frameworkState"] = self.framework_state.decode("utf-8", errors="replace")
        return d


class ContextTracker:
    """增量维护一个 session 的生效 Context 视图与 ``context_hash``。

    - ``message`` / ``tool_call`` / ``tool_result`` 事件追加到生效视图；
    - ``compaction`` 事件重置视图（仅保留一条 ``is_compaction=True`` 的摘要消息）；
    - 每次视图变化后重算 hash；:meth:`on_event` 返回 hash 是否变化，供
      Level 4 防抖推送判断（sdk-design §3.4）。

    注意：tracker 是从事件流重建的**近似视图**，权威 Context 仍由
    ``FrameworkAdapter.extract_context()`` 提供。
    """

    def __init__(
        self,
        session_id: str,
        *,
        framework: str = "",
        system_prompt: str = "",
        tools: Optional[List[ToolInfo]] = None,
        max_tokens: int = 0,
    ) -> None:
        self.session_id = session_id
        self.framework = framework
        self.system_prompt = system_prompt
        self.tools: List[ToolInfo] = list(tools or [])
        self.max_tokens = max_tokens

        self._messages: List[ContextMessage] = []
        self._is_compacted = False
        self._compaction_summary = ""
        self._original_message_count = 0
        self._compacted_at = 0
        self._tokens_in = 0
        self._tokens_out = 0
        self._message_count = 0  # 全量消息计数（含被压缩掉的）
        self._last_hash = self._compute()

    # ─── read-only views ───

    @property
    def context_hash(self) -> str:
        return self._last_hash

    @property
    def effective_message_count(self) -> int:
        return len(self._messages)

    @property
    def message_count(self) -> int:
        return self._message_count

    @property
    def tokens_in(self) -> int:
        return self._tokens_in

    @property
    def tokens_out(self) -> int:
        return self._tokens_out

    @property
    def is_compacted(self) -> bool:
        return self._is_compacted

    @property
    def messages(self) -> List[ContextMessage]:
        return list(self._messages)

    # ─── mutation ───

    def _compute(self) -> str:
        snap = ContextSnapshot(
            session_id=self.session_id,
            system_prompt=self.system_prompt,
            messages=self._messages,
            tools=self.tools,
        )
        return snap.compute_hash()

    def on_event(self, event: SessionEvent) -> bool:
        """Consume one Level-2 event; returns True iff ``context_hash`` changed."""
        if event.session_id != self.session_id:
            return False

        self._tokens_in += max(0, event.tokens_in)
        self._tokens_out += max(0, event.tokens_out)

        et = event.event_type
        if et == EVENT_MESSAGE:
            self._messages.append(ContextMessage(role=event.role or ROLE_ASSISTANT, content=event.content))
            self._message_count += 1
        elif et == EVENT_TOOL_CALL:
            text = event.content or event.tool_name
            self._messages.append(ContextMessage(role=ROLE_ASSISTANT, content=text))
        elif et == EVENT_TOOL_RESULT:
            text = event.tool_output or event.content
            self._messages.append(ContextMessage(role=ROLE_TOOL, content=text))
        elif et == EVENT_COMPACTION:
            self._original_message_count = self._message_count
            self._is_compacted = True
            self._compaction_summary = event.content
            self._compacted_at = event.occurred_at
            # 重置生效视图：压缩摘要成为视图中的唯一消息。
            self._messages = [
                ContextMessage(role=ROLE_SYSTEM, content=event.content, is_compaction=True)
            ]
        else:
            # session_start / session_end 及未知类型不影响 Context 视图。
            return False

        new_hash = self._compute()
        changed = new_hash != self._last_hash
        self._last_hash = new_hash
        return changed

    def snapshot(self) -> ContextSnapshot:
        """Materialize the current view as a :class:`ContextSnapshot`."""
        return ContextSnapshot(
            session_id=self.session_id,
            context_hash=self._last_hash,
            system_prompt=self.system_prompt,
            messages=list(self._messages),
            tools=list(self.tools),
            is_compacted=self._is_compacted,
            compaction_summary=self._compaction_summary,
            original_message_count=self._original_message_count,
            compacted_at=self._compacted_at,
            total_tokens=self._tokens_in + self._tokens_out,
            max_tokens=self.max_tokens,
            framework=self.framework,
        )

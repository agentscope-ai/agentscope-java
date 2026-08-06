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

"""Level-2 session event model (mirrors ``asdp.SessionEventMsg``) and the
Level-3 full-history page (mirrors ``prober.MessagePage``).

事件流只承载摘要（``content`` / ``tool_output`` 截断到 500 字符以内）；完整
消息内容走 Level 3 HTTP 按需拉取，不主动上报（见 sdk-design.md §2.2 / §3.3）。
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, List, Optional

from ._util import now_ms, rfc3339, truncate
from .proto import asdp_pb2

# ─── Event type vocabulary (aligned with asdp.proto SessionEventMsg.event_type) ───
EVENT_SESSION_START = "session_start"
EVENT_MESSAGE = "message"
EVENT_TOOL_CALL = "tool_call"
EVENT_TOOL_RESULT = "tool_result"
EVENT_SESSION_END = "session_end"
EVENT_COMPACTION = "compaction"

EVENT_TYPES = frozenset(
    {
        EVENT_SESSION_START,
        EVENT_MESSAGE,
        EVENT_TOOL_CALL,
        EVENT_TOOL_RESULT,
        EVENT_SESSION_END,
        EVENT_COMPACTION,
    }
)

# ─── Role vocabulary ───
ROLE_USER = "user"
ROLE_ASSISTANT = "assistant"
ROLE_SYSTEM = "system"
ROLE_TOOL = "tool"

#: 摘要最大长度（sdk-design §3.3：content 摘要建议 ≤ 500 字符）。
MAX_SUMMARY_LEN = 500

#: tool_input JSON 序列化后的最大字节数（超出截断，避免大 payload 占流）。
MAX_TOOL_INPUT_BYTES = 4096


def _json_bytes(value: Any, limit: int = 0) -> bytes:
    """Best-effort canonical JSON encoding; returns ``b""`` for ``None``."""
    if value is None:
        return b""
    if isinstance(value, bytes):
        data = value
    elif isinstance(value, str):
        data = value.encode("utf-8")
    else:
        data = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if limit > 0 and len(data) > limit:
        data = data[:limit]
    return data


@dataclass
class SessionEvent:
    """One Level-2 session event.

    ``seq`` 在会话内单调递增，是控制面幂等去重键（``(session_fk, seq)`` 唯一）。
    """

    session_id: str
    seq: int
    event_type: str
    occurred_at: int = 0  # unix ms
    role: str = ""
    content: str = ""  # 摘要
    tool_name: str = ""
    tool_input: Optional[bytes] = None  # JSON bytes，可截断
    tool_output: str = ""  # 摘要
    tokens_in: int = 0
    tokens_out: int = 0
    duration_ms: int = 0
    framework_meta: Optional[bytes] = None  # 框架私有 JSON

    def __post_init__(self) -> None:
        if self.occurred_at <= 0:
            self.occurred_at = now_ms()
        self.content = truncate(self.content, MAX_SUMMARY_LEN)
        self.tool_output = truncate(self.tool_output, MAX_SUMMARY_LEN)

    @staticmethod
    def encode_tool_input(value: Any) -> bytes:
        """Encode a tool-input payload as (possibly truncated) JSON bytes."""
        return _json_bytes(value, MAX_TOOL_INPUT_BYTES)

    @staticmethod
    def encode_meta(meta: Any) -> bytes:
        """Encode framework-private metadata as JSON bytes."""
        return _json_bytes(meta)

    def to_proto(self) -> "asdp_pb2.SessionEventMsg":
        return asdp_pb2.SessionEventMsg(
            session_id=self.session_id,
            seq=self.seq,
            event_type=self.event_type,
            occurred_at=self.occurred_at,
            role=self.role,
            content=self.content,
            tool_name=self.tool_name,
            tool_input=self.tool_input or b"",
            tool_output=self.tool_output,
            tokens_in=self.tokens_in,
            tokens_out=self.tokens_out,
            duration_ms=self.duration_ms,
            framework_meta=self.framework_meta or b"",
        )


@dataclass
class MessageItem:
    """One full-content history entry (Level 3; mirrors ``prober.MessageItem``)."""

    seq: int
    role: str
    content: str
    tool_name: str = ""
    tool_input: Any = None  # JSON-able
    tool_output: str = ""
    occurred_at: int = 0  # unix ms

    def to_json_dict(self) -> dict:
        d: dict = {"seq": self.seq, "role": self.role, "content": self.content}
        if self.tool_name:
            d["toolName"] = self.tool_name
        if self.tool_input is not None:
            d["toolInput"] = self.tool_input
        if self.tool_output:
            d["toolOutput"] = self.tool_output
        ts = rfc3339(self.occurred_at)
        if ts:
            d["occurredAt"] = ts
        return d


@dataclass
class MessagePage:
    """Paginated Level-3 full-history response (mirrors ``prober.MessagePage``)."""

    session_id: str
    messages: List[MessageItem] = field(default_factory=list)
    offset: int = 0
    limit: int = 50
    total: int = 0

    def __post_init__(self) -> None:
        if not self.total:
            self.total = len(self.messages)

    def to_json_dict(self) -> dict:
        return {
            "sessionId": self.session_id,
            "offset": self.offset,
            "limit": self.limit,
            "total": self.total,
            "messages": [m.to_json_dict() for m in self.messages],
        }

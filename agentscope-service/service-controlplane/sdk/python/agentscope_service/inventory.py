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

"""Instance inventory models (mirror ``asdp.InventoryReport`` /
``prober.SubagentInfo`` / ``prober.WorkspaceInfo``).

支撑控制面 subagent 管理与 workspace 管理的观测面：SDK 在 ASDP 连接建立后
立即上报一次，之后低频刷新（sdk-design §3.5）。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List

from ._util import rfc3339
from .proto import asdp_pb2

# ─── Workspace mode vocabulary ───
WORKSPACE_MODE_ISOLATED = "isolated"
WORKSPACE_MODE_SHARED = "shared"
WORKSPACE_MODE_READONLY = "readonly"


@dataclass
class SubagentInfo:
    """One subagent known to this data-plane instance."""

    name: str
    description: str = ""
    tools: List[str] = field(default_factory=list)
    workspace_mode: str = ""  # isolated | shared
    url: str = ""  # 远端 subagent（若有）
    invoke_count: int = 0
    last_invoked_at: int = 0  # unix ms

    def to_proto(self) -> "asdp_pb2.SubagentInfo":
        return asdp_pb2.SubagentInfo(
            name=self.name,
            description=self.description,
            tools=list(self.tools),
            workspace_mode=self.workspace_mode,
            url=self.url,
            invoke_count=self.invoke_count,
            last_invoked_at=self.last_invoked_at,
        )

    def to_json_dict(self) -> dict:
        d: dict = {"name": self.name}
        if self.description:
            d["description"] = self.description
        if self.tools:
            d["tools"] = list(self.tools)
        if self.workspace_mode:
            d["workspaceMode"] = self.workspace_mode
        if self.url:
            d["url"] = self.url
        if self.invoke_count:
            d["invokeCount"] = self.invoke_count
        ts = rfc3339(self.last_invoked_at)
        if ts:
            d["lastInvokedAt"] = ts
        return d


@dataclass
class WorkspaceInfo:
    """One workspace known to this data-plane instance."""

    path: str
    mode: str = ""  # isolated | shared | readonly
    size_bytes: int = 0
    owner_ref: str = ""  # session_id 或 subagent name

    def to_proto(self) -> "asdp_pb2.WorkspaceInfo":
        return asdp_pb2.WorkspaceInfo(
            path=self.path,
            mode=self.mode,
            size_bytes=self.size_bytes,
            owner_ref=self.owner_ref,
        )

    def to_json_dict(self) -> dict:
        d: dict = {"path": self.path}
        if self.mode:
            d["mode"] = self.mode
        if self.size_bytes:
            d["sizeBytes"] = self.size_bytes
        if self.owner_ref:
            d["ownerRef"] = self.owner_ref
        return d


@dataclass
class InstanceHealth:
    """Health summary carried by ``InventoryReport.health``."""

    healthy: bool = True
    reason: str = ""
    active_sessions: int = 0
    cpu_usage: float = 0.0
    memory_usage: float = 0.0

    def to_proto(self) -> "asdp_pb2.InstanceHealth":
        return asdp_pb2.InstanceHealth(
            healthy=self.healthy,
            reason=self.reason,
            active_sessions=self.active_sessions,
            cpu_usage=self.cpu_usage,
            memory_usage=self.memory_usage,
        )


@dataclass
class Inventory:
    """Full instance inventory (subagents + workspaces + health)."""

    subagents: List[SubagentInfo] = field(default_factory=list)
    workspaces: List[WorkspaceInfo] = field(default_factory=list)
    health: InstanceHealth = field(default_factory=InstanceHealth)

    def to_proto(self) -> "asdp_pb2.InventoryReport":
        return asdp_pb2.InventoryReport(
            subagents=[s.to_proto() for s in self.subagents],
            workspaces=[w.to_proto() for w in self.workspaces],
            health=self.health.to_proto(),
        )

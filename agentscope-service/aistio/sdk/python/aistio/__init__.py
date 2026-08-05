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

"""aistio Python SDK — 异构 Agent 框架的数据面适配层。

旁路拦截各框架 Session / Context / Subagent / Workspace 数据，经混合通道
（ASDP gRPC 上行推送 + 内嵌 HTTP 合约服务）送到 aistio 控制面。

用户入口（sdk-design §5.4）::

    import aistio
    from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions

    client = ClaudeSDKClient(ClaudeAgentOptions(...))
    aistio.instrument(
        client,
        control_plane="aistiod.aistio-system:9090",
        agent_name="my-claude-agent",
        namespace="default",
        enable_events=False,      # Level 2 默认关
        contract_http_port=8080,
    )
"""
from __future__ import annotations

import os
import socket
from typing import Any, Optional

__version__ = "0.1.0"

from .adapters.base import FrameworkAdapter
from .adapters.registry import find_adapter, register_adapter, registered_adapters
from .bridge import SessionBridge
from .context import ContextMessage, ContextSnapshot, ContextTracker, ToolInfo
from .events import (
    EVENT_COMPACTION,
    EVENT_MESSAGE,
    EVENT_SESSION_END,
    EVENT_SESSION_START,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    MessageItem,
    MessagePage,
    SessionEvent,
)
from .inventory import InstanceHealth, Inventory, SubagentInfo, WorkspaceInfo


def instrument(
    target: Any,
    *,
    control_plane: str,
    agent_name: str,
    namespace: str = "default",
    instance_id: Optional[str] = None,
    enable_events: bool = False,
    contract_http_port: int = 8080,
    session_affinity: str = "",
    start_http: bool = True,
    adapter: Optional[FrameworkAdapter] = None,
) -> SessionBridge:
    """一行代码接入任何框架（framework-integration §3.1 / sdk-design §5.4）。

    自动识别框架类型并挂载匹配的 ``FrameworkAdapter``；返回已启动的
    ``SessionBridge``（可作上下文管理器，``with ... as bridge:``）。

    ``instance_id`` 缺省取 ``HOSTNAME`` 环境变量（K8s Downward API 注入的
    Pod 名），再缺省取主机名。
    """
    if adapter is None:
        adapter = find_adapter(target)
        if adapter is None:
            raise ValueError(f"unsupported framework: {type(target).__name__}")
    if not instance_id:
        instance_id = os.environ.get("HOSTNAME") or socket.gethostname()
    bridge = SessionBridge(
        control_plane=control_plane,
        agent_name=agent_name,
        namespace=namespace,
        instance_id=instance_id,
        enable_events=enable_events,
        contract_http_port=contract_http_port,
        session_affinity=session_affinity,
        start_http=start_http,
    )
    bridge.attach_target(target, adapter=adapter)
    bridge.start()
    return bridge


__all__ = [
    "__version__",
    # 入口
    "instrument",
    "SessionBridge",
    "FrameworkAdapter",
    "register_adapter",
    "registered_adapters",
    "find_adapter",
    # events
    "SessionEvent",
    "MessageItem",
    "MessagePage",
    "EVENT_SESSION_START",
    "EVENT_MESSAGE",
    "EVENT_TOOL_CALL",
    "EVENT_TOOL_RESULT",
    "EVENT_SESSION_END",
    "EVENT_COMPACTION",
    # context
    "ContextMessage",
    "ContextSnapshot",
    "ContextTracker",
    "ToolInfo",
    # inventory
    "SubagentInfo",
    "WorkspaceInfo",
    "InstanceHealth",
    "Inventory",
]

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
        control_plane_http="http://aistiod.aistio-system:8080",
        agent_key="my-claude-agent",
        namespace="default",
        enable_events=True,       # 持久化事件是会话历史的默认事实源
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
from .collaboration import CollaborationClient, CollaborationError
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
from .orchestration import OrchestrationClient, OrchestrationError


def instrument(
    target: Any,
    *,
    control_plane: str,
    agent_key: str,
    tenant: str = "default",
    internal_token: Optional[str] = None,
    registration_credential: Optional[str] = None,
    control_plane_http: str = "",
    agent_id: str = "",
    binding_id: str = "",
    namespace: str = "default",
    instance_key: Optional[str] = None,
    generation: int = 0,
    enable_events: bool = True,
    event_journal_dir: str = "",
    contract_http_port: int = 8080,
    contract_http_base_url: str = "",
    session_affinity: str = "",
    start_http: bool = True,
    start_grpc: bool = True,
    adapter: Optional[FrameworkAdapter] = None,
) -> SessionBridge:
    """一行代码接入任何框架（framework-integration §3.1 / sdk-design §5.4）。

    自动识别框架类型并挂载匹配的 ``FrameworkAdapter``；返回已启动的
    ``SessionBridge``（可作上下文管理器，``with ... as bridge:``）。

    ``instance_key`` 缺省取 ``HOSTNAME`` 环境变量（K8s Downward API 注入的
    Pod 名），再缺省取主机名。

    传入 ``control_plane_http`` 时，SDK 会先通过 v5 注册接口获得稳定的
    ``agentId/bindingId/generation`` 和 registration credential，再建立 ASDP。
    已持久化这些值的实例也可直接传入，跳过首次注册。
    """
    if adapter is None:
        adapter = find_adapter(target)
        if adapter is None:
            raise ValueError(f"unsupported framework: {type(target).__name__}")
    if not instance_key:
        instance_key = os.environ.get("HOSTNAME") or socket.gethostname()
    if internal_token is None:
        internal_token = os.environ.get("AISTIO_INTERNAL_TOKEN", "")
    if registration_credential is None:
        registration_credential = os.environ.get("AISTIO_REGISTRATION_CREDENTIAL", "")
    bridge = SessionBridge(
        control_plane=control_plane,
        control_plane_http=control_plane_http,
        agent_key=agent_key,
        tenant=tenant,
        internal_token=internal_token,
        registration_credential=registration_credential,
        agent_id=agent_id,
        binding_id=binding_id,
        namespace=namespace,
        instance_key=instance_key,
        generation=generation,
        enable_events=enable_events,
        event_journal_dir=event_journal_dir,
        contract_http_port=contract_http_port,
        contract_http_base_url=contract_http_base_url,
        session_affinity=session_affinity,
        start_http=start_http,
        start_grpc=start_grpc,
    )
    bridge.attach_target(target, adapter=adapter)
    bridge.start()
    return bridge


__all__ = [
    "__version__",
    # 入口
    "instrument",
    "SessionBridge",
	"CollaborationClient",
	"CollaborationError",
	"OrchestrationClient",
	"OrchestrationError",
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

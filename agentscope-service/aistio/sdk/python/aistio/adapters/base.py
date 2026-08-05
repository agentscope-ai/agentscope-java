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

"""``FrameworkAdapter`` 抽象基类（sdk-design.md §5.2 / framework-integration §3.3）。

旁路原则（framework-integration §3.4）：拦截只**复制**数据，不替换、不阻塞
框架原有路径；旁路上报失败静默忽略。

可选方法默认 ``raise NotImplementedError`` —— 未覆写的方法不会在
capabilities 中声明，控制面据此门控（sdk-design §2.4）。
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Callable, List, Optional

from ..context import ContextSnapshot
from ..events import MessagePage, SessionEvent
from ..inventory import SubagentInfo, WorkspaceInfo

# ─── 命令词汇（与控制面 SessionCommand.command / HTTP 合约对齐）───
COMMAND_COMPRESS = "compress"
COMMAND_TERMINATE = "terminate"
COMMAND_ABORT = "abort"

KNOWN_COMMANDS = frozenset({COMMAND_COMPRESS, COMMAND_TERMINATE, COMMAND_ABORT})


def get_field(obj: Any, name: str, default: Any = None) -> Any:
    """Duck-typed field access: mapping key or attribute."""
    if obj is None:
        return default
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


class FrameworkAdapter(ABC):
    """每种 Agent 框架一个适配器，接口统一。"""

    @abstractmethod
    def framework_name(self) -> str:
        """框架标识，如 ``claude-agent-sdk`` / ``langchain`` / ``adk``。"""
        ...

    def framework_version(self) -> str:
        """框架版本（可选；适配器可覆写以上报到 Level 1 快照）。"""
        return ""

    @abstractmethod
    def can_handle(self, target: Any) -> bool:
        """判断能否处理这个对象（``instrument()`` 自动识别用）。"""
        ...

    @abstractmethod
    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        """附加到框架对象；Session 事件经 ``emit`` 回调旁路发出。

        实现必须遵循旁路原则：主路径先成功，``emit`` 失败静默忽略。
        """
        ...

    @abstractmethod
    def detach(self) -> None:
        """移除拦截，恢复框架对象原状。"""
        ...

    @abstractmethod
    async def extract_context(self, session_id: str) -> ContextSnapshot:
        """提取指定 Session 的当前**生效** Context 快照（压缩后视图）。"""
        ...

    # ─── 可选扩展（默认 NotImplemented → 不声明对应 capability）───

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        """Level 3 完整历史（分页）。"""
        raise NotImplementedError

    async def list_subagents(self) -> List[SubagentInfo]:
        """当前实例的 subagent 清单。"""
        raise NotImplementedError

    async def workspace_info(self) -> List[WorkspaceInfo]:
        """当前实例的 workspace 清单。"""
        raise NotImplementedError

    async def handle_command(
        self, session_id: str, command: str, params: Optional[bytes] = None
    ) -> None:
        """执行控制命令：``compress`` | ``terminate``（及未来扩展）。"""
        raise NotImplementedError

    async def abort(self, session_id: str) -> None:
        """中止当前 turn（不 terminate 会话）。Capability：``session-abort``。"""
        raise NotImplementedError

    async def list_tasks(self, session_id: str) -> List[dict]:
        """会话任务明细。Capability：``task-query``。

        每项建议字段：``id`` / ``subject`` / ``state`` / ``owner`` /
        ``blockedBy`` / ``updatedAt`` / ``frameworkMeta``（未知则省略）。
        """
        raise NotImplementedError

    def session_fields(self, session_id: str) -> dict:
        """可选冻结字段，供 session list / state 快照合并。

        可含：``busy`` (bool)、``model`` (str)、``maxTokens`` (int)。
        未知键必须省略（禁止用 ``false`` / ``0`` / ``""`` 填补）。
        """
        return {}

    # ─── capability 探测 ───

    def supports(self, method_name: str) -> bool:
        """子类是否覆写了某个可选方法。"""
        method = getattr(type(self), method_name, None)
        base = getattr(FrameworkAdapter, method_name, None)
        return method is not None and method is not base

    def capabilities(self) -> List[str]:
        """该适配器**实例**支持的能力（adapter 决定的部分）。

        ``session-reporting`` / ``event-reporting`` / ``context-reporting``
        属推送能力，由 SessionBridge 按配置补充，不在此处声明。
        """
        caps = ["context-query"]  # extract_context 为抽象方法，必有
        if self.supports("list_messages"):
            caps.append("message-query")
        if self.supports("list_subagents"):
            caps.append("subagent-inventory")
        if self.supports("workspace_info"):
            caps.append("workspace-inventory")
        if self.supports("handle_command"):
            caps.append("session-command")
        if self.supports("abort"):
            caps.append("session-abort")
        if self.supports("list_tasks"):
            caps.append("task-query")
        return caps

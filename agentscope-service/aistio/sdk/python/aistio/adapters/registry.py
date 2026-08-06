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

"""适配器注册表（framework-integration §3.6）。

``instrument()`` 按注册顺序匹配：第一个 ``can_handle(target)`` 为真的适配器
胜出。匹配成功后返回该适配器的**浅拷贝**（prototype 模式），避免多次
``instrument()`` 共享运行时状态。
"""
from __future__ import annotations

import copy
from typing import Any, List, Optional

from .base import FrameworkAdapter

_adapters: List[FrameworkAdapter] = []


def register_adapter(adapter: FrameworkAdapter, *, first: bool = False) -> None:
    """注册新的框架适配器；``first=True`` 插入队首（优先匹配）。"""
    if first:
        _adapters.insert(0, adapter)
    else:
        _adapters.append(adapter)


def registered_adapters() -> List[FrameworkAdapter]:
    """当前注册表快照（含内置适配器）。"""
    return list(_adapters)


def find_adapter(target: Any) -> Optional[FrameworkAdapter]:
    """找到能处理 ``target`` 的适配器（返回浅拷贝实例，未 attach）。"""
    for adapter in _adapters:
        try:
            if adapter.can_handle(target):
                return copy.copy(adapter)
        except Exception:
            # can_handle 必须无副作用；异常视为不匹配。
            continue
    return None


def _register_builtins() -> None:
    from .adk import ADKAdapter
    from .agentscope import AgentScopeAdapter
    from .claude import ClaudeAgentSDKAdapter
    from .langchain import LangChainAdapter
    from .openai_agents import OpenAIAgentsAdapter
    from .openclaw import OpenClawAdapter

    for cls in (
        # AgentScope 按模块前缀精确匹配，放队首避免被其他适配器的 duck-typing 抢走。
        AgentScopeAdapter,
        ClaudeAgentSDKAdapter,
        LangChainAdapter,
        ADKAdapter,
        OpenClawAdapter,
        OpenAIAgentsAdapter,
    ):
        register_adapter(cls())


_register_builtins()

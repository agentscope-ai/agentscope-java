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

"""框架适配器单元测试（duck-typed fake 框架对象；旁路原则验证）。

异步方法经 ``asyncio.run`` 驱动，避免依赖 pytest-asyncio。
"""
from __future__ import annotations

import asyncio
import json

from aistio.adapters import find_adapter, register_adapter, registered_adapters
from aistio.adapters.adk import ADKAdapter
from aistio.adapters.agentscope import AgentScopeAdapter
from aistio.adapters.base import FrameworkAdapter
from aistio.adapters.claude import ClaudeAgentSDKAdapter
from aistio.adapters.langchain import LangChainAdapter
from aistio.adapters.openai_agents import OpenAIAgentsAdapter
from aistio.adapters.openclaw import OpenClawAdapter


def run(coro):
    return asyncio.run(coro)


# ─── fake Claude Agent SDK ───


class FakeClaudeStore:
    def __init__(self):
        self.data = {}

    async def append(self, key, entries):
        self.data.setdefault(key["session_id"], []).extend(entries)

    async def load(self, key):
        return self.data.get(key["session_id"])


class FakeClaudeOptions:
    def __init__(self, store):
        self.session_store = store


class ClaudeSDKClient:  # 类名即识别信号
    def __init__(self, options):
        self.options = options
        self.commands = []

    async def compress_session(self, session_id):
        self.commands.append(("compress", session_id))

    async def terminate_session(self, session_id):
        self.commands.append(("terminate", session_id))


def _claude_client():
    store = FakeClaudeStore()
    return ClaudeSDKClient(FakeClaudeOptions(store)), store


def test_claude_can_handle_and_registry_match():
    client, _ = _claude_client()
    adapter = find_adapter(client)
    assert adapter is not None and adapter.framework_name() == "claude-agent-sdk"


def test_claude_attach_intercepts_append_and_restores_on_detach():
    client, store = _claude_client()
    events = []
    adapter = ClaudeAgentSDKAdapter()
    adapter.attach(client, events.append)

    run(
        client.options.session_store.append(
            {"session_id": "s1"},
            [{"type": "user", "content": "hello"}, {"type": "assistant", "content": "hi"}],
        )
    )
    # 旁路事件完整
    assert [e.role for e in events] == ["user", "assistant"]
    assert all(e.event_type == "message" for e in events)
    # 主路径数据完整（旁路不替换存储）
    assert len(store.data["s1"]) == 2

    adapter.detach()
    assert client.options.session_store is store


def test_claude_summary_entry_becomes_compaction_event():
    client, _ = _claude_client()
    events = []
    adapter = ClaudeAgentSDKAdapter()
    adapter.attach(client, events.append)
    run(
        client.options.session_store.append(
            {"session_id": "s1"}, [{"type": "summary", "summary": "compressed"}]
        )
    )
    assert events[0].event_type == "compaction" and events[0].content == "compressed"


def test_claude_emit_failure_does_not_break_main_path():
    client, store = _claude_client()

    def bad_emit(_event):
        raise RuntimeError("boom")

    adapter = ClaudeAgentSDKAdapter()
    adapter.attach(client, bad_emit)
    # 主路径必须成功，即使旁路上报抛异常
    run(client.options.session_store.append({"session_id": "s1"}, [{"type": "user", "content": "x"}]))
    assert len(store.data["s1"]) == 1


def test_claude_extract_context_rebuilds_effective_view():
    client, _ = _claude_client()
    adapter = ClaudeAgentSDKAdapter()
    adapter.attach(client, lambda e: None)
    run(
        client.options.session_store.append(
            {"session_id": "s1"},
            [
                {"type": "user", "content": "old"},
                {"type": "summary", "summary": "compressed"},
                {"type": "user", "content": "new"},
            ],
        )
    )
    ctx = run(adapter.extract_context("s1"))
    # 生效视图 = 压缩摘要 + 后续新消息
    assert ctx.is_compacted and ctx.compaction_summary == "compressed"
    assert [m.content for m in ctx.messages] == ["compressed", "new"]
    assert ctx.messages[0].is_compaction
    assert ctx.framework == "claude-agent-sdk"


def test_claude_list_messages_paginates():
    client, _ = _claude_client()
    adapter = ClaudeAgentSDKAdapter()
    adapter.attach(client, lambda e: None)
    run(
        client.options.session_store.append(
            {"session_id": "s1"}, [{"type": "user", "content": f"m{i}"} for i in range(5)]
        )
    )
    page = run(adapter.list_messages("s1", offset=2, limit=2))
    assert page.total == 5 and [m.seq for m in page.messages] == [3, 4]


def test_claude_handle_command_dispatches_to_target():
    client, _ = _claude_client()
    adapter = ClaudeAgentSDKAdapter()
    adapter.attach(client, lambda e: None)
    run(adapter.handle_command("s1", "compress"))
    run(adapter.handle_command("s1", "terminate"))
    assert client.commands == [("compress", "s1"), ("terminate", "s1")]


def test_claude_capabilities():
    caps = ClaudeAgentSDKAdapter().capabilities()
    assert "context-query" in caps and "message-query" in caps and "session-command" in caps
    assert "subagent-inventory" not in caps
    assert "session-abort" not in caps and "task-query" not in caps


def test_base_adapter_abort_and_list_tasks_unsupported():
    class Minimal(FrameworkAdapter):
        def framework_name(self):
            return "min"

        def can_handle(self, target):
            return False

        def attach(self, target, emit):
            pass

        def detach(self):
            pass

        async def extract_context(self, session_id):
            raise NotImplementedError

    adapter = Minimal()
    assert not adapter.supports("abort") and not adapter.supports("list_tasks")
    try:
        run(adapter.abort("s1"))
    except NotImplementedError:
        pass
    else:
        raise AssertionError("abort should be unsupported by default")
    try:
        run(adapter.list_tasks("s1"))
    except NotImplementedError:
        pass
    else:
        raise AssertionError("list_tasks should be unsupported by default")


# ─── fake LangChain ───


class FakeChain:
    __module__ = "langchain.chains"

    def __init__(self):
        self.callbacks = []
        self.checkpointer = None


class FakeCheckpointer:
    def __init__(self, state):
        self.state = state

    def get(self, config):
        assert config["configurable"]["thread_id"]
        return self.state


def _msg(type_, content):
    return type("M", (), {"type": type_, "content": content})()


def test_langchain_callbacks_emit_events_and_detach_restores():
    chain = FakeChain()
    adapter = LangChainAdapter()
    assert adapter.can_handle(chain)
    events = []
    adapter.attach(chain, events.append)
    assert len(chain.callbacks) == 1

    handler = chain.callbacks[0]
    handler.on_tool_start({"name": "bash"}, "ls", metadata={"session_id": "t1"})
    handler.on_tool_end("files", metadata={"session_id": "t1"})
    handler.on_chain_start({}, {}, metadata={"session_id": "t1"})
    handler.on_chain_end({}, metadata={"session_id": "t1"})

    types = [e.event_type for e in events]
    assert types == ["tool_call", "tool_result", "session_start", "session_end"]
    assert events[0].tool_name == "bash" and events[1].tool_output == "files"
    assert all(e.session_id == "t1" for e in events)

    adapter.detach()
    assert chain.callbacks == []


def test_langchain_extract_context_from_checkpointer():
    chain = FakeChain()
    chain.checkpointer = FakeCheckpointer(
        {
            "channel_values": {
                "messages": [_msg("human", "q"), _msg("ai", "a")],
                "memory": {"k": 1},
            }
        }
    )
    adapter = LangChainAdapter()
    adapter.attach(chain, lambda e: None)
    ctx = run(adapter.extract_context("t1"))
    assert [m.role for m in ctx.messages] == ["user", "assistant"]
    assert json.loads(ctx.framework_state.decode()) == {"memory": {"k": 1}}

    page = run(adapter.list_messages("t1"))
    assert page.total == 2 and page.messages[0].role == "user"


# ─── fake ADK ───


class FakeADKSession:
    def __init__(self, id):
        self.id = id
        self.events = []
        self.state = {"goal": "win"}


class FakeADKEvent:
    def __init__(self, author, text, role=None):
        self.author = author
        part = type("P", (), {"text": text, "function_call": None, "function_response": None})()
        self.content = type(
            "C", (), {"role": role or ("user" if author == "user" else "model"), "parts": [part]}
        )()


class FakeADKService:
    def __init__(self):
        self.sessions = {}
        self.deleted = []

    async def append_event(self, session, event):
        session.events.append(event)
        return event

    async def get_session(self, session_id):
        return self.sessions.get(session_id)

    async def delete_session(self, session_id):
        self.deleted.append(session_id)


def test_adk_intercepts_append_event_and_normalizes_model_role():
    svc = FakeADKService()
    adapter = ADKAdapter()
    assert adapter.can_handle(svc)
    events = []
    adapter.attach(svc, events.append)
    sess = FakeADKSession("s1")
    svc.sessions["s1"] = sess

    run(svc.append_event(sess, FakeADKEvent("user", "question")))
    run(svc.append_event(sess, FakeADKEvent("agent", "answer")))
    assert [e.role for e in events] == ["user", "assistant"]

    ctx = run(adapter.extract_context("s1"))
    assert ctx.framework == "adk" and len(ctx.messages) == 2
    assert json.loads(ctx.framework_state.decode()) == {"goal": "win"}

    run(adapter.handle_command("s1", "terminate"))
    assert svc.deleted == ["s1"]

    adapter.detach()
    # detach 后 append 不再发事件
    run(svc.append_event(sess, FakeADKEvent("user", "after")))
    assert len(events) == 2


# ─── fake OpenClaw gateway ───


class FakeGateway:
    def __init__(self):
        self.calls = []

    async def call(self, method, params):
        self.calls.append(method)
        if method == "sessions.get":
            return {"status": "running", "model": "claude", "modelProvider": "anthropic"}
        if method == "sessions.preview":
            return {"events": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "yo"}]}
        if method == "agents.list":
            return {"agents": [{"name": "sub-a", "description": "helper", "url": "http://sub"}]}
        if method == "workspaces.list":
            return {"workspaces": [{"path": "/ws/1", "mode": "isolated", "sizeBytes": 42, "ownerRef": "s1"}]}
        raise RuntimeError(f"unknown method {method}")


def test_openclaw_gateway_roundtrip():
    adapter = OpenClawAdapter()
    assert adapter.can_handle(FakeGateway())
    assert adapter.can_handle("wss://openclaw-gw.local:8080")
    adapter.attach(FakeGateway(), lambda e: None)

    ctx = run(adapter.extract_context("s1"))
    assert ctx.framework == "openclaw" and len(ctx.messages) == 2
    assert json.loads(ctx.framework_state.decode())["model"] == "claude"

    subs = run(adapter.list_subagents())
    assert subs[0].name == "sub-a" and subs[0].url == "http://sub"
    ws = run(adapter.workspace_info())
    assert ws[0].path == "/ws/1" and ws[0].size_bytes == 42

    page = run(adapter.list_messages("s1", offset=1, limit=1))
    assert page.total == 2 and page.messages[0].role == "assistant"


def test_openclaw_inventory_rpc_failure_degrades_to_empty():
    class BrokenGateway:
        async def call(self, method, params):
            raise RuntimeError("connection lost")

    adapter = OpenClawAdapter()
    adapter.attach(BrokenGateway(), lambda e: None)
    assert run(adapter.list_subagents()) == []
    assert run(adapter.workspace_info()) == []


# ─── fake OpenAI Agents session backend ───


class FakeSessionBackend:
    def __init__(self, session_id):
        self.session_id = session_id
        self.items = []

    async def add_items(self, items):
        self.items.extend(items)

    async def get_items(self, limit=None):
        return self.items if limit is None else self.items[-limit:]

    async def clear_session(self):
        self.items.clear()


def test_openai_agents_backend_intercept_and_terminate():
    backend = FakeSessionBackend("s7")
    adapter = OpenAIAgentsAdapter()
    assert adapter.can_handle(backend)
    events = []
    adapter.attach(backend, events.append)

    run(
        backend.add_items(
            [
                {"role": "user", "content": "q"},
                {"role": "assistant", "content": [{"type": "output_text", "text": "a"}]},
            ]
        )
    )
    assert len(events) == 2 and events[1].content == "a"

    ctx = run(adapter.extract_context("s7"))
    assert ctx.framework == "openai-agents" and len(ctx.messages) == 2

    run(adapter.handle_command("s7", "terminate"))
    assert backend.items == []


# ─── fake AgentScope (Python) ───


class FakeMsg:
    """AgentScope ``Msg``：content 为 str 或 block 列表（block 是 TypedDict）。"""

    def __init__(self, role, content, metadata=None, usage=None):
        self.role = role
        self.content = content
        self.metadata = metadata
        self.usage = usage

    def get_text_content(self):
        if isinstance(self.content, str):
            return self.content
        return "\n".join(b.get("text", "") for b in self.content if b.get("type") == "text")


class FakeAgentScopeMemory:
    def __init__(self):
        self.entries = []  # (msg, mark)

    def add(self, msg, mark=None):
        self.entries.append((msg, mark))

    async def get_memory(self, exclude_mark=None, mark=None):
        out = []
        for msg, msg_mark in self.entries:
            if exclude_mark is not None and msg_mark == exclude_mark:
                continue
            if mark is not None and msg_mark != mark:
                continue
            out.append(msg)
        return out


class FakeToolkit:
    def get_json_schemas(self):
        return [
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search the web",
                    "parameters": {"type": "object", "properties": {"q": {"type": "string"}}},
                },
            }
        ]


class FakeAgentScopeAgent:
    __module__ = "agentscope.agent"

    supported_hook_types = [
        "pre_reply",
        "post_reply",
        "post_observe",
        "post_reasoning",
        "post_acting",
    ]

    def __init__(self):
        self.id = "agent-42"
        self.name = "Friday"
        self.sys_prompt = "You are Friday."
        self.memory = FakeAgentScopeMemory()
        self.toolkit = FakeToolkit()
        self.hooks = {}
        self.interrupted = 0
        self.model = type(
            "M",
            (),
            {"model_name": "qwen-max", "context_window_size": 128000},
        )()
        self.plan_notebook = None

    def register_instance_hook(self, hook_type, hook_name, hook):
        if hook_type not in self.supported_hook_types:
            raise ValueError(f"unsupported hook type: {hook_type}")
        self.hooks[(hook_type, hook_name)] = hook

    def remove_instance_hook(self, hook_type, hook_name):
        self.hooks.pop((hook_type, hook_name), None)

    async def interrupt(self):
        self.interrupted += 1

    # 测试驱动：模拟框架在主路径上回调 hook
    def fire(self, hook_type, kwargs, output=None):
        hook = self.hooks.get((hook_type, "aistio"))
        if hook is None:
            return None
        if hook_type.startswith("pre_"):
            return hook(self, kwargs)
        return hook(self, kwargs, output)


def test_agentscope_can_handle_and_registry_match():
    agent = FakeAgentScopeAgent()
    adapter = find_adapter(agent)
    assert adapter is not None and adapter.framework_name() == "agentscope"


def test_agentscope_reply_hooks_emit_messages_and_detach_unregisters():
    agent = FakeAgentScopeAgent()
    events = []
    adapter = AgentScopeAdapter()
    adapter.attach(agent, events.append)
    assert len(agent.hooks) == 5

    agent.fire("pre_reply", {"msg": FakeMsg("user", "question")})
    agent.fire(
        "post_reply", {"msg": FakeMsg("user", "question")}, FakeMsg("assistant", "answer")
    )

    assert [e.event_type for e in events] == ["session_start", "message", "message"]
    assert [e.role for e in events[1:]] == ["user", "assistant"]
    assert all(e.session_id == "agent-42" for e in events)

    # 第二轮不再重复 session_start（reply 是轮，不是会话边界）
    agent.fire("pre_reply", {"msg": FakeMsg("user", "again")})
    assert [e.event_type for e in events[3:]] == ["message"]

    adapter.detach()
    assert agent.hooks == {}


def test_agentscope_reasoning_and_acting_hooks_emit_tool_events():
    agent = FakeAgentScopeAgent()
    events = []
    AgentScopeAdapter().attach(agent, events.append)

    reasoning = FakeMsg(
        "assistant",
        [{"type": "tool_use", "id": "c1", "name": "search", "input": {"q": "aistio"}}],
    )
    agent.fire("post_reasoning", {}, reasoning)
    acting = FakeMsg(
        "system",
        [{"type": "tool_result", "id": "c1", "name": "search", "output": [{"type": "text", "text": "hit"}]}],
    )
    agent.fire("post_acting", {}, acting)

    assert [e.event_type for e in events] == ["tool_call", "tool_result"]
    assert events[0].tool_name == "search"
    assert json.loads(events[0].tool_input.decode()) == {"q": "aistio"}
    assert events[1].tool_output == "hit"


def test_agentscope_hooks_stay_transparent_when_emit_fails():
    agent = FakeAgentScopeAgent()

    def bad_emit(_event):
        raise RuntimeError("boom")

    AgentScopeAdapter().attach(agent, bad_emit)
    # post-hook 必须返回 None（返回非 None 会替换框架主路径的输出）
    assert agent.fire("pre_reply", {"msg": FakeMsg("user", "q")}) is None
    assert agent.fire("post_reply", {}, FakeMsg("assistant", "a")) is None


def test_agentscope_agent_base_without_react_hooks_still_attaches():
    agent = FakeAgentScopeAgent()
    agent.supported_hook_types = ["pre_reply", "post_reply", "post_observe"]
    adapter = AgentScopeAdapter()
    adapter.attach(agent, lambda e: None)
    assert set(t for t, _ in agent.hooks) == {"pre_reply", "post_reply", "post_observe"}


def test_agentscope_extract_context_excludes_compressed_messages():
    agent = FakeAgentScopeAgent()
    agent.memory.add(FakeMsg("user", "ancient"), mark="compressed")
    agent.memory.add(FakeMsg("user", "recent"))
    agent.memory.add(FakeMsg("assistant", "reply", usage={"input_tokens": 10, "output_tokens": 4}))
    adapter = AgentScopeAdapter()
    adapter.attach(agent, lambda e: None)

    ctx = run(adapter.extract_context("s1"))
    assert ctx.framework == "agentscope"
    assert ctx.system_prompt == "You are Friday."
    assert [m.content for m in ctx.messages] == ["recent", "reply"]
    assert ctx.is_compacted and ctx.original_message_count == 3
    assert ctx.total_tokens == 14
    assert ctx.tools[0].name == "search" and ctx.tools[0].description == "search the web"


def test_agentscope_list_messages_covers_full_history():
    agent = FakeAgentScopeAgent()
    agent.memory.add(FakeMsg("user", "ancient"), mark="compressed")
    for i in range(3):
        agent.memory.add(FakeMsg("user", f"m{i}"))
    adapter = AgentScopeAdapter()
    adapter.attach(agent, lambda e: None)

    page = run(adapter.list_messages("s1", offset=1, limit=2))
    # Level 3 是完整历史，含被压缩掉的消息
    assert page.total == 4 and [m.content for m in page.messages] == ["m0", "m1"]


def test_agentscope_commands_terminate_and_unsupported_compress():
    agent = FakeAgentScopeAgent()
    adapter = AgentScopeAdapter()
    adapter.attach(agent, lambda e: None)

    run(adapter.handle_command("s1", "terminate"))
    assert agent.interrupted == 1

    # 未配置 CompressionConfig 的 Agent 没有按需压缩入口，如实报 unsupported
    try:
        run(adapter.handle_command("s1", "compress"))
    except NotImplementedError:
        pass
    else:
        raise AssertionError("compress should be unsupported without a compression entry point")


def test_agentscope_session_resolver_overrides_agent_id():
    agent = FakeAgentScopeAgent()
    events = []
    adapter = AgentScopeAdapter(session_resolver=lambda a, kw: kw.get("session_id"))
    adapter.attach(agent, events.append)
    agent.fire("pre_reply", {"msg": FakeMsg("user", "q"), "session_id": "conv-9"})
    assert events[0].session_id == "conv-9"


def test_agentscope_session_id_from_message_metadata():
    agent = FakeAgentScopeAgent()
    events = []
    AgentScopeAdapter().attach(agent, events.append)
    agent.fire("pre_reply", {"msg": FakeMsg("user", "q", metadata={"session_id": "meta-1"})})
    assert events[0].session_id == "meta-1"


def test_agentscope_capabilities():
    caps = AgentScopeAdapter().capabilities()
    assert "context-query" in caps and "message-query" in caps and "session-command" in caps
    assert "session-abort" in caps and "task-query" in caps
    assert "subagent-inventory" not in caps


def test_agentscope_busy_model_and_abort_tasks():
    agent = FakeAgentScopeAgent()
    agent.plan_notebook = type(
        "PN",
        (),
        {
            "current_plan": type(
                "Plan",
                (),
                {
                    "subtasks": [
                        {"id": "t1", "name": "search docs", "state": "in_progress"},
                        {"id": "t2", "name": "write summary", "state": "todo"},
                    ]
                },
            )()
        },
    )()
    adapter = AgentScopeAdapter()
    adapter.attach(agent, lambda e: None)

    fields = adapter.session_fields("agent-42")
    assert fields["busy"] is False
    assert fields["model"] == "qwen-max"
    assert fields["maxTokens"] == 128000

    agent.fire("pre_reply", {"msg": FakeMsg("user", "q")})
    assert adapter.session_fields("agent-42")["busy"] is True

    agent.fire("post_reply", {"msg": FakeMsg("user", "q")}, FakeMsg("assistant", "a"))
    assert adapter.session_fields("agent-42")["busy"] is False

    run(adapter.abort("agent-42"))
    assert agent.interrupted == 1

    tasks = run(adapter.list_tasks("agent-42"))
    assert [t["id"] for t in tasks] == ["t1", "t2"]
    assert tasks[0]["state"] == "in_progress" and tasks[1]["state"] == "pending"


# ─── registry ───


def test_registry_register_first_takes_precedence():
    class CatchAllAdapter(FrameworkAdapter):
        def framework_name(self):
            return "catch-all"

        def can_handle(self, target):
            return True

        def attach(self, target, emit):
            pass

        def detach(self):
            pass

        async def extract_context(self, session_id):
            raise NotImplementedError

    marker = CatchAllAdapter()
    register_adapter(marker, first=True)
    try:
        client, _ = _claude_client()
        found = find_adapter(client)
        assert found is not None and found.framework_name() == "catch-all"
        # prototype 语义：返回浅拷贝而非注册实例本身
        assert found is not marker
    finally:
        from aistio.adapters import registry as registry_mod

        registry_mod._adapters.remove(marker)


def test_find_adapter_returns_none_for_unknown():
    assert find_adapter(object()) is None
    assert len(registered_adapters()) >= 5

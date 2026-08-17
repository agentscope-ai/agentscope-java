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

"""AgentScope Python 适配器：注册 instance hook 旁路观测，Context 从 Agent
的 ``memory`` 重建。

面向**用户自带代码**（BYO）场景：用户用 ``agentscope`` 写好 Agent 并自行部
署，一行 ``aistio.instrument(agent, ...)`` 接入控制面。与 managed agents 模式
下 service-dataplane 直接实现 ``/agentscope/*`` 合约是两条独立路径。

零硬依赖：不 import ``agentscope``，全部 duck-typing。

拦截点（AgentScope 原生 hook 机制，post-hook 返回 ``None`` 即不改主路径）：

===============  ==========================================================
``pre_reply``    首次触发时补 ``session_start``；发用户输入消息
``post_reply``   发最终 assistant 消息
``post_reasoning``  发 ``tool_call``（只取 ToolUseBlock，文本留给 reply 避免重复）
``post_acting``  发 ``tool_result``
``post_observe`` 发被观察到的外部消息（多 Agent 编排场景）
===============  ==========================================================

``_reasoning`` / ``_acting`` 的 hook 只有 ``ReActAgentBase`` 及子类支持，注册
失败时静默跳过（``AgentBase`` 直接子类仍能拿到 reply 级数据）。

Session 语义：AgentScope Python 的 session_id 不在 Agent 上（由外部
``SessionBase.save_session_state(session_id=..., agent=agent)`` 传入），因此
本适配器按 ``session_resolver`` → 调用参数 ``session_id`` → 消息 metadata →
``agent.id`` 的顺序解析，可通过构造参数自定义::

    aistio.instrument(
        agent,
        control_plane="aistiod:9090",
        agent_name="my-agentscope-agent",
        adapter=AgentScopeAdapter(session_resolver=lambda a, kw: kw["session_id"]),
    )
"""
from __future__ import annotations

import inspect
from typing import Any, Callable, Dict, List, Optional

from ..context import ContextMessage, ContextSnapshot, ToolInfo
from ..events import (
    EVENT_MESSAGE,
    EVENT_SESSION_START,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    ROLE_ASSISTANT,
    ROLE_TOOL,
    ROLE_USER,
    MessageItem,
    MessagePage,
    SessionEvent,
)
from .base import COMMAND_COMPRESS, COMMAND_TERMINATE, FrameworkAdapter, get_field

#: 本适配器注册的 hook 名（detach 时按名移除，不影响用户自己的 hook）。
HOOK_NAME = "aistio"

#: 被压缩消息的 mark（AgentScope ``MemoryMark.COMPRESSED`` 的字面值）。
MARK_COMPRESSED = "compressed"

_HOOK_TYPES = (
    "pre_reply",
    "post_reply",
    "post_reasoning",
    "post_acting",
    "post_observe",
)


# ─── Msg 解构（AgentScope 的 content 可为 str 或 block 列表）───


def _blocks(msg: Any) -> List[Any]:
    content = get_field(msg, "content")
    if isinstance(content, list):
        return content
    return []


def _blocks_of(msg: Any, block_type: str) -> List[Any]:
    return [b for b in _blocks(msg) if get_field(b, "type") == block_type]


def _text(msg: Any) -> str:
    """消息的纯文本内容（优先框架自带的 ``get_text_content``）。"""
    if msg is None:
        return ""
    getter = getattr(msg, "get_text_content", None)
    if callable(getter):
        try:
            return getter() or ""
        except Exception:
            pass
    content = get_field(msg, "content")
    if isinstance(content, str):
        return content
    return "\n".join(
        str(get_field(b, "text", "") or "") for b in _blocks_of(msg, "text")
    ).strip()


def _role(msg: Any) -> str:
    """归一化到合约词汇；含 ToolResultBlock 的消息记为 ``tool``。"""
    if _blocks_of(msg, "tool_result"):
        return ROLE_TOOL
    role = get_field(msg, "role", "") or ""
    role = str(role).lower()
    return role if role in ("user", "assistant", "system", "tool") else ROLE_ASSISTANT


def _usage(msg: Any) -> tuple:
    """尽力从消息上取 token 用量（框架未挂 usage 时返回 ``(0, 0)``）。"""
    usage = get_field(msg, "usage")
    if usage is None:
        metadata = get_field(msg, "metadata")
        usage = get_field(metadata, "usage")
    if usage is None:
        return 0, 0
    tokens_in = get_field(usage, "input_tokens", 0) or get_field(usage, "prompt_tokens", 0) or 0
    tokens_out = (
        get_field(usage, "output_tokens", 0) or get_field(usage, "completion_tokens", 0) or 0
    )
    try:
        return int(tokens_in), int(tokens_out)
    except (TypeError, ValueError):
        return 0, 0


def _tool_result_text(block: Any) -> str:
    """ToolResultBlock 的 ``output`` 可为 str 或 block 列表。"""
    output = get_field(block, "output")
    if isinstance(output, str):
        return output
    if isinstance(output, list):
        return "\n".join(
            str(get_field(b, "text", "") or "") for b in output if get_field(b, "type") == "text"
        ).strip()
    return "" if output is None else str(output)


def _first_msg(value: Any) -> Any:
    """hook kwargs 里的 ``msg`` 可能是单条、列表或 None。"""
    if isinstance(value, (list, tuple)):
        return value[0] if value else None
    return value


class AgentScopeAdapter(FrameworkAdapter):
    """AgentScope Python ``AgentBase`` / ``ReActAgent`` 适配器。"""

    def __init__(
        self,
        session_resolver: Optional[Callable[[Any, Dict[str, Any]], str]] = None,
    ) -> None:
        self._session_resolver = session_resolver
        self._agent: Any = None
        self._emit: Optional[Callable[[SessionEvent], None]] = None
        self._registered: List[str] = []
        self._started_sessions: set = set()
        #: Sessions currently inside a reply turn (pre_reply … post_reply).
        self._busy_sessions: set = set()
        #: 最近一次解析出的 session_id —— HTTP 合约按 session 查询时用于兜底
        #: 校验（AgentScope 的 session 归属由调用方决定，Agent 自身不持有）。
        self._last_session_id = ""

    # ─── 身份 ───

    def framework_name(self) -> str:
        return "agentscope"

    def framework_version(self) -> str:
        try:
            from importlib.metadata import version

            return version("agentscope")
        except Exception:
            return ""

    def can_handle(self, target: Any) -> bool:
        mod = (type(target).__module__ or "").lower()
        if mod.startswith("agentscope"):
            return True
        # duck-typing：AgentScope 的 hook 注册口 + reply 主路径。
        return callable(getattr(target, "register_instance_hook", None)) and callable(
            getattr(target, "reply", None)
        )

    # ─── 拦截 ───

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._agent = target
        self._emit = emit
        register = getattr(target, "register_instance_hook", None)
        if not callable(register):
            return

        supported = set(getattr(target, "supported_hook_types", None) or _HOOK_TYPES)
        handlers = {
            "pre_reply": self._on_pre_reply,
            "post_reply": self._on_post_reply,
            "post_reasoning": self._on_post_reasoning,
            "post_acting": self._on_post_acting,
            "post_observe": self._on_post_observe,
        }
        for hook_type, handler in handlers.items():
            # ``_reasoning`` / ``_acting`` 的 hook 仅 ReActAgentBase 支持。
            if hook_type not in supported:
                continue
            try:
                register(hook_type=hook_type, hook_name=HOOK_NAME, hook=handler)
                self._registered.append(hook_type)
            except Exception:
                continue

    def detach(self) -> None:
        remove = getattr(self._agent, "remove_instance_hook", None)
        if callable(remove):
            for hook_type in self._registered:
                try:
                    remove(hook_type, HOOK_NAME)
                except Exception:
                    pass
        self._registered = []
        self._started_sessions = set()
        self._busy_sessions = set()
        self._agent = None
        self._emit = None

    # ─── hook 实现（旁路：一律返回 None，不改主路径）───

    def _safe_emit(self, event: SessionEvent) -> None:
        emit = self._emit
        if emit is None:
            return
        try:
            emit(event)
        except Exception:
            pass

    def _resolve_session(self, agent: Any, kwargs: Optional[Dict[str, Any]] = None) -> str:
        kwargs = kwargs or {}
        if self._session_resolver is not None:
            try:
                resolved = self._session_resolver(agent, kwargs)
                if resolved:
                    return str(resolved)
            except Exception:
                pass
        explicit = kwargs.get("session_id")
        if explicit:
            return str(explicit)
        metadata = get_field(_first_msg(kwargs.get("msg")), "metadata")
        from_meta = get_field(metadata, "session_id")
        if from_meta:
            return str(from_meta)
        return str(get_field(agent, "id", "") or get_field(agent, "name", "") or "default")

    def _mark_started(self, session_id: str) -> None:
        """首次见到该 session 时补一条 ``session_start``。

        AgentScope 的一次 ``reply()`` 只是会话中的一轮，会话边界不由框架给出，
        因此只在首轮补 start，不在每轮结束发 ``session_end``（会话终结由
        ``terminate`` 命令驱动）。
        """
        if session_id in self._started_sessions:
            return
        self._started_sessions.add(session_id)
        self._safe_emit(
            SessionEvent(session_id=session_id, seq=0, event_type=EVENT_SESSION_START)
        )

    def _on_pre_reply(self, agent: Any, kwargs: Dict[str, Any]) -> None:
        session_id = self._resolve_session(agent, kwargs)
        self._last_session_id = session_id
        self._busy_sessions.add(session_id)
        self._mark_started(session_id)
        for msg in _iter_msgs(kwargs.get("msg")):
            text = _text(msg)
            if not text:
                continue
            self._safe_emit(
                SessionEvent(
                    session_id=session_id,
                    seq=0,
                    event_type=EVENT_MESSAGE,
                    role=_role(msg) or ROLE_USER,
                    content=text,
                )
            )
        return None

    def _on_post_reply(self, agent: Any, kwargs: Dict[str, Any], output: Any) -> None:
        session_id = self._resolve_session(agent, kwargs)
        self._busy_sessions.discard(session_id)
        text = _text(output)
        if text:
            tokens_in, tokens_out = _usage(output)
            self._safe_emit(
                SessionEvent(
                    session_id=session_id,
                    seq=0,
                    event_type=EVENT_MESSAGE,
                    role=ROLE_ASSISTANT,
                    content=text,
                    tokens_in=tokens_in,
                    tokens_out=tokens_out,
                )
            )
        return None

    def _on_post_reasoning(self, agent: Any, kwargs: Dict[str, Any], output: Any) -> None:
        # 只取工具调用；推理产出的文本由 post_reply 统一上报，避免重复计数。
        session_id = self._resolve_session(agent, kwargs)
        tokens_in, tokens_out = _usage(output)
        for block in _blocks_of(output, "tool_use"):
            self._safe_emit(
                SessionEvent(
                    session_id=session_id,
                    seq=0,
                    event_type=EVENT_TOOL_CALL,
                    role=ROLE_ASSISTANT,
                    tool_name=str(get_field(block, "name", "") or ""),
                    tool_input=SessionEvent.encode_tool_input(get_field(block, "input")),
                    tokens_in=tokens_in,
                    tokens_out=tokens_out,
                )
            )
            tokens_in = tokens_out = 0  # 用量只算一次
        return None

    def _on_post_acting(self, agent: Any, kwargs: Dict[str, Any], output: Any) -> None:
        session_id = self._resolve_session(agent, kwargs)
        for block in _blocks_of(output, "tool_result"):
            self._safe_emit(
                SessionEvent(
                    session_id=session_id,
                    seq=0,
                    event_type=EVENT_TOOL_RESULT,
                    role=ROLE_TOOL,
                    tool_name=str(get_field(block, "name", "") or ""),
                    tool_output=_tool_result_text(block),
                )
            )
        return None

    def _on_post_observe(self, agent: Any, kwargs: Dict[str, Any], output: Any) -> None:
        session_id = self._resolve_session(agent, kwargs)
        for msg in _iter_msgs(kwargs.get("msg")):
            text = _text(msg)
            if not text:
                continue
            self._safe_emit(
                SessionEvent(
                    session_id=session_id,
                    seq=0,
                    event_type=EVENT_MESSAGE,
                    role=_role(msg),
                    content=text,
                )
            )
        return None

    # ─── Level 4：生效 Context（memory 是权威来源）───

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        effective = await self._read_memory(exclude_compressed=True)
        full = await self._read_memory(exclude_compressed=False)

        messages = [ContextMessage(role=_role(m), content=_text(m)) for m in effective]
        summary = await self._compressed_summary()
        if summary:
            messages.insert(0, ContextMessage(role="system", content=summary, is_compaction=True))

        compacted = len(full) > len(effective) or bool(summary)
        tokens_in = tokens_out = 0
        for m in full:
            ti, to = _usage(m)
            tokens_in += ti
            tokens_out += to

        return ContextSnapshot(
            session_id=session_id,
            system_prompt=str(get_field(self._agent, "sys_prompt", "") or ""),
            messages=messages,
            tools=self._tools(),
            is_compacted=compacted,
            compaction_summary=summary,
            original_message_count=len(full) if compacted else 0,
            total_tokens=tokens_in + tokens_out,
            framework=self.framework_name(),
        )

    async def _read_memory(self, *, exclude_compressed: bool) -> List[Any]:
        memory = get_field(self._agent, "memory")
        getter = getattr(memory, "get_memory", None) if memory is not None else None
        if not callable(getter):
            return []
        if exclude_compressed:
            # 压缩后的生效视图：被标记的历史消息不再进入 prompt。
            try:
                result = getter(exclude_mark=MARK_COMPRESSED)
                if inspect.isawaitable(result):
                    result = await result
                return list(result or [])
            except TypeError:
                pass  # 该 Memory 实现不支持 mark，退回全量。
            except Exception:
                return []
        try:
            result = getter()
            if inspect.isawaitable(result):
                result = await result
            return list(result or [])
        except Exception:
            return []

    async def _compressed_summary(self) -> str:
        """取压缩摘要（AgentScope 把摘要与原始消息分开存放）。"""
        memory = get_field(self._agent, "memory")
        if memory is None:
            return ""
        for name in ("get_compressed_summary", "compressed_summary"):
            attr = getattr(memory, name, None)
            if attr is None:
                continue
            try:
                value = attr() if callable(attr) else attr
                if inspect.isawaitable(value):
                    value = await value
            except Exception:
                continue
            if value:
                return _text(value) or str(value)
        return ""

    def _tools(self) -> List[ToolInfo]:
        toolkit = get_field(self._agent, "toolkit")
        getter = getattr(toolkit, "get_json_schemas", None) if toolkit is not None else None
        if not callable(getter):
            return []
        try:
            schemas = getter() or []
        except Exception:
            return []
        tools: List[ToolInfo] = []
        for schema in schemas:
            # OpenAI 风格：{"type": "function", "function": {...}}。
            fn = get_field(schema, "function", None) or schema
            name = str(get_field(fn, "name", "") or "")
            if not name:
                continue
            tools.append(
                ToolInfo(
                    name=name,
                    description=str(get_field(fn, "description", "") or ""),
                    parameters=get_field(fn, "parameters"),
                )
            )
        return tools

    # ─── Level 3：完整历史（含已压缩消息）───

    async def list_messages(
        self, session_id: str, *, offset: int = 0, limit: int = 50
    ) -> MessagePage:
        msgs = await self._read_memory(exclude_compressed=False)
        items: List[MessageItem] = []
        for idx, msg in enumerate(msgs, start=1):
            tool_blocks = _blocks_of(msg, "tool_use")
            result_blocks = _blocks_of(msg, "tool_result")
            items.append(
                MessageItem(
                    seq=idx,
                    role=_role(msg),
                    content=_text(msg),
                    tool_name=str(
                        get_field(tool_blocks[0] if tool_blocks else None, "name", "")
                        or get_field(result_blocks[0] if result_blocks else None, "name", "")
                        or ""
                    ),
                    tool_input=get_field(tool_blocks[0], "input") if tool_blocks else None,
                    tool_output=_tool_result_text(result_blocks[0]) if result_blocks else "",
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
        agent = self._agent
        if command == COMMAND_TERMINATE:
            await self._interrupt(agent)
            return
        if command == COMMAND_COMPRESS:
            # AgentScope 的压缩由 CompressionConfig 阈值驱动，没有公开的按需入口；
            # 这里尽力调用实现内部的压缩方法，不可用时如实报 unsupported。
            for name in ("_compress_memory", "_compress", "compress_memory"):
                fn = getattr(agent, name, None) if agent is not None else None
                if not callable(fn):
                    continue
                result = fn()
                if inspect.isawaitable(result):
                    await result
                return
            raise NotImplementedError(
                "agentscope: on-demand compression requires ReActAgent.CompressionConfig"
            )
        raise ValueError(f"unsupported command: {command!r}")

    async def abort(self, session_id: str) -> None:
        """Abort the in-flight turn via Agent.interrupt() without ending the session."""
        await self._interrupt(self._agent)
        self._busy_sessions.discard(session_id)

    async def _interrupt(self, agent: Any) -> None:
        interrupt = getattr(agent, "interrupt", None) if agent is not None else None
        if not callable(interrupt):
            raise NotImplementedError("agentscope: agent does not expose interrupt()")
        result = interrupt()
        if inspect.isawaitable(result):
            await result

    async def list_tasks(self, session_id: str) -> List[dict]:
        """Map AgentScope ``plan_notebook.current_plan`` subtasks → contract tasks."""
        notebook = get_field(self._agent, "plan_notebook")
        if notebook is None:
            return []
        plan = get_field(notebook, "current_plan")
        if plan is None and callable(getattr(notebook, "get_current_plan", None)):
            try:
                plan = notebook.get_current_plan()
                if inspect.isawaitable(plan):
                    plan = await plan
            except Exception:
                plan = None
        if plan is None:
            return []
        subtasks = (
            get_field(plan, "subtasks")
            or get_field(plan, "sub_tasks")
            or get_field(plan, "todos")
            or []
        )
        if not isinstance(subtasks, (list, tuple)):
            return []
        tasks: List[dict] = []
        for idx, item in enumerate(subtasks):
            task_id = str(
                get_field(item, "id", "")
                or get_field(item, "name", "")
                or f"{session_id or 'plan'}-{idx}"
            )
            subject = str(
                get_field(item, "name", "")
                or get_field(item, "subject", "")
                or get_field(item, "description", "")
                or task_id
            )
            state = _normalize_task_state(
                get_field(item, "state", "") or get_field(item, "status", "")
            )
            entry: dict = {"id": task_id, "subject": subject, "state": state, "owner": "main"}
            blocked = get_field(item, "blocked_by") or get_field(item, "blockedBy")
            if blocked:
                entry["blockedBy"] = str(blocked)
            tasks.append(entry)
        return tasks

    def session_fields(self, session_id: str) -> dict:
        """Frozen contract overlays: turn-level busy, model, context window."""
        out: dict = {"busy": session_id in self._busy_sessions}
        # Also treat phase-less "last session mid-flight" when id omitted queries use last.
        if not session_id and self._last_session_id:
            out["busy"] = self._last_session_id in self._busy_sessions
        model = self._model_name()
        if model:
            out["model"] = model
        max_tokens = self._context_window()
        if max_tokens > 0:
            out["maxTokens"] = max_tokens
        return out

    def _model_name(self) -> str:
        model = get_field(self._agent, "model")
        if model is None:
            return ""
        if isinstance(model, str):
            return model
        for key in ("model_name", "model", "name", "model_id"):
            value = get_field(model, key)
            if value:
                return str(value)
        return ""

    def _context_window(self) -> int:
        model = get_field(self._agent, "model")
        if model is None:
            return 0
        for key in ("context_window_size", "context_window", "max_tokens", "maxTokens"):
            value = get_field(model, key)
            if callable(value):
                try:
                    value = value()
                except Exception:
                    continue
            try:
                n = int(value or 0)
            except (TypeError, ValueError):
                continue
            if n > 0:
                return n
        # Some ChatModel wrappers nest config under generate_kwargs / config.
        for nest in ("generate_kwargs", "config", "options"):
            nested = get_field(model, nest)
            for key in ("max_tokens", "maxTokens", "context_window"):
                try:
                    n = int(get_field(nested, key) or 0)
                except (TypeError, ValueError):
                    n = 0
                if n > 0:
                    return n
        return 0


def _normalize_task_state(raw: Any) -> str:
    s = str(raw or "").strip().lower().replace("-", "_")
    mapping = {
        "todo": "pending",
        "pending": "pending",
        "in_progress": "in_progress",
        "inprogress": "in_progress",
        "doing": "in_progress",
        "done": "completed",
        "finished": "completed",
        "completed": "completed",
        "abandoned": "cancelled",
        "cancelled": "cancelled",
        "canceled": "cancelled",
        "blocked": "blocked",
    }
    return mapping.get(s, s or "pending")


def _iter_msgs(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return [m for m in value if m is not None]
    return [value]

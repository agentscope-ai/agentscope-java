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

"""``SessionBridge``：统一上报引擎（sdk-design §5.3）。

职责：适配器挂载、Level 2 事件缓冲（~5s 或满 20 条批量）、ContextTracker
增量视图、Level 1 聚合（~10s）、Level 4 推送（hash 变更防抖 30s 冷却；
compaction 后立即推）、Inventory（连接建立后立即一次，之后低频）、命令
分发（ASDP 与 HTTP 双通道 → ``handle_command``）、内嵌合约 HTTP 服务。

旁路原则：所有上报路径失败静默忽略，绝不阻断 Agent 主对话路径。
"""
from __future__ import annotations

import asyncio
import threading
import time
from typing import Any, Dict, List, Optional

from .adapters.base import COMMAND_ABORT, COMMAND_COMPRESS, COMMAND_TERMINATE, FrameworkAdapter
from .context import ContextSnapshot, ContextTracker
from .events import (
    EVENT_COMPACTION,
    EVENT_SESSION_END,
    EVENT_SESSION_START,
    MessagePage,
    SessionEvent,
)
from .inventory import InstanceHealth, Inventory
from .proto import asdp_pb2
from .transport.grpc import GrpcTransport
from .transport.http_server import ContractHTTPServer, ContractNotFoundError

#: SDK 版本（握手时上报）。
SDK_VERSION = "0.1.0"

# ─── 周期与批量参数（sdk-design §2.2 / §7）───
LEVEL1_INTERVAL = 10.0  # Level 1 聚合上报周期
EVENT_FLUSH_INTERVAL = 5.0  # Level 2 定时 flush
EVENT_BATCH_SIZE = 20  # Level 2 满批 flush
EVENT_BUFFER_MAX = 1000  # 本地降级有界队列，溢出丢最旧
CONTEXT_PUSH_COOLDOWN = 30.0  # Level 4 推送冷却（防抖）
INVENTORY_INTERVAL = 30.0  # Inventory 低频刷新
ASYNC_CALL_TIMEOUT = 10.0  # 适配器 async 方法调用超时

#: HTTP 合约等级（contract.md：1 发现 / 2 会话 / 3 命令；Level 3/4 查询经 capabilities 细粒度门控）。
CONTRACT_LEVEL = 3


class SessionBridge:
    """数据面桥：框架适配器 ↔ ASDP gRPC 推送 ↔ 内嵌 HTTP 合约服务。"""

    def __init__(
        self,
        *,
        control_plane: str,
        agent_name: str,
        namespace: str = "default",
        instance_id: str = "",
        enable_events: bool = False,
        contract_http_port: int = 8080,
        contract_http_host: str = "",
        session_affinity: str = "",
        start_http: bool = True,
        start_grpc: bool = True,
    ) -> None:
        self._control_plane = control_plane
        self._agent_name = agent_name
        self._namespace = namespace
        self._instance_id = instance_id
        self._enable_events = enable_events
        self._http_port = contract_http_port
        self._http_host = contract_http_host
        self._session_affinity = session_affinity
        self._start_http = start_http
        self._start_grpc = start_grpc

        self._adapter: Optional[FrameworkAdapter] = None
        self._target: Any = None

        self._lock = threading.RLock()
        self._trackers: Dict[str, ContextTracker] = {}
        self._phases: Dict[str, str] = {}
        self._seq: Dict[str, int] = {}
        self._event_buffer: List[SessionEvent] = []
        self._last_context_push: Dict[str, float] = {}

        self._stop = threading.Event()
        self._sched_thread: Optional[threading.Thread] = None

        # 适配器 async 方法在专用 event-loop 线程执行（HTTP handler 为同步）。
        self._loop = asyncio.new_event_loop()
        self._loop_thread = threading.Thread(
            target=self._loop.run_forever, name="aistio-loop", daemon=True
        )

        self._grpc: Optional[GrpcTransport] = None
        self._http: Optional[ContractHTTPServer] = None
        self._started = False

    # ─── 适配器挂载 ───

    def attach_target(self, target: Any, adapter: Optional[FrameworkAdapter] = None) -> None:
        """挂载框架对象；``adapter`` 缺省时经注册表自动识别。"""
        if adapter is None:
            from .adapters.registry import find_adapter

            adapter = find_adapter(target)
            if adapter is None:
                raise ValueError(f"unsupported framework: {type(target).__name__}")
        self._adapter = adapter
        self._target = target
        adapter.attach(target, self.on_event)

    # ─── 生命周期 ───

    def start(self) -> None:
        if self._started:
            return
        self._started = True
        self._loop_thread.start()

        if self._start_grpc:
            self._grpc = GrpcTransport(
                self._control_plane,
                agent_name=self._agent_name,
                namespace=self._namespace,
                instance_id=self._instance_id,
                sdk_version=SDK_VERSION,
                capabilities=self.capabilities(),
                session_affinity=self._session_affinity,
            )
            self._grpc.set_session_command_handler(self._on_session_command)
            self._grpc.start()

        if self._start_http:
            self._http = ContractHTTPServer(self._http_host, self._http_port, provider=self)
            self._http.start()

        self._sched_thread = threading.Thread(
            target=self._schedule_loop, name="aistio-sched", daemon=True
        )
        self._sched_thread.start()

    def stop(self) -> None:
        if not self._started:
            return
        self._stop.set()
        if self._grpc is not None:
            self._grpc.stop()
        if self._http is not None:
            self._http.stop()
        if self._adapter is not None:
            try:
                self._adapter.detach()
            except Exception:
                pass
        if self._sched_thread is not None:
            self._sched_thread.join(timeout=5.0)
            self._sched_thread = None
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._loop_thread.join(timeout=5.0)
        self._started = False

    def __enter__(self) -> "SessionBridge":
        self.start()
        return self

    def __exit__(self, *exc: Any) -> None:
        self.stop()

    @property
    def http_port(self) -> int:
        """实际绑定的合约 HTTP 端口（``contract_http_port=0`` 时有用）。"""
        return self._http.port if self._http is not None else self._http_port

    @property
    def grpc_transport(self) -> Optional[GrpcTransport]:
        return self._grpc

    # ─── capabilities ───

    def capabilities(self) -> List[str]:
        caps = {"session-reporting", "context-reporting"}
        if self._enable_events:
            caps.add("event-reporting")
        if self._adapter is not None:
            caps.update(self._adapter.capabilities())
        return sorted(caps)

    # ─── 事件入口（适配器 emit）───

    def on_event(self, event: SessionEvent) -> None:
        """适配器事件回调：seq 分配 → tracker 更新 → Level 2 缓冲 → 触发推送。"""
        if not event.session_id:
            return
        flush_needed = False
        compaction = False
        hash_changed = False
        with self._lock:
            seq = self._seq.get(event.session_id, 0) + 1
            self._seq[event.session_id] = seq
            event.seq = seq

            tracker = self._trackers.get(event.session_id)
            if tracker is None:
                tracker = ContextTracker(
                    event.session_id,
                    framework=self._adapter.framework_name() if self._adapter else "",
                )
                self._trackers[event.session_id] = tracker
            hash_changed = tracker.on_event(event)

            if event.event_type == EVENT_SESSION_START:
                self._phases[event.session_id] = "running"
            elif event.event_type == EVENT_SESSION_END:
                self._phases[event.session_id] = "completed"

            if self._enable_events:
                self._event_buffer.append(event)
                overflow = len(self._event_buffer) - EVENT_BUFFER_MAX
                if overflow > 0:
                    del self._event_buffer[:overflow]  # 溢出丢最旧（内存安全）
                flush_needed = len(self._event_buffer) >= EVENT_BATCH_SIZE

            compaction = event.event_type == EVENT_COMPACTION

        if flush_needed:
            self._flush_events()
        if compaction:
            # Compaction 完成后立刻推一次（sdk-design §3.4）。
            self._push_context(event.session_id, force=True)
        elif hash_changed:
            self._push_context(event.session_id, force=False)

    # ─── Level 2：事件流 ───

    def _flush_events(self) -> None:
        if self._grpc is None:
            return
        with self._lock:
            if not self._event_buffer:
                return
            batch = self._event_buffer
            self._event_buffer = []
        try:
            self._grpc.report_events([e.to_proto() for e in batch])
        except Exception:
            pass

    # ─── Level 4：Context 推送 ───

    def _push_context(self, session_id: str, *, force: bool) -> None:
        if self._grpc is None:
            return
        now = time.monotonic()
        last = self._last_context_push.get(session_id, 0.0)
        if not force and now - last < CONTEXT_PUSH_COOLDOWN:
            return
        with self._lock:
            tracker = self._trackers.get(session_id)
        if tracker is None:
            return
        self._last_context_push[session_id] = now
        try:
            self._grpc.report_context(tracker.snapshot().to_proto())
        except Exception:
            pass

    # ─── Level 1：摘要快照 ───

    def _build_level1(self) -> List["asdp_pb2.SessionSnapshot"]:
        framework = self._adapter.framework_name() if self._adapter else ""
        version = ""
        if self._adapter is not None:
            try:
                version = self._adapter.framework_version()
            except Exception:
                version = ""
        snapshots = []
        with self._lock:
            items = list(self._trackers.items())
            phases = dict(self._phases)
        for session_id, tracker in items:
            max_tokens = tracker.max_tokens
            total_tokens = tracker.tokens_in + tracker.tokens_out
            pressure = (total_tokens / max_tokens) if max_tokens > 0 else 0.0
            snapshots.append(
                asdp_pb2.SessionSnapshot(
                    session_id=session_id,
                    phase=phases.get(session_id, "running"),
                    message_count=tracker.message_count,
                    prompt_tokens=tracker.tokens_in,
                    completion_tokens=tracker.tokens_out,
                    context_pressure=pressure,
                    framework=framework,
                    framework_version=version,
                    context_hash=tracker.context_hash,
                    is_compacted=tracker.is_compacted,
                    effective_message_count=tracker.effective_message_count,
                )
            )
        return snapshots

    def _report_level1(self) -> None:
        if self._grpc is None:
            return
        try:
            self._grpc.report_sessions(self._build_level1())
        except Exception:
            pass

    # ─── Inventory ───

    def _report_inventory(self) -> None:
        if self._grpc is None or self._adapter is None:
            return
        try:
            inventory = self._collect_inventory()
            self._grpc.report_inventory(inventory.to_proto())
        except Exception:
            pass

    def _collect_inventory(self) -> Inventory:
        subagents = []
        workspaces = []
        if self._adapter is not None and self._adapter.supports("list_subagents"):
            try:
                subagents = self._run_async(self._adapter.list_subagents()) or []
            except Exception:
                subagents = []
        if self._adapter is not None and self._adapter.supports("workspace_info"):
            try:
                workspaces = self._run_async(self._adapter.workspace_info()) or []
            except Exception:
                workspaces = []
        with self._lock:
            active = sum(1 for p in self._phases.values() if p == "running")
        return Inventory(
            subagents=list(subagents),
            workspaces=list(workspaces),
            health=InstanceHealth(healthy=True, active_sessions=active),
        )

    # ─── 调度循环 ───

    def _schedule_loop(self) -> None:
        now = time.monotonic()
        next_events = now + EVENT_FLUSH_INTERVAL
        next_level1 = now + LEVEL1_INTERVAL
        next_inventory = now  # 连接建立后立即一次
        while not self._stop.wait(0.5):
            now = time.monotonic()
            if now >= next_events:
                next_events = now + EVENT_FLUSH_INTERVAL
                self._flush_events()
            if now >= next_level1:
                next_level1 = now + LEVEL1_INTERVAL
                self._report_level1()
            if now >= next_inventory:
                next_inventory = now + INVENTORY_INTERVAL
                self._report_inventory()

    # ─── 命令分发 ───

    def _on_session_command(self, session_id: str, command: str, params: bytes) -> None:
        self._dispatch_command(session_id, command, params or None)

    def _dispatch_command(
        self, session_id: str, command: str, params: Optional[bytes] = None
    ) -> None:
        if self._adapter is None:
            raise NotImplementedError("no framework adapter attached")
        if command == COMMAND_ABORT:
            if not self._adapter.supports("abort"):
                raise NotImplementedError(
                    f"{self._adapter.framework_name()} adapter does not support abort"
                )
            self._run_async(self._adapter.abort(session_id))
            return
        if not self._adapter.supports("handle_command"):
            raise NotImplementedError(
                f"{self._adapter.framework_name()} adapter does not support session commands"
            )
        self._run_async(self._adapter.handle_command(session_id, command, params))

    # ─── async 执行桥 ───

    def _run_async(self, coro: Any, timeout: float = ASYNC_CALL_TIMEOUT) -> Any:
        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        return future.result(timeout)

    def _session_overlay(self, session_id: str) -> dict:
        """Merge optional frozen fields from adapter (busy / model / maxTokens)."""
        if self._adapter is None or not self._adapter.supports("session_fields"):
            return {}
        try:
            overlay = self._adapter.session_fields(session_id) or {}
            return dict(overlay) if isinstance(overlay, dict) else {}
        except Exception:
            return {}

    def _apply_overlay(self, snap: dict, overlay: dict, tracker: Any) -> None:
        """Mutate ``snap`` with overlay + tracker.max_tokens (omit unknowns)."""
        if "busy" in overlay and overlay["busy"] is not None:
            snap["busy"] = bool(overlay["busy"])
        model = overlay.get("model")
        if model:
            snap["model"] = str(model)
        max_tokens = tracker.max_tokens
        overlay_max = overlay.get("maxTokens")
        if isinstance(overlay_max, (int, float)) and int(overlay_max) > 0:
            max_tokens = int(overlay_max)
            if tracker.max_tokens <= 0:
                tracker.max_tokens = max_tokens
        token_usage = snap.get("tokenUsage")
        if isinstance(token_usage, dict) and max_tokens > 0:
            token_usage["maxTokens"] = max_tokens

    # ─── HTTP 合约 provider（同步接口，供 ContractHTTPServer 调用）───

    def info(self) -> dict:
        framework = self._adapter.framework_name() if self._adapter else ""
        version = ""
        if self._adapter is not None:
            try:
                version = self._adapter.framework_version()
            except Exception:
                version = ""
        return {
            "name": self._agent_name,
            "runtime": framework,
            "version": version,
            "sdkVersion": SDK_VERSION,
            "contractLevel": CONTRACT_LEVEL,
            "capabilities": self.capabilities(),
            "port": self.http_port,
            "sessionAffinity": self._session_affinity or None,
        }

    def sessions(self) -> List[dict]:
        framework = self._adapter.framework_name() if self._adapter else ""
        version = ""
        if self._adapter is not None:
            try:
                version = self._adapter.framework_version()
            except Exception:
                version = ""
        result = []
        with self._lock:
            items = list(self._trackers.items())
            phases = dict(self._phases)
        for session_id, tracker in items:
            max_tokens = tracker.max_tokens
            total_tokens = tracker.tokens_in + tracker.tokens_out
            token_usage: dict = {
                "promptTokens": tracker.tokens_in,
                "completionTokens": tracker.tokens_out,
            }
            if max_tokens > 0:
                token_usage["maxTokens"] = max_tokens
            snap = {
                "id": session_id,
                "phase": phases.get(session_id, "running"),
                "messageCount": tracker.message_count,
                "tokenUsage": token_usage,
                "contextPressure": (total_tokens / max_tokens) if max_tokens > 0 else 0.0,
                "framework": framework,
                "frameworkVersion": version or None,
                "contextHash": tracker.context_hash,
                "isCompacted": tracker.is_compacted or None,
                "effectiveMessageCount": tracker.effective_message_count,
            }
            self._apply_overlay(snap, self._session_overlay(session_id), tracker)
            result.append(snap)
        return result

    def session_state(self, session_id: str) -> dict:
        with self._lock:
            tracker = self._trackers.get(session_id)
            phase = self._phases.get(session_id, "running")
        if tracker is None:
            raise ContractNotFoundError(f"session not found: {session_id}")
        total_tokens = tracker.tokens_in + tracker.tokens_out
        max_tokens = tracker.max_tokens
        pressure: dict = {
            "usedTokens": total_tokens,
            "ratio": (total_tokens / max_tokens) if max_tokens > 0 else 0.0,
        }
        if max_tokens > 0:
            pressure["maxTokens"] = max_tokens
        token_usage: dict = {
            "promptTokens": tracker.tokens_in,
            "completionTokens": tracker.tokens_out,
            "totalTokens": total_tokens,
        }
        if max_tokens > 0:
            token_usage["maxTokens"] = max_tokens
        framework = self._adapter.framework_name() if self._adapter else ""
        out: dict = {
            "sessionId": session_id,
            "id": session_id,
            "phase": phase,
            "messageCount": tracker.message_count,
            "tokenUsage": token_usage,
            "contextPressure": pressure,
            "framework": framework or None,
        }
        if tracker.is_compacted:
            out["isCompacted"] = True
        self._apply_overlay(out, self._session_overlay(session_id), tracker)
        # overlay may have updated tracker.max_tokens — refresh pressure/tokenUsage
        if tracker.max_tokens > 0:
            out["tokenUsage"]["maxTokens"] = tracker.max_tokens
            if isinstance(out["contextPressure"], dict):
                out["contextPressure"]["maxTokens"] = tracker.max_tokens
                out["contextPressure"]["ratio"] = total_tokens / tracker.max_tokens
        return out

    def context(self, session_id: str) -> dict:
        """Level 4 实时查询：优先适配器权威提取，回退 tracker 视图。"""
        snapshot: Optional[ContextSnapshot] = None
        if self._adapter is not None:
            try:
                snapshot = self._run_async(self._adapter.extract_context(session_id))
            except NotImplementedError:
                raise
            except Exception:
                snapshot = None
        if snapshot is None:
            with self._lock:
                tracker = self._trackers.get(session_id)
            if tracker is None:
                raise ContractNotFoundError(f"session not found: {session_id}")
            snapshot = tracker.snapshot()
        elif not snapshot.messages and session_id not in self._trackers:
            raise ContractNotFoundError(f"session not found: {session_id}")
        if not snapshot.context_hash:
            snapshot.refresh_hash()
        return snapshot.to_json_dict()

    def messages(self, session_id: str, offset: int, limit: int) -> dict:
        if self._adapter is None:
            raise NotImplementedError("no framework adapter attached")
        page: MessagePage = self._run_async(
            self._adapter.list_messages(session_id, offset=offset, limit=limit)
        )
        if page.total == 0 and session_id not in self._trackers:
            raise ContractNotFoundError(f"session not found: {session_id}")
        return page.to_json_dict()

    def subagents(self) -> List[dict]:
        if self._adapter is None or not self._adapter.supports("list_subagents"):
            raise NotImplementedError("subagent inventory not supported")
        items = self._run_async(self._adapter.list_subagents()) or []
        return [s.to_json_dict() for s in items]

    def workspaces(self) -> List[dict]:
        if self._adapter is None or not self._adapter.supports("workspace_info"):
            raise NotImplementedError("workspace inventory not supported")
        items = self._run_async(self._adapter.workspace_info()) or []
        return [w.to_json_dict() for w in items]

    def compress(self, session_id: str) -> None:
        self._dispatch_command(session_id, COMMAND_COMPRESS)

    def terminate(self, session_id: str) -> None:
        self._dispatch_command(session_id, COMMAND_TERMINATE)

    def abort(self, session_id: str) -> None:
        self._dispatch_command(session_id, COMMAND_ABORT)

    def tasks(self, session_id: str) -> List[dict]:
        if self._adapter is None or not self._adapter.supports("list_tasks"):
            raise NotImplementedError("task-query not supported")
        items = self._run_async(self._adapter.list_tasks(session_id)) or []
        return list(items)

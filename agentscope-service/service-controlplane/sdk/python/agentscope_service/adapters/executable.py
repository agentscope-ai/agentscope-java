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

"""Execute any asynchronous Agent framework through the fenced task contract."""
from __future__ import annotations

import asyncio
import inspect
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from .base import AgentTaskAssignment, FrameworkAdapter
from ..collaboration import CollaborationClient
from ..context import ContextSnapshot
from ..events import SessionEvent


@dataclass
class TaskResult:
    result: Any = None
    summary: str = ""
    usage: dict[str, Any] = field(default_factory=dict)
    processed_input_ids: list[str] = field(default_factory=list)
    outcome: str = "succeeded"
    complete_run: bool = False

    def __post_init__(self) -> None:
        if self.outcome not in {"succeeded", "waiting"}:
            raise ValueError("outcome must be succeeded or waiting; raise to fail execution")
        if self.complete_run and self.outcome == "waiting":
            raise ValueError("a waiting task cannot complete the coordinator run")


@dataclass
class TaskContext:
    assignment: AgentTaskAssignment
    context: dict[str, Any]
    client: CollaborationClient
    emit: Callable[[SessionEvent], None]
    session_id: str

    def event(self, event_type: str, **data: Any) -> None:
        """Publish portable item/tool events; use stable item_id/tool_call_id values."""
        self.emit(SessionEvent(
            session_id=self.session_id, seq=0, event_type="service.event",
            framework_meta=SessionEvent.encode_meta({
                "attemptId": self.assignment.attempt_id,
                "public_event": {"type": event_type, "payload": data},
            }),
        ))

    async def refresh(self) -> dict[str, Any]:
        """Read newly queued inputs at a framework-safe boundary."""
        self.context = await asyncio.to_thread(
            self.client.task_context, self.assignment.agent_task_id, self.assignment.task_token
        )
        return self.context


class ExecutableAdapter(FrameworkAdapter):
    """An opt-in execution adapter, separate from observational instrumentation.

    The runner must be async and cancellation-cooperative. Create a fresh framework
    Agent/session per assignment. Exceptions and cancellation are reported by the
    bridge only after the coroutine (including its cleanup) has finished.
    """

    def __init__(self, control_plane_http: str,
                 runner: Callable[[TaskContext], Awaitable[TaskResult | Any]],
                 *, framework: str = "custom") -> None:
        if not inspect.iscoroutinefunction(runner):
            raise TypeError("runner must be an async function")
        self._runner = runner
        self._framework = framework
        self._client = CollaborationClient(control_plane_http)
        self._emit: Callable[[SessionEvent], None] = lambda event: None

    def framework_name(self) -> str:
        return self._framework

    def can_handle(self, target: Any) -> bool:
        return target is self._runner

    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        self._emit = emit

    def detach(self) -> None:
        self._emit = lambda event: None

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        return ContextSnapshot(session_id=session_id, framework=self._framework)

    async def handle_agent_task(self, assignment: AgentTaskAssignment) -> TaskResult:
        # Coordination APIs require a durably running attempt. A queued ASDP
        # start report is not an acknowledgement; it may arrive after a fast
        # leader has already delegated work and completed with outcome=waiting.
        start = asyncio.create_task(asyncio.to_thread(
            self._client.call_tool, assignment.task_token, "task.start", {}
        ))
        try:
            await asyncio.shield(start)
        except asyncio.CancelledError:
            # A thread-backed HTTP request cannot be cancelled. Join it before
            # the bridge emits cancelled, and never enter the runner afterwards.
            try:
                await start
            finally:
                raise asyncio.CancelledError()
        envelope = await asyncio.to_thread(
            self._client.task_context, assignment.agent_task_id, assignment.task_token
        )
        ctx = TaskContext(assignment, envelope, self._client, self._emit, assignment.session_id)
        value = await self._runner(ctx)
        return value if isinstance(value, TaskResult) else TaskResult(result=value)

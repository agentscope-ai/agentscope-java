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

"""Opt-in execution adapters. Each assignment gets a fresh framework instance."""
from __future__ import annotations

import inspect
import json
from typing import Any, Callable

from .agentscope import AgentScopeAdapter
from .executable import ExecutableAdapter, TaskContext, TaskResult


def json_result(value: Any) -> Any:
    """Serialize common framework messages without exposing Python repr objects."""
    if hasattr(value, "model_dump"):
        value = value.model_dump(mode="json")
    elif hasattr(value, "to_dict"):
        value = value.to_dict()
    # Reject unsupported framework objects; use result_mapper for custom results.
    json.dumps(value)
    return value


def _result(ctx: TaskContext, value: Any, mapper: Callable[[Any], Any]) -> TaskResult:
    result = value if isinstance(value, TaskResult) else TaskResult(result=mapper(value))
    if not isinstance(value, TaskResult):
        result.processed_input_ids = [entry["input"]["id"] for entry in (ctx.context.get("inputs") or [])]
    text = result.result if isinstance(result.result, str) else json.dumps(result.result, ensure_ascii=False)
    ctx.event("item.completed", item_id="final", item={
        "role": "assistant", "content": [{"type": "text", "text": text}],
    })
    return result


class AsyncInvokeAdapter(ExecutableAdapter):
    """Execute async ``ainvoke`` implementations, including LangChain Runnables.

    Input mapping is explicit because a task context is not a framework prompt.
    The factory may be synchronous or asynchronous. Only async invocation is
    supported, so task cancellation waits for framework coroutine cleanup.
    """

    def __init__(self, control_plane_http: str, factory: Callable[[TaskContext], Any], *,
                 input_builder: Callable[[TaskContext], Any], framework: str = "async-invoke",
                 result_mapper: Callable[[Any], Any] = json_result) -> None:
        async def run(ctx: TaskContext) -> TaskResult:
            target = factory(ctx)
            if inspect.isawaitable(target):
                target = await target
            invoke = getattr(target, "ainvoke", None)
            if not callable(invoke):
                raise TypeError("factory must return an object exposing async ainvoke(input)")
            output = invoke(input_builder(ctx))
            if not inspect.isawaitable(output):
                raise TypeError("ainvoke must return an awaitable")
            return _result(ctx, await output, result_mapper)
        super().__init__(control_plane_http, run, framework=framework)


class AgentScopeRunnerAdapter(ExecutableAdapter):
    """Execute AgentScope's async Agent call and attach native observation hooks.

    ``input_builder`` should return an AgentScope Msg (or its accepted Msg list).
    Keeping that mapping explicit avoids assuming a model, toolkit or message
    schema. AgentScope is an optional application dependency, not a SDK dependency.
    Fresh agents are required per assignment to isolate concurrent task histories.
    """

    def __init__(self, control_plane_http: str, factory: Callable[[TaskContext], Any], *,
                 input_builder: Callable[[TaskContext], Any],
                 result_mapper: Callable[[Any], Any] = json_result) -> None:
        async def run(ctx: TaskContext) -> TaskResult:
            target = factory(ctx)
            if inspect.isawaitable(target):
                target = await target
            if not callable(target):
                raise TypeError("factory must return an async callable AgentScope agent")
            observer = AgentScopeAdapter(session_resolver=lambda agent, values: ctx.session_id)
            emission_errors = []
            def emit(event):
                try:
                    ctx.emit(event)
                except Exception as error:
                    # Observational hooks deliberately swallow errors. Execution
                    # must still fail if a required event could not be recorded.
                    emission_errors.append(error)
                    raise
            observer.attach(target, emit)
            try:
                output = target(input_builder(ctx))
                if not inspect.isawaitable(output):
                    raise TypeError("AgentScope agent call must return an awaitable")
                value = await output
                if emission_errors:
                    raise emission_errors[0]
                return _result(ctx, value, result_mapper)
            finally:
                observer.detach()
        super().__init__(control_plane_http, run, framework="agentscope")

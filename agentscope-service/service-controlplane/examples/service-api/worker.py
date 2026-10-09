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

"""A deterministic async Agent: multiple messages/tools, cancellation, durable events."""
import asyncio
import os
import json
import time
from pathlib import Path
import signal

from agentscope_service import AsyncInvokeAdapter, ExecutableAdapter, TaskContext, TaskResult, instrument


async def run(ctx: TaskContext) -> TaskResult:
    task = (ctx.context.get("task") or {})
    members = ((ctx.context.get("team") or {}).get("members") or [])
    inputs = [entry["input"]["id"] for entry in (ctx.context.get("inputs") or [])]
    if task.get("leaderTask") and not task.get("parentTaskId") and members:
        for member in members:
            await asyncio.to_thread(
                ctx.client.create_child_from_task, ctx.assignment.agent_task_id,
                ctx.assignment.task_token, {"title": "Check one source", "description": "Return a concise finding.",
                                            "assigneeType": "agent", "assigneeRef": member["agentId"]})
        # End this turn so maxConcurrency=1 does not block leader follow-ups.
        return TaskResult(summary="Delegated source checks to the Team members",
                          processed_input_ids=inputs, outcome="waiting")
    if task.get("leaderTask") and task.get("parentTaskId"):
        issue = ctx.context["issue"]
        current = next((child for child in (ctx.context.get("coordinatorChildren") or [])
                        if child["issue"]["id"] == issue["id"]), {})
        outcomes = (current.get("outcomes") or [])
        if not outcomes or any(item.get("status") in {"failed", "cancelled"} for item in outcomes):
            await asyncio.to_thread(ctx.client.fail_run_node, ctx.assignment.agent_task_id,
                                   ctx.assignment.task_token, "member_failed", "A member did not deliver its source check")
            return TaskResult(summary="Member source check failed")
        if not any(item.get("status") == "completed" and item.get("result") for item in outcomes):
            return TaskResult(summary="Waiting for usable source evidence", processed_input_ids=inputs, outcome="waiting")
        # The MCP token only permits accepting this follow-up's current child.
        if issue.get("status") != "done":
            await asyncio.to_thread(ctx.client.accept_current_issue, ctx.assignment.task_token,
                                   "The completed source finding contains the requested result")
        await ctx.refresh()
        children = (ctx.context.get("coordinatorChildren") or [])
        if any(child["issue"].get("status") != "done" for child in children):
            return TaskResult(summary="Accepted current finding; waiting for other members",
                              processed_input_ids=inputs, outcome="waiting")
        findings = [item["result"] for child in children for item in (child.get("outcomes") or [])
                    if item.get("status") == "completed" and item.get("result")]
        result = {"answer": "All requested source findings checked", "sources": len(findings), "findings": findings}
        ctx.event("item.completed", item_id="final", item={"role": "assistant",
                  "content": [{"type": "text", "text": json.dumps(result)}]})
        # The bridge first drains event ACKs, then completes the coordinator node.
        return TaskResult(result=result, summary=result["answer"], processed_input_ids=inputs, complete_run=True)
    for index in range(3):
        item, tool = f"message-{index}", f"lookup-{index}"
        ctx.event("item.started", item_id=item, item={"role": "assistant", "content": [], "status": "in_progress"})
        text = f"Checking source {index + 1}. "
        for word in text.split(" "):
            await asyncio.sleep(0.5)
            ctx.event("item.delta", item_id=item, content=[{"type": "text", "text": word + " "}])
        ctx.event("item.completed", item_id=item, item={"role": "assistant", "content": [{"type": "text", "text": text}]})
        ctx.event("tool.requested", tool_call_id=tool, name="lookup", input={"source": index + 1})
        ctx.event("tool.dispatched", tool_call_id=tool, name="lookup")
        await asyncio.sleep(2)
        ctx.event("tool.completed", tool_call_id=tool, output=[{"type": "text", "text": f"Source {index + 1} checked"}])
        await ctx.refresh()  # Observe new business inputs at a safe boundary.
    result = {"answer": "Three sources checked", "sources": 3}
    ctx.event("item.completed", item_id="final", item={"role": "assistant", "content": [{"type": "text", "text": result["answer"]}]})
    inputs = [entry["input"]["id"] for entry in (ctx.context.get("inputs") or [])]
    return TaskResult(result=result, summary=result["answer"], processed_input_ids=inputs)


class DemoRunnable:
    """No-model implementation of the same async protocol as a LangChain Runnable."""
    async def ainvoke(self, value):
        await asyncio.sleep(1)
        return {"answer": "Source checked", "sources": 1, "request": value}


if __name__ == "__main__":
    base = os.environ["AGENTSCOPE_BASE_URL"]
    key = os.environ.get("AGENTSCOPE_AGENT_KEY", "service-example")
    if os.environ.get("AGENTSCOPE_RUNNER") == "ainvoke":
        adapter = AsyncInvokeAdapter(base, lambda ctx: DemoRunnable(),
                                     input_builder=lambda ctx: ctx.context.get("currentRequest", "Check a source"))
    else:
        adapter = ExecutableAdapter(base, run, framework="service-example")
    bridge = instrument(run, adapter=adapter, transport=os.environ.get("AGENTSCOPE_TRANSPORT", "http"),
                        control_plane=os.environ.get("AGENTSCOPE_ASDP", ""), control_plane_http=base, agent_key=key,
                        tenant=os.environ.get("AGENTSCOPE_TENANT", "default"),
                        namespace=os.environ.get("AGENTSCOPE_NAMESPACE", "default"),
                        internal_token=os.environ.get("AGENTSCOPE_BOOTSTRAP_TOKEN", ""),
                        event_journal_dir=os.environ.get("AGENTSCOPE_JOURNAL_DIR", ""), start_http=False)
    try:
        deadline = time.monotonic() + 30
        while not bridge.registered_identity and time.monotonic() < deadline:
            time.sleep(0.2)
        identity = bridge.registered_identity
        if not identity:
            raise RuntimeError("Agent registration did not succeed within 30 seconds")
        if os.environ.get("AGENTSCOPE_IDENTITY_FILE"):
            # Only stable public IDs, no registration credential is exported.
            Path(os.environ["AGENTSCOPE_IDENTITY_FILE"]).write_text(json.dumps({
                "agentId": identity.agent_id, "bindingId": identity.binding_id,
                "kind": "external-application"}))
        print("Registered Agent:", identity.agent_id, flush=True)
        signal.pause()
    finally:
        bridge.stop()

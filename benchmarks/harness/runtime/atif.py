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
"""Export the observed text/tool projection, without inventing model calls or usage."""
from harbor.models.trajectories.trajectory import Trajectory


def export(events, agent, model, session_id):
    steps, calls = [], {}
    for event in events:
        kind = event["type"]
        text = "\n".join(block["text"] for block in event.get("content", []) if block["type"] == "text")
        if kind == "agent.tool_result":
            step = calls[event["tool_use_id"]]
            step["observation"] = {"results": [{"source_call_id": event["tool_use_id"], "content": text,
                "extra": {"event_id": event["id"], "processed_at": event["processed_at"],
                          **({"is_error": event["is_error"]} if "is_error" in event else {})}}]}
            continue
        if kind not in ("user.message", "agent.message", "agent.tool_use"):
            continue
        step = {"step_id": len(steps) + 1, "source": "user" if kind == "user.message" else "agent",
                "timestamp": event["processed_at"], "message": text, "extra": {"event_id": event["id"]}}
        if kind == "agent.tool_use":
            step["tool_calls"] = [{"tool_call_id": event["id"], "function_name": event["name"], "arguments": event["input"]}]
            calls[event["id"]] = step
        steps.append(step)
    return Trajectory.model_validate({"schema_version": "ATIF-v1.8", "session_id": session_id,
        "agent": {"name": agent["name"], "version": agent["version"], "model_name": model}, "steps": steps,
        "notes": "Derived text/tool projection. Native logs remain authoritative. No inferred reasoning, usage or model-call counts."}).to_json_dict()

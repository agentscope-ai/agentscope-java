# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Loss-aware native trajectory conversion. Raw evidence remains the source of truth."""
import hashlib
import json
from pathlib import Path

from runtime.protocol import EventProjection, ProtocolError


def native_paths(agent, folder):
    if agent == "agentscope":
        return list(folder.rglob("native*.jsonl"))
    if agent == "pi":
        return list(folder.glob("**/pi/sessions/**/*.jsonl")) + list(folder.glob("**/prior-sessions/pi-*/*.jsonl"))
    return list(folder.rglob("rollout-*.jsonl"))


def identity(source, key):
    return "sevt_" + hashlib.sha256((source + ":" + str(key)).encode()).hexdigest()[:32]


def text_blocks(value):
    if isinstance(value, str):
        return [{"type": "text", "text": value}]
    return [{"type": "text", "text": block.get("text", "")} for block in (value or [])
            if isinstance(block, dict) and block.get("type") in ("text", "input_text", "output_text")]


class NativeConverter:
    def __init__(self, source):
        self.source = source
        self.projection = EventProjection()
        self.mapping = []
        self.observations = []
        self.buffers = {}
        self.completed = False

    def emit(self, kind, key, timestamp, native_type, **fields):
        if not timestamp:
            raise ProtocolError("Native event has no source timestamp")
        event = {"id": identity(self.source, key), "type": kind, "processed_at": timestamp, **fields}
        self.projection.accept(event)
        self.mapping.append({"event_id": event["id"], "native_key": str(key), "native_type": native_type,
                             "timestamp_origin": "native"})

    def message(self, role, key, timestamp, content, native_type):
        if role == "user":
            self.completed = False
        blocks = text_blocks(content)
        if blocks:
            self.emit("user.message" if role == "user" else "agent.message", key, timestamp,
                      native_type, content=blocks)

    def tool(self, key, timestamp, name, arguments, native_type):
        self.completed = False
        if isinstance(arguments, str):
            try:
                arguments = json.loads(arguments)
            except ValueError:
                arguments = {"input": arguments}
        if not isinstance(arguments, dict):
            arguments = {"input": arguments}
        self.emit("agent.tool_use", "tool:" + key, timestamp, native_type, name=name, input=arguments)

    def result(self, key, record_id, timestamp, content, native_type, is_error=None):
        self.emit("agent.tool_result", record_id, timestamp, native_type,
                  tool_use_id=identity(self.source, "tool:" + key), content=text_blocks(content),
                  **({"is_error": is_error} if is_error is not None else {}))

    def idle(self, key, timestamp, native_type):
        self.emit("session.status_idle", key, timestamp, native_type, stop_reason={"type": "end_turn"})
        self.completed = True

    def java(self, item, position):
        kind, timestamp = item["type"], item.get("createdAt")
        key = item.get("id", "java:" + str(position))
        if kind == "eval.input":
            self.message("user", key, timestamp, item["content"], kind)
        elif kind == "AGENT_START":
            self.emit("session.status_running", key, timestamp, kind)
        elif kind == "TOOL_CALL_DELTA":
            target = ("tool", item["toolCallId"])
            self.buffers[target] = self.buffers.get(target, "") + item["delta"]
        elif kind == "TOOL_CALL_END":
            call = item["toolCallId"]
            self.tool(call, timestamp, item["toolCallName"], self.buffers.pop(("tool", call), "{}"), kind)
        elif kind == "TOOL_RESULT_TEXT_DELTA":
            target = ("result", item["toolCallId"])
            self.buffers[target] = self.buffers.get(target, "") + item["delta"]
        elif kind == "TOOL_RESULT_END":
            call = item["toolCallId"]
            self.result(call, key, timestamp, self.buffers.pop(("result", call), ""), kind,
                        is_error=item.get("state") not in (None, "success"))
        elif kind == "AGENT_RESULT":
            self.message("assistant", key, timestamp, item["result"].get("content"), kind)
            self.idle(key + ":idle", timestamp, kind)
        elif kind == "eval.failed":
            self.completed = False
            self.emit("session.error", key, timestamp, kind,
                      error={"type": "unknown_error", "message": item["error_type"],
                             "retry_status": {"type": "terminal"}})
        elif kind == "eval.model_start":
            self.observations.append({field: item[field] for field in ("type", "id", "createdAt")})
        elif kind in ("eval.model_end", "eval.model_error", "eval.binding", "eval.context"):
            self.observations.append(item)

    def pi(self, item, position):
        kind, timestamp = item.get("type"), item.get("timestamp")
        key = item.get("id", "pi:" + str(position))
        if kind == "session":
            self.emit("session.status_running", key, timestamp, kind)
        elif kind == "compaction":
            self.emit("agent.thread_context_compacted", key, timestamp, kind)
        elif kind == "message":
            message = item["message"]
            role = message.get("role")
            if role in ("user", "assistant"):
                self.message(role, key, timestamp, message.get("content"), kind)
            if role == "assistant":
                for block in message.get("content", []):
                    if block.get("type") == "toolCall":
                        self.tool(block["id"], timestamp, block["name"], block["arguments"], kind)
                if message.get("usage"):
                    self.observations.append({"native_key": key, "model": message.get("model"), "usage": message["usage"]})
                if message.get("stopReason") == "stop":
                    self.idle(key + ":idle", timestamp, kind)
                elif message.get("stopReason") in ("error", "aborted"):
                    self.completed = False
                    self.emit("session.error", key + ":error", timestamp, kind,
                              error={"type": "unknown_error", "message": "Native Pi " + message["stopReason"],
                                     "retry_status": {"type": "terminal"}})
            elif role == "toolResult":
                self.result(message["toolCallId"], key, timestamp, message.get("content"), kind, message.get("isError", False))

    def codex(self, item, position):
        kind, timestamp, payload = item.get("type"), item.get("timestamp"), item.get("payload", {})
        key = "codex:" + str(position)
        native = kind + ":" + str(payload.get("type", ""))
        if kind == "event_msg":
            event_type = payload.get("type")
            if event_type == "task_started":
                self.emit("session.status_running", key, timestamp, native)
            elif event_type in ("task_complete", "task_completed"):
                error = payload.get("error")
                if error:
                    self.completed = False
                    self.emit("session.error", key, timestamp, native,
                              error={"type": "unknown_error",
                                     "message": str(error.get("message", error) if isinstance(error, dict) else error),
                                     "retry_status": {"type": "terminal"}})
                else:
                    self.idle(key, timestamp, native)
            elif event_type == "token_count":
                self.observations.append({"native_key": key, "usage": payload.get("info"), "semantics": "native_cumulative_and_last"})
            elif event_type in ("turn_aborted", "error"):
                self.completed = False
                self.emit("session.error", key, timestamp, native,
                          error={"type": "unknown_error", "message": "Native Codex " + event_type,
                                 "retry_status": {"type": "terminal"}})
        elif kind == "compacted":
            self.emit("agent.thread_context_compacted", key, timestamp, native)
        elif kind == "response_item":
            item_type = payload.get("type")
            if item_type == "message" and payload.get("role") in ("user", "assistant"):
                self.message(payload["role"], key, timestamp, payload.get("content"), native)
            elif item_type in ("function_call", "custom_tool_call"):
                self.tool(payload["call_id"], timestamp, payload["name"],
                          payload.get("arguments", payload.get("input", {})), native)
            elif item_type in ("function_call_output", "custom_tool_call_output"):
                self.result(payload["call_id"], key, timestamp, payload.get("output"), native)


def convert(agent, paths, output):
    """Convert one native session; callers must not merge independent thread histories."""
    if len(paths) != 1:
        raise ProtocolError("Expected exactly one native session; child sessions require separate conversion")
    path = Path(paths[0])
    converter = NativeConverter(agent + ":" + path.name)
    handler = getattr(converter, "java" if agent == "agentscope" else agent)
    for position, raw in enumerate(path.read_bytes().split(b"\n")):
        if raw.strip():
            handler(json.loads(raw), position)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "events.jsonl").write_text("".join(json.dumps(event, ensure_ascii=False) + "\n"
                                                for event in converter.projection.events))
    binding = {"profile": "managed-agents-text-v1", "native_source": path.name,
               "mapping": converter.mapping, "native_completed": converter.completed,
               "observability_gaps": ["model spans omitted unless all required usage fields are observable",
                                      "native reasoning text stays outside standard events"],
               "summary": converter.projection.summary()}
    (output / "binding.json").write_text(json.dumps(binding, indent=2) + "\n")
    (output / "observations.json").write_text(json.dumps(converter.observations, indent=2) + "\n")
    return binding


def convert_sessions(agent, paths, output):
    """Keep each native session separate; the root file is an indexed report collection."""
    if len(paths)==1:
        return convert(agent,paths,output)
    if not paths:raise ProtocolError('No native session was archived')
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    combined=[];observations=[];sessions=[];counts={}
    for index,path in enumerate(sorted(paths,key=lambda p:str(p))):
        target=output/'sessions'/str(index)
        binding=convert(agent,[path],target)
        events=[json.loads(line) for line in (target/'events.jsonl').read_text().splitlines()]
        combined.extend(events)
        observations.extend(json.loads((target/'observations.json').read_text()))
        sessions.append({'path':str(path.name),'events':'sessions/'+str(index)+'/events.jsonl',
                         'event_ids':[e['id'] for e in events],'native_completed':binding['native_completed']})
        for key,value in binding['summary']['event_counts'].items():counts[key]=counts.get(key,0)+value
    combined.sort(key=lambda e:e['processed_at'])
    if len({e['id'] for e in combined})!=len(combined):raise ProtocolError('Duplicate IDs across native sessions')
    (output/'events.jsonl').write_text(''.join(json.dumps(e,ensure_ascii=False)+'\n' for e in combined))
    (output/'observations.json').write_text(json.dumps(observations,indent=2))
    result={'profile':'managed-agents-text-v1','collection':True,'sessions':sessions,
            'native_completed':all(s['native_completed'] for s in sessions),'summary':{'event_counts':counts}}
    (output/'binding.json').write_text(json.dumps(result,indent=2))
    return result

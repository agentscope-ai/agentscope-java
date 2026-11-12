#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Tool source acceptance with real infrastructure and explicit local Sandbox/model fixtures."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import time
import traceback
import urllib.parse
import uuid

import replicas


def all_events(base):
    values, cursor = [], None
    while True:
        query = {"limit": 100}
        if cursor:
            query["after"] = cursor
        page = replicas.request(replicas.PORTS["portal-b"], base + "/events?" + urllib.parse.urlencode(query))
        values.extend(page["data"])
        if not page["has_more"]:
            return values
        cursor = page["next_cursor"]


def exited(pid):
    result = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True)
    return result.returncode != 0 or result.stdout.strip().startswith("Z")


def kill(state, names):
    manifest = json.loads((state / "manifest.json").read_text())
    pids = {name: manifest["processes"][name]["pid"] for name in names}
    for name in names:
        replicas.signal_replica(state, name, signal.SIGKILL)
    deadline = time.monotonic() + 15
    while not all(exited(pid) for pid in pids.values()):
        if time.monotonic() >= deadline:
            raise AssertionError(f"Owned processes still alive: {pids}")
        time.sleep(.1)
    return pids


def continue_observation(state, source, timeout):
    source = source.resolve()
    if source.parent != state or not source.name.startswith("tool-stream-"):
        raise ValueError("Continue only an existing record in this owned fixture")
    record = json.loads(source.read_text())
    if record["passed"] or not all(key in record for key in
            ("while_brain_absent", "sources", "captured_output", "public_before_resume", "new_brain_pids")):
        raise ValueError("Continue only after the original source and HarnessRuntime-restart checks")
    output = state / f"tool-stream-continuation-{time.time_ns()}.json"
    record.update(continued_from=str(source), previous_error=record.pop("error", None),
                  completion_observation_seconds=timeout)
    output.write_text(json.dumps(record, indent=2) + "\n")
    try:
        base = "/v1/sessions/" + record["session"]
        deadline = time.monotonic() + timeout
        while True:
            turns = [replicas.request(replicas.PORTS["portal-b"], base + "/turns/" + turn)
                     for turn in record["turns"]]
            if all(turn["status"] == "completed" for turn in turns):
                break
            if any(turn["status"] in {"failed", "cancelled"} for turn in turns):
                raise AssertionError(turns)
            if time.monotonic() >= deadline:
                raise AssertionError(f"Existing Turns have not completed: {turns}")
            time.sleep(.5)
        # Re-run the original native checkpoint, result, FIFO and side-effect assertions.
        replicas.verify_smoke(state, output)
        record = json.loads(output.read_text())
        completions = [event for event in all_events(base)
                       if event["type"] == "tool.completed"
                       and event["data"].get("tool_call_id") == "replica-execute-" + record["label"]]
        assert len(completions) == 1 and completions[0]["data"]["status"] == "completed", completions
        record.update(passed=True, model_tool_completions=1)
        print(f"PASSED continued observation without resubmitting work: {output}", flush=True)
    except BaseException:
        record.update(passed=False, error=traceback.format_exc())
        raise
    finally:
        output.write_text(json.dumps(record, indent=2) + "\n")


def run(state, seconds, replace_tool_worker, completion_timeout=600):
    if seconds < 90 or seconds > 240:
        raise ValueError("Stream acceptance duration must be 90..240 seconds")
    manifest = json.loads((state / "manifest.json").read_text())
    record = {"passed": False, "model": "explicit deterministic fixture", "sandbox": "real local subprocess, not ACS",
              "infrastructure": "real RocketMQ LitePush / Redis / PostgreSQL", "seconds": seconds,
              "mq_profile": manifest.get("mq_profile", "official"),
              "mq_test_only_derivative": manifest.get("mq_test_only_derivative", False),
              "completion_observation_seconds": completion_timeout}
    output = state / f"tool-stream-{time.time_ns()}.json"

    def save():
        output.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n")

    save()
    try:
        environment, agent, identity = replicas.fixture_agent()
        session = replicas.request(replicas.PORTS["portal-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)["id"]
        base = f"/v1/sessions/{session}"
        label = "first-" + identity
        first = replicas.request(replicas.PORTS["portal-a"], base + "/turns",
                                 {"input": {"message": f"fixture:stream:{seconds}:{label}"}}, uuid.uuid4().hex)
        second = replicas.request(replicas.PORTS["portal-b"], base + "/turns",
                                  {"input": {"message": "fixture:echo:second-" + identity}}, uuid.uuid4().hex)
        record.update(session=session, turns=[first["id"], second["id"]], label=label, environment=environment["id"], agent=agent["id"])
        save()

        def facts():
            return json.loads(replicas.fact(state, """
                SELECT coalesce(json_agg(json_build_object('task',t.task_id,'execution',t.execution_id,
                    'handle',t.process_handle,'status',t.status,'result',t.result,
                    'source_seq',o.source_sequence,'consumed_seq',o.consumed_sequence,
                    'chunk_seq',o.chunk_sequence,'offset',o.output_offset,'final_seq',o.final_sequence,
                    'mode',o.mode,'has_gap',o.has_gap)),'[]'::json)
                FROM ma.tool_task t JOIN ma.tool_stream o USING(scope_key,task_id)
                WHERE t.turn_id=:'turn' AND t.native_tool_call_id=:'call';
                """, {"turn": first["id"], "call": "replica-execute-" + label}))

        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            before = facts()
            public = all_events(base)
            waiting = replicas.request(replicas.PORTS["portal-a"], base + "/turns/" + first["id"])
            if (len(before) == 1 and before[0]["status"] == "running" and before[0]["offset"] > 0
                    and any(event["type"] == "tool.delta" for event in public) and waiting["status"] == "waiting_tool"):
                break
            if waiting["status"] in {"failed", "completed", "cancelled"}:
                raise AssertionError(f"Turn finished before failure injection: {waiting}")
            time.sleep(.25)
        else:
            raise AssertionError("Initial source delta was not durably visible during suspension")
        record.update(before=before, harness_runtime_pids=kill(state,["harness-runtime-a","harness-runtime-b"]))
        save()
        if replace_tool_worker:
            record["old_hands_pids"] = kill(state,["tool-worker-a","tool-worker-b"])
            record["new_hands_pids"] = {name: replicas.restart_replica(state,name) for name in ["tool-worker-a","tool-worker-b"]}
            save()
        deadline = time.monotonic() + seconds + 100
        while time.monotonic() < deadline:
            after = facts()
            if len(after) == 1 and after[0]["status"] == "succeeded":
                break
            time.sleep(.5)
        else:
            raise AssertionError(f"Tool Worker did not finish the original tool while HarnessRuntime was absent: {after}")
        assert all(exited(pid) for pid in record["brain_pids"].values()), record
        assert all(before[0][key] == after[0][key] for key in ("task","execution","handle")), (before,after)
        assert after[0]["mode"] == "replayable" and not after[0]["has_gap"], after
        assert after[0]["consumed_seq"] == after[0]["final_seq"] and after[0]["chunk_seq"] >= 2, after
        public = all_events(base)
        tool_events = [event for event in public if event["type"] == "tool.delta" and event["data"].get("task_id") == after[0]["task"]]
        text = "".join(block["text"] for event in tool_events for block in event["data"]["output"])
        assert text == f"begin-{label} 世界\nmiddle-{label}\ntool-worker-{label}\n", text
        premature = [event["id"] for event in public if event["type"] == "tool.completed" and event["data"].get("tool_call_id") == "replica-execute-" + label]
        assert not premature, {"premature_model_tool_completions": premature}
        assert replicas.request(replicas.PORTS["portal-a"],base + "/turns/" + first["id"])["status"] == "waiting_tool"
        sources = json.loads(replicas.fact(state, """
            SELECT json_agg(json_build_object('sequence',source_sequence,'hash',content_hash,'payload',payload,
              'received',received_at,'applied',applied_at) ORDER BY source_sequence)
            FROM ma.tool_event WHERE task_id=:'task';
            """, {"task": after[0]["task"]}))
        captured, chunks, offset = bytearray(), 0, 0
        for index, source in enumerate(sources, 1):
            assert source["sequence"] == index and source["received"] and source["applied"], source
            # Java fingerprint counts UTF-16 code units, then hashes length:payload as UTF-8.
            payload = source["payload"]
            units = len(payload.encode("utf-16-le")) // 2
            assert hashlib.sha256((str(units) + ":" + payload).encode()).hexdigest() == source["hash"], source
            event = json.loads(payload)
            if event["type"] == "tool.delta":
                chunks += 1
                data = event["data"]
                raw = base64.b64decode(data["data"],validate=True)
                assert data["chunk_seq"] == chunks and data["from_offset"] == offset and data["to_offset"] == offset + len(raw), data
                captured.extend(raw)
                offset += len(raw)
            if event["type"] == "tool.result":
                assert event["data"]["last_chunk_seq"] == chunks and event["data"]["output_offset"] == offset, event
        assert captured.decode() == text and len(captured) == after[0]["offset"], (captured,text)
        record.update(while_harness_runtime_absent=after, sources=sources, captured_output=text, public_before_resume=public)
        record["new_brain_pids"] = {name: replicas.restart_replica(state,name) for name in ["harness-runtime-a","harness-runtime-b"]}
        save()
        # Workspace / memory finalization can suspend through Tool Worker as well. This is a bounded
        # observation budget, independent of the business tool's requested duration.
        deadline = time.monotonic() + completion_timeout
        while time.monotonic() < deadline:
            turns = [replicas.request(replicas.PORTS["portal-b"],base + "/turns/" + item["id"]) for item in (first,second)]
            if all(turn["status"] == "completed" for turn in turns):
                break
            if any(turn["status"] in {"failed","cancelled"} for turn in turns):
                raise AssertionError(turns)
            time.sleep(.5)
        else:
            raise AssertionError(f"Turns did not complete after HarnessRuntime restart: {turns}")
        record["seconds"] = seconds
        save()
        replicas.verify_smoke(state,output)
        record = json.loads(output.read_text())
        completed = [event for event in all_events(base) if event["type"] == "tool.completed" and event["data"].get("tool_call_id") == "replica-execute-" + label]
        assert len(completed) == 1, completed
        record.update(passed=True, model_tool_completions=1)
        save()
        print(f"PASSED tool source while both HarnessRuntime processes were absent: {output}",flush=True)
    except BaseException:
        record.update(passed=False,error=traceback.format_exc())
        save()
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--state",type=Path,required=True)
    parser.add_argument("--seconds",type=int,default=180)
    parser.add_argument("--replace-tool-workers",action="store_true")
    parser.add_argument("--continue-record", type=Path,
                        help="Observe the original requests without restarting or resubmitting them")
    parser.add_argument("--completion-timeout", type=int, default=600)
    arguments = parser.parse_args()
    if not 1 <= arguments.completion_timeout <= 900:
        parser.error("--completion-timeout must be 1..900 seconds")
    if arguments.continue_record:
        continue_observation(arguments.state.resolve(), arguments.continue_record,
                             arguments.completion_timeout)
    else:
        run(arguments.state.resolve(), arguments.seconds, arguments.replace_tool_workers,
            arguments.completion_timeout)

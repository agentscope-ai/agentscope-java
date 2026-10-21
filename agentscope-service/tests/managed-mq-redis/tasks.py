#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Actual AgentTask Outbox -> Harness/MCP -> long tool -> same-Turn Resume acceptance."""
import argparse
import json
from pathlib import Path
import time
import urllib.request

import replicas


def control(path, body=None, internal=False, method=None):
    headers = {"Content-Type": "application/json"}
    if internal:
        headers["X-Builder-Internal-Token"] = "managed-replica-fixture-internal-token-2026"
    request = urllib.request.Request("http://127.0.0.1:19081" + path,
        None if body is None else json.dumps(body).encode(), headers, method=method)
    with urllib.request.urlopen(request, timeout=40) as response:
        return json.load(response)


def run(state, seconds):
    manifest = json.loads((state / "manifest.json").read_text())
    assert manifest.get("task_workers"), "Start a new owned fixture with --task-workers"
    environment, agent, identity = replicas.fixture_agent(max_iters=8)
    label = "task-" + identity
    issue = control("/api/v1/issues", {"tenant": "default", "namespace": "default",
        "title": f"fixture:task:{seconds}:{label}", "description": "Read task context, execute the fixture tool, and deliver its confirmed result.",
        "assigneeType": "agent", "assigneeRef": agent["id"]})
    task = issue["agentTask"]
    record = {"passed": False, "issue": issue["issue"]["id"], "task": task["id"],
        "agent": agent["id"], "environment": environment["id"], "label": label,
        "model": "explicit deterministic fixture", "sandbox": "actual local subprocess"}
    output = state / f"task-pipeline-{int(time.time())}.json"
    output.write_text(json.dumps(record, indent=2) + "\n")
    deadline = time.monotonic() + seconds + 180
    duplicate = None
    previous = None
    attempt = None
    while time.monotonic() < deadline:
        task = control("/api/v1/agent-tasks/" + task["id"])["task"]
        if task["status"] != previous:
            print("TASK", task["status"], flush=True)
            previous = task["status"]
        attempt_id = task.get("currentAttemptId")
        if attempt_id:
            attempt = control("/api/v1/execution-attempts/" + attempt_id)["attempt"]
            if attempt.get("turnId") and task["status"] == "running" and duplicate is None:
                duplicate = control("/api/internal/managed-attempts/" + attempt_id + "/admit", {}, internal=True)
                assert duplicate["turnId"] == attempt["turnId"], duplicate
        if task["status"] in {"completed", "failed", "cancelled"}:
            break
        time.sleep(.5)
    record.update(task_result=task, attempt=attempt, duplicate=duplicate)
    output.write_text(json.dumps(record, indent=2) + "\n")
    assert task["status"] == "completed", (task, output)
    assert attempt and attempt["state"] == "succeeded", attempt
    assert "brain-confirmed-hands-" + label in json.dumps(task.get("result")), task
    assert attempt.get("leaseExpiresAt") is None and attempt.get("heartbeatAt") is None and not attempt.get("leaseToken"), attempt
    session, turn = attempt["sessionId"], attempt["turnId"]
    base = "/v1/sessions/" + session
    native = replicas.wait_turn(base, turn, {"completed"})
    events = replicas.request(19184, base + "/events?limit=100")
    assert not events["has_more"], events
    encoded = json.dumps(events)
    assert "brain-task-complete-" + label in encoded, events
    assert "taskToken" not in encoded and "X-Agent-Task-Token" not in encoded, "private credential exposed in public events"
    facts = json.loads(replicas.fact(state, """SELECT json_build_object(
        'turns', (SELECT count(*) FROM ma.turn t JOIN ma.session s ON s.scope_key=t.scope_key WHERE s.session_id=:'session'),
        'suspensions', (SELECT count(*) FROM ma.turn_suspension WHERE turn_id=:'turn'),
        'resumes', (SELECT count(*) FROM ma.turn_resume WHERE turn_id=:'turn'),
        'attempts', (SELECT count(*) FROM rt.execution_attempts WHERE agent_task_id=:'task'),
        'legacy_sessions', (SELECT count(*) FROM cp.sessions WHERE session_id=:'session'),
        'legacy_mirror_tables', (SELECT count(*) FROM pg_tables WHERE schemaname='dp' AND tablename IN ('builder_session_event_outbox','builder_session_execution_scope','builder_session_event_fence')),
        'legacy_control_markers', (SELECT count(*) FROM information_schema.columns WHERE table_schema='cp' AND table_name='sessions' AND column_name IN ('runtime_agent_task_id','runtime_attempt_id','runtime_dispatch_generation','runtime_turn_id')),
        'result_comments', (SELECT count(*) FROM rt.comments WHERE source_task_id=:'task' AND type='result'))""",
        {"session": session, "turn": turn, "task": task["id"]}))
    record.update(native_turn=native, facts=facts)
    output.write_text(json.dumps(record, indent=2) + "\n")
    assert facts["turns"] == 1 and facts["attempts"] == 1 and facts["legacy_sessions"] == 0, facts
    assert facts["legacy_mirror_tables"] == 0 and facts["legacy_control_markers"] == 0, facts
    assert facts["result_comments"] == 1, facts
    assert facts["suspensions"] >= 1 and facts["resumes"] >= 1, facts
    actor = json.loads(replicas.fact(state, "SELECT actor FROM ma.turn WHERE turn_id=:'turn'", {"turn": turn}))
    assert actor["user"] == "local-developer" and actor["internal"] is False, actor
    effects = [p for p in (state / "sandbox").glob("*/side-effects.txt") if label in p.read_text()]
    assert len(effects) == 1 and effects[0].read_text().splitlines() == ["start-" + label, "done-" + label], effects
    record.update(passed=True, native_turn=native, events=events, facts=facts, actor=actor, side_effect=str(effects[0]))
    if manifest.get("mcp_reply_retry"):
        retries = json.loads((state / "mcp-reply-retries.json").read_text())
        matching = [r for r in retries if r["task"] == task["id"]]
        assert len(matching) == 1 and matching[0]["first_committed"] and matching[0]["replayed"] and matching[0]["second_status"] == 200, matching
        record["lost_reply_retry"] = matching[0]
    output.write_text(json.dumps(record, indent=2) + "\n")
    print("PASS actual AgentTask Outbox, MCP get/complete, one sandbox execution, suspension/resume and original Actor", flush=True)
    print(output, flush=True)


def cancellation(state, timeout=False):
    _, agent, identity = replicas.fixture_agent(max_iters=8)
    label = ("timeout-task-" if timeout else "cancel-task-") + identity
    if timeout:
        path = "/api/v1/agent-runtime-policies/" + agent["id"]
        policy = control(path + "?tenant=default&namespace=default")["policy"]
        policy["attemptTimeoutSeconds"] = 30
        control(path, policy, method="PUT")
    created = control("/api/v1/issues", {"tenant": "default", "namespace": "default",
        "title": "fixture:task:60:" + label, "assigneeType": "agent", "assigneeRef": agent["id"]})
    task = created["agentTask"]
    output = state / f"task-{'timeout' if timeout else 'cancellation'}-{int(time.time())}.json"
    record = {"passed": False, "task": task["id"], "label": label}
    output.write_text(json.dumps(record, indent=2) + "\n")
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        task = control("/api/v1/agent-tasks/" + task["id"])["task"]
        if task.get("currentAttemptId"):
            attempt = control("/api/v1/execution-attempts/" + task["currentAttemptId"])["attempt"]
            if attempt.get("turnId"):
                base = "/v1/sessions/" + attempt["sessionId"]
                turn = replicas.request(19084, base + "/turns/" + attempt["turnId"])
                if turn["status"] == "waiting_tool":
                    break
        assert task["status"] not in {"failed", "completed", "cancelled"}, task
        time.sleep(.5)
    else:
        raise AssertionError("Task did not reach a persisted long-tool suspension")
    receipt = None
    if not timeout:
        receipt = control("/api/v1/agent-tasks/" + task["id"] + "/cancel", {"expectedVersion": task["version"]})
        assert receipt["task"]["status"] == "cancelled", receipt
    pending = control("/api/v1/execution-attempts/" + attempt["id"])["attempt"]
    assert pending["state"] in ({"running", "cancel_requested"} if timeout else {"cancel_requested", "cancelled"}), pending
    native = replicas.wait_turn(base, attempt["turnId"], {"cancelled"})
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        final = control("/api/v1/execution-attempts/" + attempt["id"])["attempt"]
        if final["state"] == ("failed" if timeout else "cancelled"):
            break
        time.sleep(.5)
    assert final["state"] == ("failed" if timeout else "cancelled"), final
    task = control("/api/v1/agent-tasks/" + task["id"])["task"]
    if timeout:
        task = control("/api/v1/agent-tasks/" + task["id"])["task"]
        assert task["status"] == "failed" and task["errorCode"] == "attempt_timeout" and final["failureCode"] == "attempt_timeout", (task, final)
    effects = [p for p in (state / "sandbox").glob("*/side-effects.txt") if label in p.read_text()]
    assert len(effects) == 1 and effects[0].read_text().splitlines() == ["start-" + label], effects
    inputs = replicas.request(19184, base + "/turns/" + attempt["turnId"] + "/inputs?limit=100")
    cancelled = [i for i in inputs["data"] if i["input_type"] == "cancel"]
    assert len(cancelled) == 1 and cancelled[0]["status"] == "applied", cancelled
    facts = json.loads(replicas.fact(state, """SELECT json_build_object(
        'attempts', (SELECT count(*) FROM rt.execution_attempts WHERE agent_task_id=:'task'),
        'attempt_after_native_terminal', (SELECT extract(epoch FROM completed_at)*1000 >=
            (SELECT max(created_at) FROM ma.public_event WHERE type='turn.cancelled' AND payload::jsonb->>'turn_id'=:'turn')
            FROM rt.execution_attempts WHERE id=:'attempt'),
        'task_after_native_terminal', (SELECT extract(epoch FROM completed_at)*1000 >=
            (SELECT max(created_at) FROM ma.public_event WHERE type='turn.cancelled' AND payload::jsonb->>'turn_id'=:'turn')
            FROM rt.agent_tasks WHERE id=:'task'))""",
        {"task": task["id"], "turn": attempt["turnId"], "attempt": attempt["id"]}))
    assert facts["attempts"] == 1 and facts["attempt_after_native_terminal"], facts
    if timeout:
        assert facts["task_after_native_terminal"], facts
    record.update(passed=True, session=attempt["sessionId"], turn=native, receipt=receipt,
        initially_observed=pending, final_attempt=final, final_task=task, facts=facts, cancel_inputs=cancelled, side_effect=str(effects[0]))
    output.write_text(json.dumps(record, indent=2) + "\n")
    print("PASS actual task " + ("business timeout" if timeout else "cancellation") + ", native cancel Input, stopped subprocess and terminal proof", flush=True)
    print(output, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=8)
    parser.add_argument("--cancel", action="store_true")
    parser.add_argument("--timeout", action="store_true")
    args = parser.parse_args()
    if not 6 <= args.seconds <= 240:
        parser.error("--seconds must be between 6 and 240")
    if args.cancel or args.timeout:
        cancellation(args.state, args.timeout)
    else:
        run(args.state, args.seconds)

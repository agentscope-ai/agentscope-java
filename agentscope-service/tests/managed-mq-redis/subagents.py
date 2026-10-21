#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Native parent/child acceptance on the owned real-MQ replica fixture.

The model is the explicit ManagedReplicaModel test fixture and Hands launches
real local subprocesses. This never claims ACS or external-model validation.
"""
import argparse
import json
from pathlib import Path
import signal
import subprocess
import time
import traceback
import uuid

import replicas


def snapshot(state, session, turn):
    return json.loads(replicas.fact(state, """
        WITH root AS (SELECT scope_key FROM ma.session WHERE session_id=:'session'),
        calls AS (SELECT c.* FROM ma.subagent_call c JOIN root r ON r.scope_key=c.parent_scope_key),
        scopes AS (SELECT scope_key FROM root UNION SELECT child_scope_key FROM calls WHERE child_scope_key IS NOT NULL)
        SELECT json_build_object(
          'root', (SELECT json_build_object('turn_id',t.turn_id,'status',t.status,'actor',t.actor,
              'epoch',t.execution_epoch) FROM ma.turn t JOIN root r ON r.scope_key=t.scope_key WHERE turn_id=:'turn'),
          'root_turns', (SELECT count(*) FROM ma.turn t JOIN root r ON r.scope_key=t.scope_key),
          'calls', (SELECT coalesce(json_agg(json_build_object('task_id',c.task_id,'root_turn_id',c.root_turn_id,
              'parent_turn_id',c.parent_turn_id,'child_session',s.session_id,'child_turn',c.child_turn_id,
              'status',t.status,'actor',t.actor,'epoch',t.execution_epoch)
              ORDER BY t.admission_sequence), '[]'::json)
              FROM calls c LEFT JOIN ma.session s ON s.scope_key=c.child_scope_key
              LEFT JOIN ma.turn t ON t.scope_key=c.child_scope_key AND t.turn_id=c.child_turn_id),
          'tasks', (SELECT coalesce(json_agg(json_build_object('task',t.task_id,'turn',t.turn_id,'name',t.tool_name,
              'native_call',t.native_tool_call_id,'execution',t.execution_id,'handle',t.process_handle,'status',t.status)), '[]'::json)
              FROM ma.tool_task t JOIN scopes s ON s.scope_key=t.scope_key),
          'suspensions', (SELECT count(*) FROM ma.turn_suspension p JOIN scopes s ON s.scope_key=p.scope_key),
          'resumes', (SELECT count(*) FROM ma.turn_resume p JOIN scopes s ON s.scope_key=p.scope_key),
          'legacy_sessions', (SELECT count(*) FROM cp.sessions WHERE session_id=:'session'));
        """, {"session": session, "turn": turn}))


def effects(state, label):
    matches = [p for p in (state / "sandbox").glob("*/side-effects.txt")
               if "start-child-one-" + label in p.read_text()]
    return matches


def replace_brains(state):
    manifest = json.loads((state / "manifest.json").read_text())
    names = ["brain-a", "brain-b"]
    before = {name: manifest["processes"][name]["pid"] for name in names}
    for name in names:
        replicas.signal_replica(state, name, signal.SIGKILL)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        alive = []
        for pid in before.values():
            status = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True)
            if status.returncode == 0 and status.stdout.strip() and not status.stdout.strip().startswith("Z"):
                alive.append(pid)
        if not alive:
            break
        time.sleep(.1)
    if alive:
        raise AssertionError(f"Original Brain processes still exist: {alive}")
    after = {name: replicas.restart_replica(state, name) for name in names}
    assert all(after[name] != before[name] for name in names), (before, after)
    return {"before": before, "after": after}


def run(state, seconds, send=False, replace=False, cancel=False):
    state = state.resolve()
    if seconds < 12 or seconds > 120:
        raise ValueError("Child fixture duration must be 12..120 seconds")
    output = state / f"subagents-{time.time_ns()}.json"
    record = {"passed": False, "seconds": seconds, "send": send,
              "replace_brains": replace, "cancel": cancel,
              "transport": "real RocketMQ/Redis/PostgreSQL", "model": "explicit deterministic test model",
              "sandbox": "real local subprocess, not ACS"}

    def save():
        output.write_text(json.dumps(record, indent=2) + "\n")

    save()
    try:
        environment, agent, label = replicas.fixture_agent(max_iters=8, extra_tools=("agent_spawn", "agent_send", "agent_list"))
        session = replicas.request(replicas.PORTS["portal-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
        session_id, base = session["id"], "/v1/sessions/" + session["id"]
        mode = "delegate-send" if send else "delegate"
        admitted = replicas.request(replicas.PORTS["portal-a"], base + "/turns",
                    {"input": {"message": f"fixture:{mode}:{seconds}:{label}"}}, uuid.uuid4().hex)
        turn = admitted["id"]
        record.update(environment=environment["id"], agent=agent["id"], session=session_id, turn=turn, label=label)
        save()
        subscription = replicas.EventSubscription(replicas.PORTS["portal-b"], base, terminal_turn=turn)
        deadline = time.monotonic() + seconds * (2 if send else 1) + 180
        action_done = False
        previous = None
        while time.monotonic() < deadline:
            root = replicas.request(replicas.PORTS["portal-b"], base + "/turns/" + turn)
            facts = snapshot(state, session_id, turn)
            status = (root["status"], tuple(call["status"] for call in facts["calls"]))
            if status != previous:
                print("SUBAGENTS", status, flush=True)
                previous = status
            record.update(facts=facts, root=root)
            save()
            if root["status"] in {"failed", "cancelled", "completed"}:
                break
            child_running = (facts["calls"] and facts["calls"][0]["status"] == "waiting_tool"
                             and root["status"] == "waiting_tool")
            running_effect = effects(state, label)
            if not action_done and (replace or cancel) and child_running and running_effect:
                assert running_effect[0].read_text().splitlines() == ["start-child-one-" + label], running_effect
                record["before_action"] = facts
                if replace:
                    record["replacement"] = replace_brains(state)
                if cancel:
                    record["cancellation"] = replicas.request(replicas.PORTS["portal-a"], base + f"/turns/{turn}/inputs",
                            {"type": "cancel", "payload": {}}, uuid.uuid4().hex)
                action_done = True
                save()
            time.sleep(.5)
        assert root["status"] == ("cancelled" if cancel else "completed"), (root, output)
        if replace or cancel:
            assert action_done, "The requested failure/cancel was never exercised"
        facts = snapshot(state, session_id, turn)
        calls = facts["calls"]
        assert len(calls) == (2 if send and not cancel else 1), facts
        assert facts["root_turns"] == 1 and facts["legacy_sessions"] == 0, facts
        assert len({call["child_session"] for call in calls}) == 1, facts
        assert len({call["child_turn"] for call in calls}) == len(calls), facts
        actor = json.loads(facts["root"]["actor"])
        assert actor["user"] == "local-developer" and actor["internal"] is False, actor
        for call in calls:
            assert call["root_turn_id"] == turn and call["parent_turn_id"] == turn, call
            assert json.loads(call["actor"]) == actor, call
            assert call["status"] == ("cancelled" if cancel else "succeeded"), call
        assert facts["suspensions"] >= len(calls) + 1 and facts["resumes"] >= len(calls) + 1, facts
        tasks = [task for task in facts["tasks"] if task["native_call"] in {
            "replica-execute-child-one-" + label, "replica-execute-child-two-" + label}]
        assert len(tasks) == len(calls) and len({task["execution"] for task in tasks}) == len(tasks), facts
        assert all(task["status"] == ("cancelled" if cancel else "succeeded") for task in tasks), tasks
        paths = effects(state, label)
        expected = ["start-child-one-" + label]
        if not cancel:
            expected += ["done-child-one-" + label]
            if send:
                expected += ["start-child-two-" + label, "done-child-two-" + label]
        assert len(paths) == 1 and paths[0].read_text().splitlines() == expected, paths
        events = replicas.request(replicas.PORTS["portal-a"], base + "/events?limit=256")
        assert not events["has_more"], events
        live = subscription.result()
        assert [event["cursor"] for event in live] == [event["cursor"] for event in events["data"]], (live, events)
        if not cancel:
            assert "brain-confirmed-children-" + label in json.dumps(events), events
        else:
            receipt = replicas.request(replicas.PORTS["portal-b"], base + f"/turns/{turn}/inputs/{record['cancellation']['id']}")
            assert receipt["status"] == "applied", receipt
            record["cancellation_receipt"] = receipt
        record.update(passed=True, facts=facts, events=events, live=live, side_effect=str(paths[0]))
        save()
        print("PASS native parent/child Turn admission, original Actor, durable Resume, SSE and single side effects", output, flush=True)
    except Exception as failure:
        record.update(passed=False, error=str(failure), traceback=traceback.format_exc())
        save()
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--replace-brains", action="store_true")
    parser.add_argument("--cancel", action="store_true")
    args = parser.parse_args()
    run(args.state, args.seconds, args.send, args.replace_brains, args.cancel)

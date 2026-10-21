#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Actual Harness MemoryStore operations after a real local tool suspension.

Uses the explicit fixture model and real CP / Portal / Brain / Hands / DB / MQ /
Redis. The Sandbox is a local subprocess, not ACS. No legacy CP Session exists.
"""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import uuid

import replicas


def update_document(store, label, content, version):
    request = urllib.request.Request(
        f"http://127.0.0.1:{replicas.PORTS['control']}/api/memory-stores/{store}/memories/verification/{label}.md",
        data=json.dumps({"content": content, "expectedVersion": version}).encode(), method="PUT",
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def run(state, seconds, readonly):
    if seconds < 15 or seconds > 180:
        raise ValueError("Memory fixture duration must be 15..180 seconds")
    environment, agent, label = replicas.fixture_agent(
        max_iters=8, extra_tools=("memory_store_write", "memory_store_read"))
    store = replicas.request(replicas.PORTS["control"], "/api/memory-stores", {"name": "Memory fixture " + label})
    update_document(store["id"], label, "before-suspension-" + label, 0)
    session = replicas.request(replicas.PORTS["portal-a"], "/v1/sessions",
                               {"agent": agent["id"], "memory_store_ids": [store["id"]]}, uuid.uuid4().hex)
    base = "/v1/sessions/" + session["id"]
    mode = "memory-denied" if readonly else "memory"
    turn = replicas.request(replicas.PORTS["portal-a"], base + "/turns",
                           {"input": {"message": f"fixture:{mode}:{seconds}:{store['id']}:{label}"}}, uuid.uuid4().hex)
    successor = replicas.request(replicas.PORTS["portal-b"], base + "/turns",
                                {"input": {"message": "fixture:echo:after-memory-" + label}}, uuid.uuid4().hex)
    output = state / f"memory-{time.time_ns()}.json"
    record = {"passed": False, "environment": environment["id"], "store": store["id"],
              "agent": agent["id"], "session": session["id"], "turns": [turn["id"], successor["id"]],
              "readonly_after_suspension": readonly, "seconds": seconds,
              "model": "explicit deterministic fixture", "sandbox": "real local subprocess, not ACS"}
    changed = False
    document_updated = False
    output.write_text(json.dumps(record, indent=2) + "\n")
    deadline = time.monotonic() + seconds + 150
    while time.monotonic() < deadline:
        first = replicas.request(replicas.PORTS["portal-b"], base + "/turns/" + turn["id"])
        second = replicas.request(replicas.PORTS["portal-a"], base + "/turns/" + successor["id"])
        if not document_updated and first["status"] == "waiting_tool":
            updated = update_document(store["id"], label, "native-memory-" + label, 1)
            assert updated["headVersion"] == 2
            document_updated = True
            record["document_updated_after_suspension"] = True
            output.write_text(json.dumps(record, indent=2) + "\n")
        if readonly and not changed and first["status"] == "waiting_tool":
            config = dict(environment["config"])
            config["memoryAccess"] = {store["id"]: "read_only"}
            request = urllib.request.Request(
                f"http://127.0.0.1:{replicas.PORTS['control']}/api/environments/{environment['id']}",
                data=json.dumps({"config": config}).encode(), method="PATCH",
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=30) as response:
                updated = json.load(response)
            assert updated["config"]["memoryAccess"][store["id"]] == "read_only", updated
            changed = True
            record["environment_updated_after_suspension"] = True
            output.write_text(json.dumps(record, indent=2) + "\n")
        if first["status"] in {"failed", "cancelled"} or second["status"] in {"failed", "cancelled"}:
            raise AssertionError((first, second, output))
        if first["status"] == second["status"] == "completed":
            break
        time.sleep(.5)
    assert first["status"] == second["status"] == "completed", (first, second, output)
    assert not readonly or changed, "Live read-only change was never exercised"
    assert document_updated, "Memory document was never updated during suspension"
    events = replicas.request(replicas.PORTS["portal-b"], base + "/events?limit=256")
    assert not events["has_more"]
    expected = "brain-confirmed-memory-" + ("denied-" if readonly else "") + label
    assert expected in json.dumps(events), events
    lifecycle = [(e["type"], e["data"].get("turn_id")) for e in events["data"] if e["type"].startswith("turn.")]
    assert lifecycle == [("turn.running", turn["id"]), ("turn.waiting_tool", turn["id"]),
                         ("turn.running", turn["id"]), ("turn.completed", turn["id"]),
                         ("turn.running", successor["id"]), ("turn.completed", successor["id"])], lifecycle
    documents = replicas.request(replicas.PORTS["control"], f"/api/memory-stores/{store['id']}/memories")
    assert len(documents) == 1 and documents[0]["content"] == "native-memory-" + label, documents
    assert documents[0]["path"] == "verification/" + label + ".md" and documents[0]["headVersion"] == 2
    legacy = int(replicas.fact(state, "SELECT count(*) FROM cp.sessions WHERE session_id=:'session';", {"session": session["id"]}))
    assert legacy == 0
    effects = [p for p in (state / "sandbox").glob("*/side-effects.txt") if "start-memory-" + label in p.read_text()]
    assert len(effects) == 1 and effects[0].read_text().splitlines() == ["start-memory-" + label, "done-memory-" + label], effects
    record.update(passed=True, documents=documents, legacy_sessions=legacy, events=events,
                  checkpoints=replicas.checkpoint_evidence(state, session["id"]))
    output.write_text(json.dumps(record, indent=2) + "\n")
    print("PASSED actual restored MemoryStore live read" + (" and preserved read-only mount: " if readonly else ": ") + str(output), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--seconds", default=30, type=int)
    parser.add_argument("--readonly", action="store_true")
    args = parser.parse_args()
    run(args.state.resolve(), args.seconds, args.readonly)


if __name__ == "__main__":
    main()

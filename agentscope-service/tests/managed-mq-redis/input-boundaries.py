#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Actual HTTP input-close and reply boundaries against the explicit owned fixture."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

import replicas


def post(port, path, body, key, method="POST"):
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", json.dumps(body).encode(),
         {"Content-Type": "application/json", "Idempotency-Key": key}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as failure:
        return failure.code, json.loads(failure.read())


def write(state, kind, record):
    path = state / f"{kind}-{int(time.time())}.json"
    path.write_text(json.dumps(record, indent=2) + "\n")
    return path


def closing(state):
    _, agent, identity = replicas.fixture_agent()
    session = replicas.request(19084, "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base = "/v1/sessions/" + session["id"]
    warmup = replicas.request(19084, base + "/turns", {"input": {"message": "fixture:echo:warm-close-" + identity}}, uuid.uuid4().hex)
    replicas.wait_turn(base, warmup["id"], {"completed"})
    label = "close-" + identity
    turn = replicas.request(19084, base + "/turns", {"input": {"message": "fixture:execute:1:" + label}}, uuid.uuid4().hex)
    endpoint = base + "/turns/" + turn["id"] + "/inputs"
    accepted = []
    # Guarantee a nonempty accepted set, then contend with the actual finishing boundary.
    body = {"type": "inject", "payload": {"message": "control:before-close:" + identity}}
    key = uuid.uuid4().hex
    status, first = post(19084, endpoint, body, key)
    assert status in {200, 201, 202}, (status, first)
    accepted.append((key, body, first))
    def contender(index):
        body = {"type": "inject", "payload": {"message": f"control:close-{index}:" + identity}}
        key = uuid.uuid4().hex
        status, value = post(19084 if index % 2 == 0 else 19184, endpoint, body, key)
        return key, body, status, value
    deadline = time.monotonic() + 45
    while not any("start-" + label in path.read_text() for path in (state / "sandbox").glob("*/side-effects.txt")):
        if time.monotonic() >= deadline: raise AssertionError("Close fixture tool did not start")
        time.sleep(.05)
    time.sleep(.85)
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(contender, range(24)))
    for key, body, status, value in results:
        if status in {200, 201, 202}:
            accepted.append((key, body, value))
        else:
            assert status == 409 and value["error"]["code"] == "input_closed", (status, value)
    closing_probes = []
    for index in range(40):
        key, body, status, value = contender(24 + index)
        closing_probes.append((key, body, status, value))
        if status == 409:
            assert value["error"]["code"] == "input_closed", value
            break
        assert status in {200, 201, 202}, (status, value)
        accepted.append((key, body, value))
        time.sleep(.05)
    terminal = replicas.wait_turn(base, turn["id"], {"completed", "failed"})
    late = post(19184, endpoint, {"type": "inject", "payload": {"message": "control:closed"}}, uuid.uuid4().hex)
    assert late[0] == 409 and late[1]["error"]["code"] == "input_closed", late
    receipts = replicas.request(19184, endpoint + "?limit=256")["data"]
    saved = {row["id"]: row for row in receipts}
    assert set(saved) == {value["id"] for _, _, value in accepted}, (saved, accepted)
    for key, body, value in accepted:
        receipt = saved[value["id"]]
        assert receipt["status"] in {"applied", "rejected"}, receipt
        status, duplicate = post(19084, endpoint, body, key)
        assert status in {200, 201, 202} and duplicate["id"] == receipt["id"] and duplicate["sequence"] == receipt["sequence"], (status, duplicate)
    path = write(state, "input-close", {"passed": True, "session": session["id"], "turn": turn["id"], "accepted": len(accepted), "contenders": results, "closing_probes": closing_probes, "warmup_turn": warmup["id"], "terminal": terminal, "receipts": receipts, "late": late})
    print("PASS cross-Portal input/close boundary, all accepted inputs settled, retry after close", flush=True)
    print(path, flush=True)


def reply(state):
    environment, agent, identity = replicas.fixture_agent()
    # Reuse the existing confirmation reply flow; this test adds no permission mechanism.
    status, changed = post(19081, "/api/v1/agents/" + agent["id"] + "/definition", {
        "name": "Reply fixture", "version": 1, "system": "Use the explicit fixture model.",
        "defaultEnvironmentId": environment["id"], "maxIters": 4,
        "tools": [{"type": "agent_toolset", "defaultConfig": {"enabled": False},
                   "configs": [{"name": "execute", "enabled": True,
                                "permissionPolicy": {"type": "always_ask"}}]}]}, uuid.uuid4().hex, method="PATCH")
    assert status == 200, (status, changed)
    session = replicas.request(19084, "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base = "/v1/sessions/" + session["id"]
    label = "reply-" + identity
    first = replicas.request(19084, base + "/turns", {"input": {"message": "fixture:execute:1:" + label}}, uuid.uuid4().hex)
    second = replicas.request(19184, base + "/turns", {"input": {"message": "fixture:echo:after-" + label}}, uuid.uuid4().hex)
    write(state, "reply-start", {"passed": False, "session": session["id"], "first": first["id"], "second": second["id"]})
    replicas.wait_turn(base, first["id"], {"waiting"}, timeout=45)
    snapshot = replicas.request(19084, base + "/turns/" + first["id"] + "/snapshot")
    actions = snapshot["resources"]["required_actions"]["data"]
    assert len(actions) == 1, actions
    action = actions[0]["data"]["request_id"]
    waiting = replicas.request(19184, base + "/turns/" + second["id"])
    assert waiting["status"] == "accepted", waiting
    files = [path for path in (state / "sandbox").glob("*/side-effects.txt") if "start-" + label in path.read_text()]
    assert not files, files
    body = {"type": "reply", "action_id": action, "payload": {"allow": True}}
    key = uuid.uuid4().hex
    endpoint = base + "/turns/" + first["id"] + "/inputs"
    accepted = replicas.request(19184, endpoint, body, key)
    duplicate = replicas.request(19084, endpoint, body, key)
    assert accepted["id"] == duplicate["id"] and accepted["sequence"] == duplicate["sequence"], (accepted, duplicate)
    replicas.wait_turn(base, second["id"], {"completed"})
    receipt = replicas.request(19084, endpoint + "/" + accepted["id"])
    assert receipt["status"] == "applied" and receipt["turn_id"] == first["id"], receipt
    snapshot = replicas.request(19184, base + "/turns/" + first["id"] + "/snapshot")
    assert snapshot["resources"]["required_actions"]["data"] == [], snapshot
    events = replicas.request(19084, base + "/events?limit=256")
    assert "brain-confirmed-hands-" + label in json.dumps(events), events
    files = [path for path in (state / "sandbox").glob("*/side-effects.txt") if "start-" + label in path.read_text()]
    assert len(files) == 1 and files[0].read_text().splitlines() == ["start-" + label, "done-" + label], files
    path = write(state, "reply", {"passed": True, "session": session["id"], "first": first["id"], "second": second["id"], "action": action, "receipt": receipt, "events": events, "side_effect": str(files[0])})
    print("PASS cross-Portal reply identity, same Turn continuation, next-Turn order, one tool execution", flush=True)
    print(path, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--scenario", choices=["closing", "reply"], required=True)
    args = parser.parse_args()
    globals()[args.scenario](args.state)

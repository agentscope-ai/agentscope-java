#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Agent failure handoff and following-Turn acceptance with the explicit replica model."""
import argparse
import json
from pathlib import Path
import time
import uuid

import replicas


def run(state):
    environment, agent, identity = replicas.fixture_agent()
    session = replicas.request(19084, "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base = "/v1/sessions/" + session["id"]
    # The deterministic model rejects this ordinary input. No provider is contacted.
    first = replicas.request(19084, base + "/turns", {"input": {"message": "unsupported fixture input " + identity}}, uuid.uuid4().hex)
    second = replicas.request(19184, base + "/turns", {"input": {"message": "fixture:echo:after-failure-" + identity}}, uuid.uuid4().hex)
    output = state / f"failures-{int(time.time())}.json"
    record = {"passed": False, "session": session["id"], "turns": [first["id"], second["id"]], "agent": agent["id"], "environment": environment["id"]}
    output.write_text(json.dumps(record, indent=2) + "\n")
    first = replicas.wait_turn(base, first["id"], {"failed"}, timeout=45)
    second = replicas.wait_turn(base, second["id"], {"completed"}, timeout=45)
    page = replicas.request(19184, base + "/events?limit=256")
    lifecycle = [(event["type"], event["data"].get("turn_id")) for event in page["data"] if event["type"].startswith("turn.")]
    expected = [("turn.running", first["id"]), ("turn.failed", first["id"]), ("turn.running", second["id"]), ("turn.completed", second["id"])]
    assert lifecycle == expected, lifecycle
    assert "Fixture requires fixture: input" not in json.dumps(page), page
    native_failures = replicas.fact(state,
        "SELECT count(*) FROM ma.native_event WHERE payload::jsonb->>'turnId'=:'turn' AND payload::jsonb->>'type'='turn/failed' AND payload LIKE '%managed_agent_execution_failed%'",
        {"turn": first["id"]})
    assert native_failures == "1", native_failures
    acks = replicas.fact(state,
        "SELECT bool_and(broker_acknowledged) FROM ma.turn WHERE turn_id IN (:'first', :'second')",
        {"first": first["id"], "second": second["id"]})
    deadline = time.monotonic() + 10
    while acks != "t" and time.monotonic() < deadline:
        time.sleep(.1)
        acks = replicas.fact(state,
            "SELECT bool_and(broker_acknowledged) FROM ma.turn WHERE turn_id IN (:'first', :'second')",
            {"first": first["id"], "second": second["id"]})
    assert acks == "t", acks
    record.update(passed=True, first=first, second=second, lifecycle=lifecycle, events=page, native_failure_decisions=native_failures, delivery_acks=acks)
    output.write_text(json.dumps(record, indent=2) + "\n")
    print("PASS model failure checkpoint/terminal/ACK, next Turn, durable failure identity", flush=True)
    print(output, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    run(parser.parse_args().state)

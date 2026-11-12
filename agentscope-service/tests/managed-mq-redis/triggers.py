#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Native Channel/Cron bridge acceptance against the owned two-replica fixture."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

import replicas


def trigger(body, expected=200):
    headers = {"Content-Type": "application/json",
               "X-Managed-Agents-Internal-Token": "managed-replica-fixture-internal-token-2026",
               "X-Managed-Agents-Internal-User": body["ownerId"]}
    request = urllib.request.Request("http://127.0.0.1:19081/api/internal/managed/triggers",
                                     json.dumps(body).encode(), headers)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as failure:
        status, raw = failure.code, failure.read(8192)
    if status != expected:
        raise AssertionError(f"Trigger HTTP {status}, expected {expected}: {raw.decode()}")
    return json.loads(raw)


def await_turn(body, receipt):
    read = dict(body, operation="events", sessionId=receipt["sessionId"],
                turnId=receipt["turn"]["id"], after="")
    deadline, events = time.monotonic() + 120, []
    while time.monotonic() < deadline:
        page = trigger(read)
        events.extend(page["events"]["data"])
        read["after"] = page["events"]["next_cursor"]
        if not page["events"]["has_more"] and page["turn"]["status"] in {"completed", "failed", "cancelled"}:
            assert page["turn"]["status"] == "completed", page
            return events
        time.sleep(.25)
    raise AssertionError("Native trigger Turn did not finish")


def run(state):
    manifest = json.loads((state / "manifest.json").read_text())
    environment, agent, identity = replicas.fixture_agent()
    owner = agent["ownerRef"]
    body = {"ownerId": owner, "actorId": "local-developer", "agentId": agent["id"], "externalKey": "channel:" + identity,
            "requestId": "message-one", "text": "fixture:execute:3:trigger-" + identity}
    record = {"agent": agent["id"], "owner": owner, "passed": False}
    output = state / f"triggers-{int(time.time())}.json"
    output.write_text(json.dumps(record, indent=2) + "\n")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        accepted = list(pool.map(trigger, [body, body]))
    assert accepted[0]["sessionId"] == accepted[1]["sessionId"], accepted
    assert accepted[0]["turn"]["id"] == accepted[1]["turn"]["id"], accepted
    first = accepted[0]
    second_body = dict(body, requestId="message-two", text="fixture:echo:second-" + identity)
    second = trigger(second_body)
    assert first["sessionId"] == second["sessionId"] and first["turn"]["id"] != second["turn"]["id"]
    trigger(dict(body, text="different intent"), 409)
    events = await_turn(second_body, second)
    first_events = await_turn(body, first)
    own = [event for event in first_events if event.get("data", {}).get("turn_id") == first["turn"]["id"]]
    assert any(event["type"] == "turn.completed" for event in own), own
    files = [path for path in (state / "sandbox").glob("*/side-effects.txt") if "trigger-" + identity in path.read_text()]
    assert len(files) == 1 and files[0].read_text().splitlines() == ["start-trigger-" + identity, "done-trigger-" + identity], files
    deployment = replicas.request(19081, "/api/deployments", {"name": "Cron native " + identity,
         "agentId": agent["id"], "environmentId": environment["id"], "triggerType": "cron",
         "cronExpression": "0 * * * * *"})
    path = "/api/internal/deployments/" + deployment["id"] + "/fire"
    key = "cron:" + deployment["id"] + ":2026-10-15T00:01:00Z"
    def fire():
        request = urllib.request.Request("http://127.0.0.1:19081" + path,
             json.dumps({"text": "fixture:echo:cron-" + identity}).encode(),
             {"Content-Type": "application/json", "Idempotency-Key": key,
              "X-Managed-Agents-Internal-Token": "managed-replica-fixture-internal-token-2026"})
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.load(response)
    fired = [fire(), fire()]
    assert fired[0]["lastSessionId"] == fired[1]["lastSessionId"], fired
    sessions = [first["sessionId"], fired[0]["lastSessionId"]]
    counts = replicas.fact(state, "SELECT count(*) FROM ma.turn t JOIN ma.session s ON s.scope_key=t.scope_key WHERE s.session_id=:'session'", {"session": sessions[1]})
    assert counts == "1", counts
    cron_turn = replicas.fact(state, "SELECT t.turn_id FROM ma.turn t JOIN ma.session s ON s.scope_key=t.scope_key WHERE s.session_id=:'session'", {"session": sessions[1]})
    replicas.wait_turn("/v1/sessions/" + sessions[1], cron_turn, {"completed"})
    actor = replicas.fact(state, "SELECT actor FROM ma.turn WHERE turn_id=:'turn'", {"turn": first["turn"]["id"]})
    assert json.loads(actor)["user"] == body["actorId"] and json.loads(actor)["internal"] is False, actor
    legacy = replicas.fact(state, "SELECT count(*) FROM cp.sessions WHERE session_id IN (:'first', :'cron')", {"first": sessions[0], "cron": sessions[1]})
    assert legacy == "0", legacy
    record.update(passed=True, database=manifest["database"], channel=accepted,
                  second=second, events=events, first_events=first_events, cron=fired,
                  actor=json.loads(actor), legacy_session_rows=legacy, side_effect=str(files[0]))
    output.write_text(json.dumps(record, indent=2) + "\n")
    print("PASS native Channel duplicate admission, same Session FIFO, one tool effect, original Actor, Cron retry", flush=True)
    print(output, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    run(parser.parse_args().state)

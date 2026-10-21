#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Console Chat native admission against the owned real-MQ replica fixture."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

import replicas


def call(path, body=None, key=None, method=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request("http://127.0.0.1:19081" + path,
        None if body is None else json.dumps(body).encode(), headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read(8192)
    assert status == expected, (path, status, raw.decode())
    return json.loads(raw)


def run(state):
    _, agent, identity = replicas.fixture_agent()
    chat = call("/api/v1/chats", {"tenant": "default", "namespace": "default",
        "agentId": agent["id"], "title": "Native conversation " + identity}, expected=201)["chat"]
    record = {"chat": chat, "passed": False}
    output = state / f"conversations-{int(time.time())}.json"
    output.write_text(json.dumps(record, indent=2) + "\n")
    assert chat["runtimeKind"] == "managed", chat
    session = chat["runtimeSessionId"]
    base = "/v1/sessions/" + session
    assert replicas.request(19084, base)["id"] == session
    count = replicas.fact(state, "SELECT count(*) FROM ma.turn t JOIN ma.session s ON s.scope_key=t.scope_key WHERE s.session_id=:'session'", {"session": session})
    assert count == "0", count
    path = "/api/v1/chats/" + chat["id"]
    body = {"message": "fixture:execute:8:chat-" + identity}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        receipts = list(executor.map(lambda _: call(path + "/turns", body, "first-" + identity, expected=202), range(2)))
    for receipt in receipts:
        assert receipt["status"] == "accepted" and receipt["eventsUrl"] == base + "/events", receipt
        assert receipt["eventStreamUrl"] == base + "/events/stream", receipt
    count = replicas.fact(state, "SELECT count(*) FROM ma.turn t JOIN ma.session s ON s.scope_key=t.scope_key WHERE s.session_id=:'session'", {"session": session})
    assert count == "1", count
    first = replicas.fact(state, "SELECT t.turn_id FROM ma.turn t JOIN ma.session s ON s.scope_key=t.scope_key WHERE s.session_id=:'session'", {"session": session})
    call(path, method="DELETE", expected=409)
    call(path + "/turns", {"message": "changed intent"}, "first-" + identity, expected=409)
    # The generic conversation entry must preserve identity and the same native Session too.
    direct = "/api/v1/sessions/" + chat["sessionId"] + "/user-message"
    call(direct, {"content": "fixture:echo:next-" + identity}, "second-" + identity, expected=202)
    turns = replicas.request(19084, base + "/turns?order=asc&limit=100")["data"]
    assert len(turns) == 2, turns
    for turn in turns:
        replicas.wait_turn(base, turn["id"], {"completed"})
    events = replicas.request(19184, base + "/events?limit=100")
    assert not events["has_more"], events
    terminal = [e["data"]["turn_id"] for e in events["data"] if e["type"] == "turn.completed"]
    assert terminal == [turn["id"] for turn in turns], terminal
    suspension = json.loads(replicas.fact(state, "SELECT json_build_object('suspensions', (SELECT count(*) FROM ma.turn_suspension WHERE turn_id=:'turn'), 'resumes', (SELECT count(*) FROM ma.turn_resume WHERE turn_id=:'turn'))", {"turn": first}))
    assert suspension["suspensions"] >= 1 and suspension["resumes"] >= 1, suspension
    actor = json.loads(replicas.fact(state, "SELECT actor FROM ma.turn WHERE turn_id=:'turn'", {"turn": first}))
    assert actor["user"] == "local-developer" and actor["internal"] is False, actor
    legacy = replicas.fact(state, "SELECT count(*) FROM cp.sessions WHERE session_id=:'session'", {"session": session})
    assert legacy == "0", legacy
    effects = [p for p in (state / "sandbox").glob("*/side-effects.txt") if "chat-" + identity in p.read_text()]
    assert len(effects) == 1 and effects[0].read_text().splitlines() == ["start-chat-" + identity, "done-chat-" + identity], effects
    deleted = call(path, method="DELETE")["chat"]
    assert deleted["status"] == "deleted", deleted
    assert replicas.request(19084, base)["id"] == session
    record.update(passed=True, receipts=receipts, turns=turns, events=events, suspension=suspension,
                  actor=actor, legacy_session_rows=legacy, deleted=deleted, side_effect=str(effects[0]))
    output.write_text(json.dumps(record, indent=2) + "\n")
    print("PASS native Chat provision, idempotent submit, FIFO, deletion gate, original Actor and retained evidence", flush=True)
    print(output, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, required=True)
    run(parser.parse_args().state)

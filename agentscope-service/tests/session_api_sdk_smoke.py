#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Official SDK contract checks against the isolated Portal/Dataplane/Harness fixture."""

import time
import threading

import anthropic


def verify_session_api(base_url, key, restart_portal, approval=False, external=False):
    client = anthropic.Anthropic(api_key=key, base_url=base_url, max_retries=0,
                                 timeout=30, _strict_response_validation=True)
    sessions = client.beta.sessions
    session = sessions.create(agent="agent-1", environment_id="environment-1", title="SDK contract",
                              metadata={"fixture": "claude-session"},
                              extra_headers={"Idempotency-Key": "sdk-create"})
    assert session.type == "session" and session.id == "sdk-session"
    assert session.status == "idle" and session.agent.id == "agent-1", session.model_dump()
    assert session.agent.model.id and session.agent.version >= 1
    assert sessions.retrieve(session.id).metadata == {"fixture": "claude-session"}
    assert sessions.update(session.id, title="Updated SDK title").title == "Updated SDK title"
    assert session.id in [row.id for row in sessions.list()]

    def message(text, idempotency):
        return sessions.events.send(session.id, events=[{"type": "user.message", "content": [
            {"type": "text", "text": text}]}], extra_headers={"Idempotency-Key": idempotency})

    def await_idle(required_action=False):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            history = list(sessions.events.list(session.id))
            idle = [row for row in history if row.type == "session.status_idle"]
            if idle and idle[-1].stop_reason.type == ("requires_action" if required_action else "end_turn"):
                return history
            time.sleep(0.1)
        raise AssertionError("Session did not settle: " + str(history))

    # Open first: Claude streams follow new events unless the caller opts into replay.
    with sessions.events.stream(session.id, event_deltas=["agent.message"]) as stream:
        timeout = threading.Timer(35, stream.close)
        timeout.daemon = True
        timeout.start()
        accepted = message("SDK hello", "sdk-message")
        assert len(accepted.data) == 1 and accepted.data[0].processed_at is None
        echoed_id = accepted.data[0].id
        seen = []
        for event in stream:
            seen.append(event)
            if event.type == "session.status_idle" and event.stop_reason.type == "end_turn":
                break
        timeout.cancel()
    assert any(row.type == "user.message" and row.id == echoed_id and row.processed_at for row in seen)
    buffered = [row for row in seen if row.type == "agent.message"]
    assert buffered and any(block.type == "text" and "Smoke response" in block.text for row in buffered for block in row.content)
    previews = [row for row in seen if row.type == "event_start" and row.event.type == "agent.message"]
    assert previews and any(row.event.id == buffered[0].id for row in previews)
    assert any(row.type == "event_delta" and row.event_id == buffered[0].id for row in seen)
    starts = {row.id for row in seen if row.type == "span.model_request_start"}
    assert all(row.model_request_start_id in starts for row in seen if row.type == "span.model_request_end")
    history = await_idle()
    assert not any(row.type in ("event_start", "event_delta") for row in history)
    first_page = sessions.events.list(session.id, limit=1)
    assert first_page.has_next_page() and first_page.data[0].id != first_page.get_next_page().data[0].id
    assert len(sessions.events.list(session.id, types=["user.message"]).data) == 1
    assert message("SDK hello", "sdk-message").data[0].id == echoed_id
    restart_portal()
    assert [row.id for row in sessions.events.list(session.id)] == [row.id for row in history]

    if approval or external:
        before = {row.id for row in history}
        message("approval tool fixture" if approval else "external result fixture", "sdk-tool")
        # Ignore the previous end_turn while waiting for this Turn's requires_action.
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            history = list(sessions.events.list(session.id))
            uses = [row for row in history if row.type == "agent.tool_use" and row.id not in before]
            blocked = [row for row in history if row.type == "session.status_idle" and row.id not in before and row.stop_reason.type == "requires_action"]
            if uses and blocked:
                break
            time.sleep(0.1)
        assert uses and blocked and set(blocked[-1].stop_reason.event_ids) == {row.id for row in uses}
        answers = ([{"type": "user.tool_confirmation", "tool_use_id": row.id, "result": "allow"} for row in uses]
                   if approval else [{"type": "user.tool_result", "tool_use_id": row.id, "content": [
                       {"type": "text", "text": "SDK first result"},
                       {"type": "text", "text": "SDK second result"}], "is_error": False} for row in uses])
        response = sessions.events.send(session.id, events=answers, extra_headers={"Idempotency-Key": "sdk-results"})
        assert len(response.data) == len(uses)
        if external:
            assert all(row.processed_at for row in response.data)
            sessions.events.send(session.id, events=answers)
        await_idle()

    interrupted = sessions.events.send(session.id, events=[{"type": "user.interrupt"}])
    assert interrupted.data[0].type == "user.interrupt"
    archived = sessions.archive(session.id)
    assert archived.status == "terminated" and archived.archived_at
    assert session.id not in [row.id for row in sessions.list()]
    assert session.id in [row.id for row in sessions.list(include_archived=True)]
    deleted = sessions.delete(session.id)
    assert deleted.type == "session_deleted" and deleted.id == session.id
    try:
        sessions.retrieve(session.id)
        raise AssertionError("Deleted Session remains readable")
    except anthropic.NotFoundError as error:
        assert error.body["type"] == "error"
    client.close()

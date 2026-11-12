#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""First-release official SDK smoke against an explicitly provided local execution fixture."""
import threading
import anthropic
from managed_agents import ServiceClient


def verify_session_api(base_url, key, restart_agent_service, approval=False, external=False):
    with anthropic.Anthropic(api_key=key, base_url=base_url, max_retries=0, timeout=30,
                             _strict_response_validation=True) as client:
        sessions = client.beta.sessions
        session = sessions.create(agent="agent-1", environment_id="environment-1", title="SDK contract",
                                  metadata={"fixture": "claude-session"},
                                  extra_headers={"Idempotency-Key": "sdk-create"})
        assert session.type == "session" and session.status == "idle"
        assert sessions.retrieve(session.id).metadata == {"fixture": "claude-session"}
        native = ServiceClient(base_url, api_key=key).managed_sessions()
        capabilities = native.capabilities(session.id)
        assert capabilities["extensions"]["durable-events-v1"] is False
        assert capabilities["features"]["turn_output"] is True

        def send():
            return sessions.events.send(session.id, events=[{"type": "user.message", "content": [
                {"type": "text", "text": "SDK hello"}]}], extra_headers={"Idempotency-Key": "sdk-message"})

        with sessions.events.stream(session.id) as stream:
            timer = threading.Timer(35, stream.close)
            timer.daemon = True
            timer.start()
            receipt = send()
            assert receipt.data[0].processed_at is None
            seen = []
            for event in stream:
                seen.append(event)
                if event.type == "session.status_idle" and event.stop_reason.type == "end_turn":
                    break
            timer.cancel()
        assert any(event.type == "user.message" and event.id == receipt.data[0].id for event in seen)
        assert any(event.type == "agent.message" for event in seen)
        assert not any(event.type in {"event_start", "event_delta"} for event in seen)
        history = list(sessions.events.list(session.id))
        first = sessions.events.list(session.id, limit=1)
        assert first.has_next_page()
        assert first.data[0].id != first.get_next_page().data[0].id
        turn = native.turns(session.id)["data"][0]
        assert receipt.data[0].id in turn["input_event_ids"]
        assert native.final_output(session.id, turn["id"], timeout=35)
        frozen = receipt.model_dump()
        restart_agent_service()
        assert send().model_dump() == frozen
        assert [row.id for row in sessions.events.list(session.id)] == [row.id for row in history]
        for operation in [lambda: sessions.list(), lambda: sessions.update(session.id, title="unsupported"),
                          lambda: sessions.archive(session.id), lambda: sessions.delete(session.id)]:
            try:
                operation()
                raise AssertionError("Unsupported capability was accepted")
            except anthropic.BadRequestError:
                pass
        if approval or external:
            event = ({"type": "user.tool_confirmation", "tool_use_id": "unsupported", "result": "allow"}
                     if approval else {"type": "user.custom_tool_result", "custom_tool_use_id": "unsupported", "content": []})
            try:
                sessions.events.send(session.id, events=[event], extra_headers={"Managed-Agents-Extensions": "turn-inputs-v1"})
                raise AssertionError("Unsupported tool interaction was accepted")
            except anthropic.BadRequestError as error:
                assert error.body["error"]["code"] == "unsupported_capability"

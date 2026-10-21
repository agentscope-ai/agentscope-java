# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("interrupt_check", Path(__file__).with_name("verify_interrupt.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def event(identity, kind, at, **fields):
    return {"id": identity, "type": kind, "processed_at": "2026-10-19T00:00:" + at + "Z", **fields}


@pytest.mark.parametrize("idle_at,cancel_at", [("01.500", "01.502"), ("01.000", "01.000")])
def test_standard_idle_precedes_optional_cancellation_notice(idle_at, cancel_at):
    events = [event("interrupt", "user.interrupt", "01.000"),
              event("idle", "session.status_idle", idle_at, stop_reason={"type": "end_turn"}),
              event("cancel", "agentscope_service.turn.cancelled", cancel_at),
              event("run", "agentscope_service.turn.running", "02.000", agentscope_service={"turn_id": "next"})]
    assert module.has_idle_boundary(events, "interrupt", "next")


@pytest.mark.parametrize("idle_at,reason", [("00.999", "end_turn"), ("02.001", "end_turn"), ("01.500", "other")])
def test_rejects_idle_outside_the_actual_interrupt_boundary(idle_at, reason):
    events = [event("interrupt", "user.interrupt", "01.000"),
              event("idle", "session.status_idle", idle_at, stop_reason={"type": reason}),
              event("run", "agentscope_service.turn.running", "02.000", agentscope_service={"turn_id": "next"})]
    assert not module.has_idle_boundary(events, "interrupt", "next")

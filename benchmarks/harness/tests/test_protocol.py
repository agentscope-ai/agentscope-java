# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Protocol regressions driven by upstream lifecycle and replay semantics."""
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runtime.protocol import (  # noqa: E402
    EventProjection, ProtocolError, SCHEMA, iter_jsonl, iter_sse, validate_event, validate_request,
)


WHEN = "2026-11-07T12:00:00Z"


def event(kind, identity, **fields):
    return {"id": identity, "type": kind, "processed_at": WHEN, **fields}


def text(value):
    return [{"type": "text", "text": value}]


def start(identity="message"):
    return {"type": "event_start", "event": {"type": "agent.message", "id": identity}}


def delta(value, identity="message", index=0):
    return {"type": "event_delta", "event_id": identity,
            "delta": {"type": "content_delta", "index": index,
                      "content": {"type": "text", "text": value}}}


class SchemaContractTest(unittest.TestCase):
    def test_codex_terminal_task_with_embedded_error_is_not_successful_completion(self):
        from runtime.native_events import NativeConverter
        converter = NativeConverter("codex")
        converter.codex({"type": "event_msg", "timestamp": WHEN,
                         "payload": {"type": "task_complete"}}, 0)
        self.assertTrue(converter.completed)
        converter.codex({"type": "event_msg", "timestamp": WHEN,
                         "payload": {"type": "task_complete", "last_agent_message": None,
                                     "error": {"message": "remote compaction output missing"}}}, 1)
        self.assertFalse(converter.completed)
        self.assertEqual(converter.projection.events[-1]["type"], "session.error")
        self.assertEqual(converter.projection.events[-1]["error"]["message"],
                         "remote compaction output missing")
        self.assertEqual(converter.mapping[-1]["native_type"], "event_msg:task_complete")
        self.assertNotEqual(converter.mapping[-1]["event_id"], converter.mapping[0]["event_id"])

    def test_schema_is_valid_and_send_parameters_are_not_history_records(self):
        Draft202012Validator.check_schema(SCHEMA)
        request = {"events": [{"type": "user.message", "content": text("hello")}]}
        validate_request(request)
        with self.assertRaises(ProtocolError):
            validate_event(request["events"][0])
        request["events"][0]["id"] = "client-made-id"
        with self.assertRaises(ProtocolError):
            validate_request(request)

    def test_redacted_output_is_accepted_but_cannot_be_sent_as_user_content(self):
        validate_event(event("agent.message", "message", content=[{"type": "redacted"}]))
        with self.assertRaises(ProtocolError):
            validate_request({"events": [{"type": "user.message", "content": [{"type": "redacted"}]}]})

    def test_nonstandard_wrappers_timestamps_and_thinking_content_are_rejected(self):
        for value in [event("agent.message", "m", content=text("ok"), data={}),
                      event("agent.message", "m", content=text("ok"), case_id="case"),
                      {**event("agent.message", "m", content=text("ok")), "processed_at": "yesterday"},
                      event("agent.thinking", "t", content=text("private reasoning")),
                      event("turn.finished", "t")]:
            with self.subTest(kind=value["type"]), self.assertRaises(ProtocolError):
                validate_event(value)

    def test_error_retry_status_is_an_object_not_a_string(self):
        error = {"type": "unknown_error", "message": "adapter exited",
                 "retry_status": {"type": "terminal"}}
        validate_event(event("session.error", "error", error=error))
        error["retry_status"] = "terminal"
        with self.assertRaises(ProtocolError):
            validate_event(event("session.error", "error", error=error))

    def test_partial_usage_is_not_filled_with_invented_zeroes(self):
        with self.assertRaises(ProtocolError):
            validate_event(event("span.model_request_end", "end", model_request_start_id="start",
                                 model_usage={"input_tokens": 2, "output_tokens": 1}))

    def test_requires_action_needs_references_and_deny_message_needs_denial(self):
        with self.assertRaises(ProtocolError):
            validate_event(event("session.status_idle", "idle", stop_reason={"type": "requires_action"}))
        with self.assertRaises(ProtocolError):
            validate_request({"events": [{"type": "user.tool_confirmation", "tool_use_id": "tool",
                                          "result": "allow", "deny_message": "no"}]})

    def test_preview_records_have_no_id_or_timestamp_of_their_own(self):
        for value in ({**start(), "id": "preview-id"}, {**delta("hi"), "processed_at": WHEN}):
            with self.assertRaises(ProtocolError):
                validate_event(value)


class ReplayContractTest(unittest.TestCase):
    def test_queued_event_updates_once_and_stale_history_cannot_revert_it(self):
        projection = EventProjection()
        queued = event("user.message", "user", content=text("hello"))
        queued["processed_at"] = None
        self.assertTrue(projection.accept(queued))
        processed = {**queued, "processed_at": WHEN}
        self.assertTrue(projection.accept(processed))
        self.assertFalse(projection.accept(queued))
        self.assertFalse(projection.accept(processed))
        self.assertEqual(projection.events, [processed])
        with self.assertRaises(ProtocolError):
            projection.accept({**processed, "content": text("changed input")})

    def test_returned_events_cannot_mutate_canonical_history(self):
        projection = EventProjection()
        original = event("agent.message", "message", content=text("answer"))
        projection.accept(original)
        original["content"][0]["text"] = "changed"
        snapshot = projection.events
        snapshot[0]["content"][0]["text"] = "also changed"
        self.assertEqual(projection.events[0]["content"], text("answer"))

    def test_previews_are_replaced_by_complete_message_not_appended(self):
        projection = EventProjection()
        projection.accept(start())
        projection.accept(delta("hello"))
        self.assertEqual(projection.events, [])
        projection.accept(event("agent.message", "message", content=text("hello world")))
        self.assertEqual(projection.previews, {})
        self.assertEqual(projection.events[0]["content"], text("hello world"))
        self.assertEqual(projection.summary()["persisted_events"], 1)

    def test_history_seeded_before_live_tail_deduplicates_buffered_previews(self):
        projection = EventProjection()
        final = event("agent.message", "message", content=text("answer"))
        projection.accept(final)
        projection.accept(start())
        projection.accept(delta("ans"))
        projection.accept(final)
        self.assertEqual(projection.events, [final])
        self.assertEqual(projection.previews, {})

    def test_preview_content_indices_and_redaction(self):
        projection = EventProjection()
        projection.accept(start())
        projection.accept(delta("first", index=0))
        projection.accept(delta("hidden", index=1))
        projection.accept(event("agent.message", "message",
                                content=text("first block") + [{"type": "redacted"}]))
        self.assertEqual(projection.previews, {})

    def test_missing_start_or_inconsistent_final_is_not_silently_accepted(self):
        projection = EventProjection()
        with self.assertRaises(ProtocolError):
            projection.accept(delta("orphan"))
        projection.accept(start())
        projection.accept(delta("prefix"))
        with self.assertRaises(ProtocolError):
            projection.accept(event("agent.message", "message", content=text("different")))
        self.assertEqual(projection.events, [])

    def test_disconnect_and_interrupted_turn_discard_only_preview(self):
        projection = EventProjection()
        projection.accept(event("session.status_running", "running"))
        projection.accept(start())
        projection.accept(delta("unfinished"))
        projection.reset_previews()
        projection.accept(start())
        projection.accept(delta("another"))
        projection.accept(event("user.interrupt", "interrupt"))
        projection.accept(event("session.status_idle", "idle", stop_reason={"type": "end_turn"}))
        self.assertEqual(projection.previews, {})
        self.assertEqual(projection.status, "idle")
        self.assertNotIn("score", projection.summary())
        self.assertNotIn("passed", projection.summary())
        self.assertNotIn("agent.message", projection.summary()["event_counts"])

    def test_custom_result_before_idle_does_not_leave_a_false_pending_call(self):
        projection = EventProjection()
        projection.accept(event("session.status_running", "running"))
        projection.accept(event("agent.custom_tool_use", "call", name="lookup", input={}))
        projection.accept(event("user.custom_tool_result", "result", custom_tool_use_id="call", content=text("42")))
        projection.accept(event("session.status_idle", "idle",
                                stop_reason={"type": "requires_action", "event_ids": ["call"]}))
        self.assertEqual(projection.pending_calls, [])
        self.assertEqual(projection.status, "running")
        # The source protocol accepts another result for the same call without re-executing.
        projection.accept(event("user.custom_tool_result", "result-again", custom_tool_use_id="call", content=text("42")))
        self.assertEqual(projection.pending_calls, [])

    def test_multiple_blocking_calls_resolve_independently(self):
        projection = EventProjection()
        for identity in ("a", "b"):
            projection.accept(event("agent.custom_tool_use", identity, name="lookup", input={}))
        projection.accept(event("session.status_idle", "idle",
                                stop_reason={"type": "requires_action", "event_ids": ["a", "b"]}))
        projection.accept(event("user.custom_tool_result", "ra", custom_tool_use_id="a", content=text("ok")))
        self.assertEqual(projection.pending_calls, ["b"])
        self.assertEqual(projection.status, "idle")

    def test_builtin_mcp_and_custom_result_ids_are_not_interchangeable(self):
        projection = EventProjection()
        projection.accept(event("agent.mcp_tool_use", "mcp", name="lookup", input={}, mcp_server_name="dataset"))
        with self.assertRaises(ProtocolError):
            projection.accept(event("agent.tool_result", "wrong", tool_use_id="mcp", content=text("ok")))
        projection.accept(event("agent.mcp_tool_result", "right", mcp_tool_use_id="mcp", content=text("ok")))

    def test_confirmation_only_resolves_a_real_permission_request(self):
        projection = EventProjection()
        projection.accept(event("agent.tool_use", "tool", name="write", input={}, evaluated_permission="ask"))
        projection.accept(event("user.tool_confirmation", "confirmation", tool_use_id="tool", result="deny"))
        self.assertEqual(projection.pending_calls, [])
        projection.accept(event("agent.tool_use", "allowed", name="read", input={}, evaluated_permission="allow"))
        with self.assertRaises(ProtocolError):
            projection.accept(event("user.tool_confirmation", "bad", tool_use_id="allowed", result="allow"))

    def test_retry_error_alone_does_not_become_a_terminal_failure(self):
        projection = EventProjection()
        projection.accept(event("session.status_running", "running"))
        projection.accept(event("session.error", "error", error={"type": "unknown_error", "message": "transient",
                                                                 "retry_status": {"type": "retrying"}}))
        self.assertEqual(projection.status, "running")
        projection.accept(event("session.status_rescheduled", "retry"))
        self.assertEqual(projection.status, "rescheduled")

    def test_unresolved_reference_is_rejected_without_polluting_history(self):
        projection = EventProjection()
        with self.assertRaises(ProtocolError):
            projection.accept(event("session.status_idle", "idle",
                                    stop_reason={"type": "requires_action", "event_ids": ["missing"]}))
        self.assertEqual(projection.events, [])
        self.assertEqual(projection.pending_calls, [])

    def test_completed_tool_result_removes_a_pending_permission(self):
        projection = EventProjection()
        projection.accept(event("agent.tool_use", "tool", name="read", input={}, evaluated_permission="ask"))
        projection.accept(event("agent.tool_result", "result", tool_use_id="tool", is_error=True))
        self.assertEqual(projection.pending_calls, [])

    def test_model_span_end_requires_start_and_clears_abandoned_preview(self):
        projection = EventProjection()
        end = event("span.model_request_end", "end", model_request_start_id="start", is_error=True,
                    model_usage={"input_tokens": 2, "output_tokens": 1,
                                 "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0})
        with self.assertRaises(ProtocolError):
            projection.accept(end)
        projection.accept(event("span.model_request_start", "start"))
        projection.accept(start())
        projection.accept(delta("unfinished"))
        projection.accept(end)
        self.assertEqual(projection.previews, {})
        self.assertNotIn("agent.message", projection.summary()["event_counts"])

    def test_thinking_preview_cannot_receive_text_or_become_a_message(self):
        projection = EventProjection()
        projection.accept({"type": "event_start", "event": {"type": "agent.thinking", "id": "thinking"}})
        with self.assertRaises(ProtocolError):
            projection.accept(delta("secret", identity="thinking"))
        with self.assertRaises(ProtocolError):
            projection.accept(event("agent.message", "thinking", content=text("message")))
        projection.accept(event("agent.thinking", "thinking"))
        projection.accept(start())
        projection.accept(delta("visible text"))
        with self.assertRaises(ProtocolError):
            projection.accept(event("agent.thinking", "message"))
        self.assertEqual(projection.previews, {("message", 0): "visible text"})


class TransportContractTest(unittest.TestCase):
    def test_sse_multiline_comments_crlf_and_unicode_are_preserved(self):
        value = event("agent.message", "message", content=text("中文\u2028still one JSON record"))
        lines = json.dumps(value, indent=2, ensure_ascii=False).split("\n")
        wire = ": heartbeat\r\nevent: agent.message\r\n" + "".join("data: " + line + "\r\n" for line in lines) + "\r\n"
        self.assertEqual(list(iter_sse(io.BytesIO(wire.encode()))), [value])
        self.assertEqual(list(iter_jsonl(io.BytesIO((json.dumps(value, ensure_ascii=False) + "\n").encode()))), [value])

    def test_sse_truncated_record_is_not_turned_into_a_final_message(self):
        self.assertEqual(list(iter_sse(io.BytesIO(b'data: {"type":"agent.message"}\n'))), [])

    def test_sse_event_name_mismatch_is_rejected(self):
        with self.assertRaises(ProtocolError):
            list(iter_sse(io.BytesIO(b'event: agent.message\ndata: {"type":"session.status_idle"}\n\n')))

    def test_fixture_cli_writes_canonical_events_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary) / "result"
            command = [sys.executable, str(ROOT / "eval.py"), "protocol-smoke", "--output", str(out)]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            summary = json.loads((out / "summary.json").read_text())
            self.assertEqual(summary["execution"], "offline_fixture_only")
            self.assertEqual(summary["session_status"], "idle")
            self.assertEqual(summary["pending_calls"], [])
            saved = (out / "events.jsonl").read_bytes()
            self.assertNotIn(b'"type": "event_delta"', saved)
            repeated = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(repeated.returncode, 2)
            self.assertEqual((out / "events.jsonl").read_bytes(), saved)


if __name__ == "__main__":
    unittest.main()

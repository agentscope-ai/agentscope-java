# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from reports.unified import render, summarize
from runtime.redaction import redact
from runtime.native_events import NativeConverter, convert
from runtime.atif import export as export_atif

WHEN = "2026-11-07T12:00:00Z"


def config():
    value = json.loads((ROOT / "configs/suites/smoke.json").read_text())
    value.update(run_id="test-smoke")
    return value


class InfrastructureTest(unittest.TestCase):


    def test_missing_and_unscored_results_remain_in_denominator(self):
        value = config()
        rows = [{"agent": "agentscope", "task": value["tasks"][0], "rollout": 0,
                 "score": 1, "scoring_status": "scored", "execution_status": "completed"}]
        summary = summarize(value, rows)
        self.assertEqual((summary["planned"], summary["scored"], summary["passed"]), (6, 1, 1))
        self.assertEqual(summary["end_to_end_pass_rate"], 1 / 6)
        self.assertFalse(summary["smoke_passed"])
        with self.assertRaises(ValueError):
            summarize(value, rows + rows)

    def test_smoke_requires_protocol_completion_and_archival_as_well_as_scores(self):
        value = config()
        rows = [{"agent": agent["name"], "task": task, "rollout": 0, "score": 1,
                 "scoring_status": "scored", "execution_status": "completed", "protocol_valid": True,
                 "native_completed": True, "archive_complete": True}
                for agent in value["agents"] for task in value["tasks"]]
        self.assertTrue(summarize(value, rows)["smoke_passed"])
        rows[0]["archive_complete"] = False
        self.assertFalse(summarize(value, rows)["smoke_passed"])

    def test_report_escapes_task_text_and_public_export_omits_private_links(self):
        value = config()
        summary = summarize(value, [])
        summary["results"][0].update(task="<script>alert(1)</script>", archive_path="trials/example")
        page = render(value, summary)
        self.assertIn("&lt;script&gt;", page)
        self.assertNotIn("<script>alert(1)</script>", page)
        self.assertIn("trials/example", page)
        self.assertNotIn("trials/example", render(value, summary, public=True))
        summary["results"][0].update(final_answer="private-output", timeline=[{
            "type": "agent.tool_result", "processed_at": WHEN, "text": "private-tool-output"}])
        self.assertIn("private-tool-output", render(value, summary))
        self.assertNotIn("private-output", render(value, summary, public=True))
        self.assertNotIn("private-tool-output", render(value, summary, public=True))
        self.assertEqual(redact(b'prefix supersecretvalue suffix', ['supersecretvalue']), b'prefix [REDACTED] suffix')


class NativeEventsTest(unittest.TestCase):
    def test_atif_preserves_tool_link_without_inventing_usage(self):
        reader = NativeConverter("fixture")
        reader.message("user", "input", WHEN, "read a file", "fixture")
        reader.tool("call", WHEN, "read", {"path": "file"}, "fixture")
        reader.result("call", "result", WHEN, "content", "fixture")
        reader.message("assistant", "answer", WHEN, "done", "fixture")
        result = export_atif(reader.projection.events, {"name": "agentscope", "version": "1.0.0"}, "fake", "session")
        step = result["steps"][1]
        self.assertEqual(step["observation"]["results"][0]["source_call_id"], step["tool_calls"][0]["tool_call_id"])
        self.assertNotIn("final_metrics", result)
        self.assertNotIn("metrics", step)

    def test_java_preserves_tool_arguments_and_success_state(self):
        reader = NativeConverter("java")
        values = [
            {"type": "TOOL_CALL_DELTA", "toolCallId": "call", "delta": '{"path":"x"}'},
            {"type": "TOOL_CALL_END", "toolCallId": "call", "toolCallName": "read"},
            {"type": "TOOL_RESULT_TEXT_DELTA", "toolCallId": "call", "delta": "result"},
            {"type": "TOOL_RESULT_END", "toolCallId": "call", "state": "success"},
        ]
        for i, value in enumerate(values):
            reader.java({**value, "id": str(i), "createdAt": WHEN}, i)
        events = reader.projection.events
        self.assertEqual(events[0]["input"], {"path": "x"})
        self.assertFalse(events[1]["is_error"])
        self.assertEqual(events[1]["tool_use_id"], events[0]["id"])

    def test_pi_session_maps_native_tools_without_reasoning_text(self):
        reader = NativeConverter("pi")
        for i, message in enumerate([
            {"role": "user", "content": "read"},
            {"role": "assistant", "content": [{"type": "thinking", "thinking": "private"},
                {"type": "toolCall", "id": "c", "name": "read", "arguments": {}}], "stopReason": "toolUse"},
            {"role": "toolResult", "toolCallId": "c", "content": [{"type": "text", "text": "ok"}]},
            {"role": "assistant", "content": [{"type": "text", "text": "done"}], "stopReason": "stop"},
        ]):
            reader.pi({"id": str(i), "type": "message", "timestamp": WHEN, "message": message}, i)
        self.assertTrue(reader.completed)
        self.assertNotIn("private", json.dumps(reader.projection.events))
        self.assertEqual(reader.projection.summary()["event_counts"]["agent.tool_result"], 1)

    def test_codex_custom_calls_and_completion_keep_source_times(self):
        reader = NativeConverter("codex")
        for i, payload in enumerate([
            {"type": "custom_tool_call", "call_id": "c", "name": "apply_patch", "input": "patch"},
            {"type": "custom_tool_call_output", "call_id": "c", "output": "ok"},
        ]):
            reader.codex({"type": "response_item", "timestamp": WHEN, "payload": payload}, i)
        reader.codex({"type": "event_msg", "timestamp": WHEN, "payload": {"type": "task_complete"}}, 2)
        self.assertTrue(reader.completed)
        self.assertTrue(all(e["processed_at"] == WHEN for e in reader.projection.events))

    def test_conversion_fails_closed_on_missing_native_session(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                convert("codex", [], Path(temporary))


if __name__ == "__main__":
    unittest.main()

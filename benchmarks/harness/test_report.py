# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import base64
import json
import re
import unittest

from report import build_report, render, requested_apis


class ReportTest(unittest.TestCase):
    def test_appworld_api_discovery_does_not_count_documentation_strings(self):
        trace = [{"kind": "tool_request", "value": {"name": "execute_code", "arguments": {
            "code": 'print("apis.fake.doc()")\napis.phone.login()\nfor x in []:\n apis.phone.login()'}}}]
        self.assertEqual(requested_apis(trace), ["phone.login"])

    def fixture(self):
        cases = [{"dataset": "tau", "task_id": str(i), "repeat": 0} for i in range(4)]
        return {"manifest.json": {"cases": cases},
                **{f"cases/tau-{i}-r0.json": {**cases[i], "status": "completed", "score": score}
                   for i, score in enumerate([1, 0, None])}}

    def test_zero_null_and_missing_are_distinct(self):
        objects = self.fixture()
        data = build_report(objects.__getitem__, list(objects))
        self.assertEqual([c["outcome"] for c in data["cases"]],
                         ["passed", "not_passed", "unscored", "missing"])
        self.assertIsNone(data["completed"])

    def test_local_ledger_preserves_initial_state_for_comparison(self):
        objects = self.fixture()
        objects['ledger/tau-0-r0/1.json'] = {'event': 'initialized', 'initial_hash': 'state-hash'}
        data = build_report(objects.__getitem__, list(objects))
        self.assertEqual(data['cases'][0]['initial_hash'], 'state-hash')
        objects['ledger/tau-0-r0/2.json'] = {'event': 'initialized', 'initial_hash': 'different'}
        with self.assertRaisesRegex(ValueError, 'Conflicting initial business state'):
            build_report(objects.__getitem__, list(objects))

    def test_compact_trace_preserves_order_and_instruction_without_reasoning_or_state(self):
        objects = self.fixture()
        prefix = "traces/tau-0-r0/"
        objects.update({prefix + "000001-user_model.json": {
            "request": {"messages": [{"role": "system", "content": "Instruction: Full goal\n\nRules: Simulate"}]},
            "response": {"reasoning_content": "PRIVATE_REASONING", "model": "fixture", "usage": {
                "prompt_tokens": 12, "completion_tokens": 4}}},
            prefix + "000002-initial_business_state.json": {"data": "FULL_DATABASE"},
            prefix + "000003-user_message.json": "Partial goal",
            prefix + "000004-tool_request.json": {"name": "test", "arguments": {"x": 1}},
            prefix + "000005-agent_response.json": {"text": "Done", "trace": ["REPEATED_CONTEXT"]}})
        data = build_report(objects.__getitem__, list(reversed(objects)))
        case = data["cases"][0]
        self.assertEqual(case["instruction"], "Full goal")
        self.assertEqual([e["sequence"] for e in case["timeline"]], ["000003", "000004", "000005"])
        self.assertEqual(case["simulator_usage"]["input_tokens"], 12)
        for excluded in ("PRIVATE_REASONING", "FULL_DATABASE", "REPEATED_CONTEXT"):
            self.assertNotIn(excluded, json.dumps(data))

    def test_dataset_content_cannot_escape_script_element(self):
        data = {"text": '</script><img src=x onerror="alert(1)">中文 & <'}
        html = render(data)
        self.assertNotIn(data["text"], html)
        encoded = re.search(r"atob\('([^']+)'\)", html)[1]
        self.assertEqual(json.loads(base64.b64decode(encoded)), data)

    def test_unexpected_and_duplicate_results_are_rejected(self):
        objects = self.fixture()
        objects["cases/duplicate.json"] = objects["cases/tau-0-r0.json"]
        with self.assertRaises(ValueError):
            build_report(objects.__getitem__, list(objects))
        objects["cases/duplicate.json"] = {"dataset": "tau", "task_id": "99", "repeat": 0}
        with self.assertRaises(ValueError):
            build_report(objects.__getitem__, list(objects))


if __name__ == "__main__":
    unittest.main()

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
from copy import deepcopy
import unittest

from reports.task_results import result_view
from reports.unified import summarize


class ResultViewTest(unittest.TestCase):
    def test_alternative_tool_path_is_separate_and_raw_score_is_immutable(self):
        manifest = {"run_id": "test", "suite": "capabilities", "model": "fake",
                    "agents": [{"name": "a"}], "tasks": ["task"], "rollouts": 1}
        row = {"agent": "a", "task": "task", "rollout": 0, "score": 0.0,
               "execution_status": "completed", "scoring_status": "scored",
               "native_completed": True, "protocol_valid": True, "archive_complete": True,
               "capability_checks": {"0:final_text_contains": True, "1:required_fixture_calls": False}}
        original = summarize(manifest, [row]);before = deepcopy(original)
        summary, metrics, page = result_view(manifest, original)
        self.assertEqual(summary["passed"], 1)
        self.assertEqual(metrics["frozen_strict_by_agent"]["a"]["passed"], 0)
        self.assertEqual(original, before)
        self.assertIn("report-overview.html", page)
        for mutation in ({"verifier_checks": {"file": False}},
                         {"capability_checks": {"0:final_text_contains": False, "1:required_fixture_calls": False}},
                         {"behavior_checks": {"native_compaction_observed": False}},
                         {"native_completed": False}, {"archive_complete": False},
                         {"execution_status": "failed"}, {"execution_status": "adapter_unsupported"},
                         {"score": None, "scoring_status": "unscored"}):
            with self.subTest(mutation=mutation):
                changed = summarize(manifest, [{**row, **mutation}])
                view, _, _ = result_view(manifest, changed)
                self.assertEqual(view["passed"], 0)

    def test_native_delegation_and_judge_failures_are_not_excluded(self):
        manifest = {"run_id": "test", "suite": "capabilities", "model": "fake",
                    "agents": [{"name": "a"}], "tasks": ["task"], "rollouts": 1}
        for kind in ("successful_delegation", "llm_judge", "read_before_memory_write"):
            row = {"agent": "a", "task": "task", "rollout": 0, "score": 0.0,
                   "execution_status": "completed", "scoring_status": "scored",
                   "native_completed": True, "protocol_valid": True, "archive_complete": True,
                   "capability_checks": {"0:" + kind: False, "1:required_fixture_calls": False}}
            self.assertEqual(result_view(manifest, summarize(manifest, [row]))[0]["passed"], 0)

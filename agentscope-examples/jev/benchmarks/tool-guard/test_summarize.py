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

"""Offline accounting regressions: no API keys and no network access."""
import copy
import unittest
from summarize import metrics, paired_difference


def row(backend="jev", actual=False, expected=False, status="DECIDED", family="a"):
    return {"id": family, "repeat": 0, "backend": backend, "family": family,
            "requestSha256": "same-request", "status": status, "reason": "DENY",
            "requests": 1, "returnedModel": "test", "usage": None,
            "gateMillis": 2000 if status == "ERROR" else 10, "pipelineMillis": 2001,
            "judgments": [{"callId": "r", "expectedAllow": expected,
                           "modelAllow": actual, "dispatched": actual is True,
                           "executionCount": int(actual is True)}],
            "dispatchedIds": ["r"] if actual else [], "resultByCallId": {"r": "result"}}


class SummaryTest(unittest.TestCase):
    def test_failure_deny_does_not_count_as_correct_model_answer(self):
        result = metrics([row(actual=None, status="ERROR")])
        self.assertEqual(0, result["modelCorrect"])
        self.assertEqual(0, result["modelValidCalls"])
        self.assertIsNone(result["modelAccuracyOnValid"])
        self.assertEqual(1, result["operationalCorrect"])
        self.assertEqual(2000, result["gateAllP95Millis"])
        self.assertIsNone(result["gateValidP95Millis"])
        self.assertEqual(0, result["usageKnownResponses"])
        self.assertIsNone(result["currencyCost"])

    def test_batch_counts_calls_and_requests_separately(self):
        sample = row()
        other = copy.deepcopy(sample["judgments"][0])
        other.update(callId="r2", expectedAllow=True, modelAllow=False)
        sample["judgments"].append(other)
        sample["resultByCallId"]["r2"] = "DENIED"
        result = metrics([sample])
        self.assertEqual(1, result["requests"])
        self.assertEqual(2, result["protectedCalls"])
        self.assertEqual(.5, result["modelAccuracyOnValid"])
        self.assertEqual(1, result["modelFalseDeny"])
        self.assertEqual(0, result["unpairedSnapshots"])

    def test_compare_requires_matching_inputs_and_all_pairs(self):
        self.assertIsNone(paired_difference([row()]))
        with self.assertRaises(ValueError):
            paired_difference([row(), row("qwen", family="unpaired")])
        b = row("qwen")
        b["requestSha256"] = "different"
        with self.assertRaises(ValueError):
            paired_difference([row(), b])
        delta = paired_difference([row(), row("qwen", actual=True)])
        self.assertEqual(1, delta["difference"])
        self.assertEqual([1, 1], delta["familyBootstrap95"])

    def test_wrongful_allow_and_false_deny_have_separate_denominators(self):
        result = metrics([row(actual=True), row(expected=True)])
        self.assertEqual(1, result["modelUnsafeAllow"])
        self.assertEqual(1, result["modelFalseDeny"])
        self.assertEqual(0, result["balancedAccuracyOnValid"])
        self.assertEqual(1, result["unsafeSimulatedExecutions"])


if __name__ == "__main__":
    unittest.main()

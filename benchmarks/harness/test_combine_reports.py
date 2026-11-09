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
import copy
import unittest

from combine_reports import combine


class CombineReportsTest(unittest.TestCase):
    def report(self, identity, score):
        return {"manifest": {"batch_id": identity, "candidate_sha": "sha", "model": "model",
                             "datasets": {"tau": {"revision": "rev"}}, "images": {}, "appworld_data_hash": "data"},
                "cases": [{"id": identity, "dataset": "tau", "score": score,
                           "outcome": "unscored" if score is None else "passed" if score == 1 else "not_passed"}],
                "completed": {"time": 1, "results_complete": True, "valid_scoring_coverage_complete": score is not None},
                "summary": {}}

    def test_pool_keeps_unscored_denominator(self):
        data = combine([self.report("one", 1), self.report("two", None)])
        stats = data["summary"]["datasets"]["tau"]
        self.assertEqual(stats["end_to_end_success"], .5)
        self.assertEqual(stats["official_success_on_scored"], 1)
        self.assertFalse(data["completed"]["valid_scoring_coverage_complete"])

    def test_incompatible_or_overlapping_batches_are_rejected(self):
        first = self.report("one", 1)
        with self.assertRaises(ValueError):
            combine([first, first])
        for field in ("candidate_sha", "model", "datasets", "images", "appworld_data_hash"):
            second = copy.deepcopy(self.report("two", 1))
            second["manifest"][field] = "different"
            with self.assertRaises(ValueError):
                combine([first, second])


if __name__ == "__main__":
    unittest.main()

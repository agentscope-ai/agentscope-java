# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from compare_reports import compare, render


class ComparisonTest(unittest.TestCase):
    def fixture(self):
        return {"manifest": {"model": "fixture", "datasets": {"appworld": "revision"},
                             "appworld_data_hash": "data"},
                "completed": {"results_complete": True},
                "cases": [{"id": str(i), "dataset": "appworld", "initial_hash": "same",
                           "score": score, "outcome": outcome, "instruction": "<script>unsafe</script>"}
                          for i, score, outcome in [(0, 0, "not_passed"), (1, 1, "passed"), (2, None, "unscored")]]}

    def test_preserves_regressions_and_excludes_unscored_pairs(self):
        before = self.fixture()
        after = copy.deepcopy(before)
        after["cases"][0].update(score=1, outcome="passed")
        after["cases"][1].update(score=0, outcome="not_passed")
        result = compare(before, after)
        self.assertEqual(result["paired"], {"valid_pairs": 2, "before_passed": 1, "after_passed": 1,
                                          "changes": {"improved": 1, "regressed": 1, "unscored": 1}})
        self.assertEqual(result["before"]["scoring_coverage"], 2 / 3)
        self.assertNotIn("<script>unsafe</script>", render(result))

    def test_rejects_different_or_incomplete_comparisons(self):
        before = self.fixture()
        for change in (lambda d: d["manifest"].update(model="different"),
                       lambda d: d["cases"].pop(),
                       lambda d: d["cases"][0].update(initial_hash="different"),
                       lambda d: d["completed"].update(results_complete=False)):
            after = copy.deepcopy(before)
            change(after)
            with self.assertRaises(ValueError):
                compare(before, after)


if __name__ == "__main__":
    unittest.main()

# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import tempfile
import unittest

from sandbox_eval.grading import (ScorerExecutionUnavailable, require_pytest_execution,
                                 require_swe_execution)


class GradingTest(unittest.TestCase):
    def test_missing_test_runner_is_unscored_even_with_raw_reward_zero(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / 'reward.txt').write_text('0')
            with self.assertRaises(ScorerExecutionUnavailable):
                require_pytest_execution(root, 0)
            with self.assertRaises(ScorerExecutionUnavailable):
                require_swe_execution(root, 'example', 0)

    def test_real_test_failure_retains_official_zero(self):
        with tempfile.TemporaryDirectory() as root:
            report = {'results': {'tests': [{'status': 'failed'}],
                      'summary': {'tests': 1, 'passed': 0, 'failed': 1}}}
            (Path(root) / 'ctrf.json').write_text(json.dumps(report))
            self.assertEqual(require_pytest_execution(root, 0)['failed'], 1)
            with self.assertRaises(ScorerExecutionUnavailable):
                require_pytest_execution(root, 1)


if __name__ == '__main__':
    unittest.main()

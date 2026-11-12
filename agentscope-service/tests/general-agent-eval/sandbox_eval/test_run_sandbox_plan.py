# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(importlib.util.find_spec('harbor'), 'Pinned Harbor is installed in the cluster')
class SandboxPlanTest(unittest.TestCase):
    def test_swe_task_requires_accepted_clean_base_and_unchanged_files(self):
        from sandbox_eval.run_terminal import validate_plan
        with tempfile.TemporaryDirectory() as root:
            task = Path(root) / 'example__repo-1'
            task.mkdir()
            instruction = task / 'instruction.md'
            instruction.write_text('Repair this issue')
            entry = {'task': task.name, 'scorer_evidence': 'swe-report',
                     'repository': '/testbed', 'base_commit': 'a' * 40,
                     'files': {'instruction.md': hashlib.sha256(instruction.read_bytes()).hexdigest()},
                     'oracle_acceptance': {'reward': 1}}
            plan = {'version': 1, 'dataset': 'swe-bench-verified', 'tasks': [entry]}
            self.assertEqual(validate_plan(plan, root, 'swe-bench-verified'), [entry])
            with self.assertRaises(ValueError):
                validate_plan(plan, root)  # Terminal entry point cannot admit SWE.
            entry['base_commit'] = 'HEAD'
            with self.assertRaises(ValueError):
                validate_plan(plan, root, 'swe-bench-verified')
            entry['base_commit'] = 'a' * 40
            instruction.write_text('Changed after acceptance')
            with self.assertRaises(ValueError):
                validate_plan(plan, root, 'swe-bench-verified')

    def test_missing_scorer_evidence_never_admits_a_model_attempt(self):
        from sandbox_eval.run_terminal import validate_plan
        with self.assertRaises(ValueError):
            validate_plan({'version': 1, 'dataset': 'terminal-bench-2.1',
                           'tasks': [{'task': 'example', 'oracle_acceptance': {'reward': 1}}]}, '.')


if __name__ == '__main__':
    unittest.main()

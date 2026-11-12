# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest

from seed_environment import validate_members


class SeedEnvironmentTest(unittest.TestCase):
    def test_dependencies_and_immutable_data_are_allowed(self):
        validate_members(['venvs/appworld/bin/python', 'python/bin/python',
                          'sources/appworld/appworld/__init__.py', 'appworld-root/data/tasks/example/dbs/admin.jsonl'])

    def test_credentials_live_ledgers_and_results_are_rejected(self):
        for name in ['evaluation-gateway.sqlite', 'environment-leases.sqlite', 'formal/run/results.jsonl',
                     'appworld-root/experiments/trial', '../config.json', '/run/evaluation/config.json']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_members([name])


if __name__ == '__main__':
    unittest.main()

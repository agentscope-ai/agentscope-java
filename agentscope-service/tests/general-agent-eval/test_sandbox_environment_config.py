# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
from copy import deepcopy
import unittest

from sandbox_eval.environment_config import (comparable_runtime, effective_environment,
                                             resource_overrides, verifier_environment)


class Config(dict):
    def model_copy(self, update):
        return Config({**self, **update})


class EnvironmentConfigTest(unittest.TestCase):
    def test_runtime_comparison_excludes_only_declared_timestamp(self):
        receipt = {'profile': 'swe-requests-httpbin-v1', 'at': 100, 'archive_sha256': 'fixed',
                   'tls_verification': True, 'packages': [{'version': '1', 'sha256': 'package'}]}
        self.assertEqual(comparable_runtime(receipt), comparable_runtime({**receipt, 'at': 200}))
        self.assertEqual(receipt['at'], 100)
        self.assertNotEqual(comparable_runtime(receipt),
                            comparable_runtime({**receipt, 'archive_sha256': 'different'}))
        self.assertEqual(comparable_runtime({'profile': 'unknown', 'at': 100}),
                         {'profile': 'unknown', 'at': 100})

    def test_routing_override_requires_exact_reference_and_preserves_other_parameters(self):
        plan = {'verifier_env': {'HTTPS_PROXY': 'github-only', 'UV_PYTHON': 'pinned'}}
        override = {'HTTPS_PROXY': '', 'https_proxy': ''}
        entry = {'verifier_env': override, 'oracle_acceptance': {'verifier_env': dict(override)}}
        self.assertEqual(verifier_environment(plan, entry),
                         {'HTTPS_PROXY': '', 'https_proxy': '', 'UV_PYTHON': 'pinned'})
        self.assertEqual(plan['verifier_env']['HTTPS_PROXY'], 'github-only')
        entry['oracle_acceptance']['verifier_env']['HTTPS_PROXY'] = 'other'
        with self.assertRaises(ValueError):
            verifier_environment(plan, entry)
        for unsafe in [{'PYTHONHTTPSVERIFY': '0'}, {'HTTPBIN_URL': 'http://replacement'},
                       {'HTTPS_PROXY': False}]:
            with self.assertRaises(ValueError):
                verifier_environment(plan, {'verifier_env': unsafe,
                    'oracle_acceptance': {'verifier_env': unsafe}})

    def test_cpu_acceptance_used_for_model_and_clean_grader_without_mutating_task(self):
        original = Config(cpus=1, memory_mb=4096, workdir='/old')
        entry = {'image': 'pinned', 'repository': '/testbed', 'resource_overrides': {'cpus': 2},
                 'oracle_acceptance': {'reward': 1, 'resource_overrides': {'cpus': 2}}}
        model = effective_environment(original, entry, True)
        grader = effective_environment(original, entry, True)
        self.assertEqual(model, grader)
        self.assertEqual(model['cpus'], 2)
        self.assertEqual(model['workdir'], '/testbed')
        self.assertEqual(original, {'cpus': 1, 'memory_mb': 4096, 'workdir': '/old'})

    def test_unaccepted_resource_or_nonresource_override_is_rejected(self):
        baseline = {'oracle_acceptance': {'reward': 1}}
        for overrides in [{'cpus': 2}, {'cpus': 0}, {'cpus': True}, {'cpus': 1.5},
                          {'env': {'ANSWER': 'leaked'}}, {'docker_image': 'other'}, []]:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                resource_overrides({**deepcopy(baseline), 'resource_overrides': overrides})
        with self.assertRaises(ValueError):
            resource_overrides({'oracle_acceptance': {'reward': 1, 'resource_overrides': {'cpus': 2}}})
        self.assertEqual(resource_overrides(baseline), {})


if __name__ == '__main__':
    unittest.main()

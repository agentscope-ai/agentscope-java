# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Check scorer execution evidence without changing the official score rule."""
import json
from pathlib import Path


class ScorerExecutionUnavailable(RuntimeError):
    pass


def require_pytest_execution(directory, reward):
    """The selected Terminal smoke tasks emit CTRF after executing pytest.

    Their shell scripts can write reward=0 even when a missing dependency prevents
    pytest from starting. Such a reward is retained as a raw artifact, but is not
    a valid task-test score. Apply this contract only to tasks declaring CTRF.
    """
    try:
        report = json.loads((Path(directory) / 'ctrf.json').read_text())['results']
        summary = report['summary']
        count = summary['tests']
        if type(count) is not int or count <= 0 or len(report['tests']) != count:
            raise ValueError('No executed test records')
        counts = [summary.get(key, 0) for key in ['passed', 'failed', 'skipped', 'pending', 'other']]
        if any(type(n) is not int or n < 0 for n in counts) or sum(counts) != count:
            raise ValueError('Incomplete test summary')
        if reward not in {0, 1} or reward == 1 and summary['failed'] != 0:
            raise ValueError('Score and test evidence conflict')
        return {'tests': count, 'passed': summary['passed'], 'failed': summary['failed']}
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ScorerExecutionUnavailable('Official pytest execution is not established') from error


def require_swe_execution(directory, instance, reward):
    try:
        report = json.loads((Path(directory) / 'report.json').read_text())[instance]
        tests = report['tests_status']
        if not tests or not report['patch_successfully_applied']:
            raise ValueError('Official SWE parser did not find test execution')
        if reward not in {0, 1} or bool(reward) != report['resolved']:
            raise ValueError('Score and official resolved status conflict')
        return tests
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ScorerExecutionUnavailable('Official SWE test parsing is not established') from error

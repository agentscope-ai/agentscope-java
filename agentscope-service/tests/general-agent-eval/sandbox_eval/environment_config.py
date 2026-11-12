# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Explicit, reference-accepted resource overrides without editing task files."""


def comparable_runtime(receipt):
    """Exclude only the known preparation timestamp, not environment contents.

    The full receipt remains in runtime-preparation.json. Package, trust, version
    and routing fields still participate in the cross-Harness initial-state hash.
    """
    if receipt.get('profile') in {'swe-requests-httpbin-v1', 'swe-requests-httpbin-py27-v1',
                                 'swe-requests-vendored-ca-v1'}:
        return {key: value for key, value in receipt.items() if key != 'at'}
    return dict(receipt)


def resource_overrides(entry):
    overrides = entry.get('resource_overrides', {})
    if not isinstance(overrides, dict) or set(overrides) - {'cpus'}:
        raise ValueError('Only an explicitly accepted CPU override is supported')
    if 'cpus' in overrides and (type(overrides['cpus']) is not int or overrides['cpus'] <= 0):
        raise ValueError('CPU override must be a positive integer')
    accepted = entry['oracle_acceptance'].get('resource_overrides', {})
    if overrides != accepted:
        raise ValueError('Task resources differ from reference acceptance')
    return dict(overrides)


def effective_environment(task_config, entry, is_swe):
    updates = {'docker_image': entry['image'], **resource_overrides(entry)}
    if is_swe:
        updates['workdir'] = entry['repository']
    return task_config.model_copy(update=updates)


def verifier_environment(plan, entry):
    overrides = entry.get('verifier_env', {})
    accepted = entry['oracle_acceptance'].get('verifier_env', {})
    allowed = {'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
               'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy',
               'UV_PYTHON', 'UV_PYTHON_DOWNLOADS', 'UV_INDEX_URL'}
    if (not isinstance(overrides, dict) or set(overrides) - allowed or overrides != accepted
            or any(not isinstance(v, str) for v in overrides.values())):
        raise ValueError('Verifier routing overrides must match reference acceptance')
    return {**plan.get('verifier_env', {}), **overrides}

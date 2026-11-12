# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Shared, explicit evaluation deployment contract."""
import copy


def model_catalog(profile, target, gateway_base=None):
    bindings = copy.deepcopy(profile['brain']['model']['bindings'])
    if gateway_base:
        for binding in bindings.values():
            binding['baseUrl'] = gateway_base.rstrip('/') + '/models/' + target['name'] + '/v1'
    return bindings


def runtime_environment(evaluation, target):
    settings = evaluation['deployment']
    mode = 'short' if evaluation['mode'] == 'aligned-short-context' else 'native'
    return {'agent-harness-runtime': {
                'MANAGED_AGENTS_EVALUATION_CONTEXT_MODE': mode,
                'MANAGED_AGENTS_EVALUATION_TOOL_ENVELOPE': 'text-json-v1',
                'MANAGED_AGENTS_EVALUATION_TOOL_SURFACE': settings['toolSurface'],
                'MANAGED_AGENTS_RUNTIME_CONCURRENCY' if target['name'] == 'agentscope' else 'BRAIN_CONCURRENCY':
                    str(settings['runtimeConcurrency'])},
            'agent-service': {'MANAGED_AGENTS_PROJECTION_CONCURRENCY': str(settings['projectionConcurrency']),
                              'MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED': 'true',
                              'MANAGED_AGENTS_RUNTIME_PROCESS_OWNERSHIP': 'true',
                              'MANAGED_AGENTS_AGENT_SERVICE_MAX_SESSION_QUEUED': '1000'},
            'tool-worker': {'MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED': 'true',
                            'MANAGED_AGENTS_RUNTIME_PROCESS_OWNERSHIP': 'true',
                            'MANAGED_AGENTS_RUNTIME_TOOL_CONCURRENCY': str(settings['toolConcurrency']),
                            'MANAGED_AGENTS_RUNTIME_TOOL_CONTROL_CONCURRENCY': str(settings['toolControlConcurrency'])}}


def helm_overlay(model, evaluation, target, gateway_base, release):
    """Render the same contract for a subsequent Helm upgrade (no credentials)."""
    settings = evaluation['deployment']
    result = copy.deepcopy(model)
    result['evaluation'] = {'contextMode': 'short' if evaluation['mode'] == 'aligned-short-context' else 'native',
                            'toolEnvelope': 'text-json-v1',
                            'toolSurface': settings['toolSurface'],
                            'initResources': {'requests': settings['initResources'], 'limits': settings['initResources']}}
    result['runtime'] = {'sessionQueue': {'enabled': True}, 'processOwnership': True}
    result['brain']['type'] = 'agentscope-java' if target['name'] == 'agentscope' else target['name']
    result['brain']['model']['bindings'] = model_catalog(model, target, gateway_base)
    result['brain']['pi'] = {'concurrency': settings['runtimeConcurrency']}
    result['brain']['codex'] = {'concurrency': settings['runtimeConcurrency']}
    for component, resource in settings['resources'].items():
        result.setdefault(component, {}).update(replicas=settings['replicas'],
            resources={'requests': resource, 'limits': resource}, image=release[target['namespace'] + '/' + component]['image'])
    result['agent-harness-runtime']['concurrency'] = settings['runtimeConcurrency']
    result['agent-service']['projection'] = {'concurrency': settings['projectionConcurrency']}
    result['tool-worker'].update(toolConcurrency=settings['toolConcurrency'],
                                 toolControlConcurrency=settings['toolControlConcurrency'])
    return result

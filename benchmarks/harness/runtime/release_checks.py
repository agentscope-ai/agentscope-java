# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Release tool assertions use controller-owned receipts, never candidate files."""
import json


def check_calls(scenario, receipts):
    expected = scenario.get('trusted_calls')
    if expected is None:
        return {}
    calls = [r for r in receipts if r['name'] == 'operate']
    def exact(actual, value):
        # bool is not interchangeable with int in the argument contract.
        return json.dumps(actual, sort_keys=True) == json.dumps(value, sort_keys=True)
    ok = len(calls) == len(expected)
    for index, want in enumerate(expected):
        if index >= len(calls):
            ok = False
            continue
        call = calls[index]
        args = call['arguments']
        if want['op'] == 'commit':
            ticket = calls[0].get('result', {}).get('ticket')
            want = {**want, 'ticket': ticket}
            ok &= bool(ticket)
        ok &= exact(args, want)
        intended_error = want == {'op': 'validate', 'schema_version': 1}
        ok &= ('error' in call) == intended_error
    return {'trusted_tool_state_and_arguments': bool(ok)}

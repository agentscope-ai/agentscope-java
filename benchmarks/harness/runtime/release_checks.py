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

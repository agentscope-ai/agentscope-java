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
"""Common, predeclared capability denominator; invalid runs are never filtered away."""
import html
import json
from runtime.release import valid_row
from reports.unified import summarize


def compare_agents(manifest, summary):
    declared = manifest['evaluation_contract']['identity']['tasks']
    common = sorted(task for task, spec in declared.items() if not any(spec['unsupported'].values()))
    rows = summarize(manifest, summary['results'])['results']
    result = {'run_id': manifest['run_id'], 'model': manifest['model'], 'profiles': manifest['agents'],
        'model_snapshot_locked': manifest.get('model_snapshot_locked', False), 'common_tasks': common,
        'note': 'Descriptive native-profile comparison. API, prompts, tool surfaces and token budgets differ. No product ranking or statistical significance claim.',
        'agents': {}, 'excluded_tasks': {task: spec['unsupported'] for task, spec in declared.items() if task not in common}}
    for agent in manifest['agents']:
        items = [r for r in rows if r['agent'] == agent['name'] and r['task'] in common]
        passed = sum(valid_row(r) and r['score'] == 1 for r in items)
        result['agents'][agent['name']] = {'planned_common': len(items), 'passed_common': passed,
            'valid_common': sum(valid_row(r) for r in items),
            'pass_rate_common_planned': passed/len(items) if items else None,
            'per_task': {task: [{k: r.get(k) for k in ('rollout','score','execution_status','scoring_status','native_completed')}
                for r in items if r['task'] == task] for task in common}}
    return result


def render_comparison(value):
    return ('<!doctype html><meta charset="utf-8"><title>Harness comparison</title>'
        '<style>body{font:16px system-ui;margin:32px}pre{white-space:pre-wrap}</style><h1>Harness comparison</h1>'
        '<p><a href="report.html">Per-trial evidence</a></p><pre>' + html.escape(json.dumps(value, ensure_ascii=False, indent=2)) + '</pre>')

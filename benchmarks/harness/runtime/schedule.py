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
"""Cluster preflight barrier; each planned cell is executed at most once."""
import asyncio
from reports.unified import trial_keys
from runtime.release import valid_row


async def run_schedule(config, execute, record_preflight):
    selected = trial_keys(config)
    preflight = {(a['name'], task, 0) for a in config['agents']
                 for task in config.get('release', {}).get('preflight_tasks', [])}
    rows = []
    if preflight:
        if not preflight.issubset(selected):
            raise ValueError('Preflight is outside the planned matrix')
        rows = await asyncio.gather(*(execute(a, task, 0) for a in config['agents']
            for task in config['release']['preflight_tasks']), return_exceptions=True)
        passed = all(isinstance(r, dict) and valid_row(r) and r['score'] == 1 for r in rows)
        await record_preflight({'passed': passed, 'selected': sorted(preflight)})
        if not passed:
            return rows
    remaining = await asyncio.gather(*(execute(a, task, rollout) for task in config['tasks']
        for a in config['agents'] for rollout in range(config['rollouts'])
        if (a['name'], task, rollout) in selected - preflight), return_exceptions=True)
    return rows + remaining

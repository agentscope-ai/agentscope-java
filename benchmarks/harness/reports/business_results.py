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
"""Offline acceptance for the frozen full business matrix, without model calls."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import html
import json
from pathlib import Path
from statistics import median

from runtime.release import valid_row

AGENTS = ('agentscope', 'codex', 'pi')


def expected_tasks(inventory):
    return {**{f'tau-{domain}-{i}': 'tau-' + domain
               for domain, ids in inventory['tau'].items() for i in ids},
            **{'appworld-' + i: 'appworld-' + split
               for split, ids in inventory['appworld'].items() for i in ids}}


def evaluate(inventory, runs):
    tasks = expected_tasks(inventory)
    actual = {}
    sources = []
    identities = []
    for manifest, summary in runs:
        if manifest['rollouts'] != 1 or set(a['name'] for a in manifest['agents']) != set(AGENTS):
            raise ValueError('Expected exactly three profiles and one rollout')
        if manifest.get('selected_trials'):
            raise ValueError('Full business acceptance cannot silently accept a partial selection')
        sources.append(manifest['run_id'])
        identities.append({k: manifest.get(k) for k in ('candidate', 'model', 'model_base_url', 'agents', 'business_image')})
        for row in summary['results']:
            key = (row['agent'], row['task'])
            if key in actual or row['task'] not in tasks or row['agent'] not in AGENTS or row['rollout'] != 0:
                raise ValueError('Duplicate or unexpected trial')
            if row['task'] not in manifest['tasks']:
                raise ValueError('Trial outside its frozen run')
            actual[key] = {**row, 'source_run': manifest['run_id']}
    if any(i != identities[0] for i in identities[1:]):
        raise ValueError('Candidate, model, profiles or business image differ across runs')
    rows = [actual.get((agent, task), {'agent': agent, 'task': task, 'score': None,
             'execution_status': 'missing'}) for agent in AGENTS for task in tasks]
    groups = []
    scenarios = []
    for agent in AGENTS:
        for split in sorted(set(tasks.values())):
            items = [r for r in rows if r['agent'] == agent and tasks[r['task']] == split]
            valid = [r for r in items if valid_row(r)]
            passed = sum(r['score'] == 1 for r in valid)
            duration = [(datetime.fromisoformat(r['finished_at']) - datetime.fromisoformat(r['started_at'])).total_seconds()
                        for r in items if r.get('finished_at') and r.get('started_at')]
            groups.append({'agent': agent, 'split': split, 'planned': len(items),
                'attempted': sum(r['execution_status'] != 'missing' for r in items),
                'valid_scored': len(valid), 'passed': passed, 'official_zero': sum(r['score'] == 0 for r in valid),
                'invalid_or_unscored': len(items) - len(valid),
                'pass_rate_planned': passed / len(items),
                'pass_rate_scored': passed / len(valid) if valid else None,
                'median_wall_seconds': median(duration) if duration else None,
                'unscored_reasons': dict(Counter(r.get('unscored_reason') or r.get('error_type') or r['execution_status']
                                                for r in items if not valid_row(r)))})
            if split.startswith('appworld-'):
                families = defaultdict(list)
                for r in items:
                    families[r['task'].rsplit('_', 1)[0]].append(r)
                if any(len(v) != 3 for v in families.values()):
                    raise ValueError('AppWorld scenario does not contain its three variants')
                success = sum(all(valid_row(r) and r['score'] == 1 for r in v) for v in families.values())
                scenarios.append({'agent': agent, 'split': split, 'planned_scenarios': len(families),
                                  'all_three_passed': success, 'scenario_pass_rate_planned': success / len(families)})
    paired = []
    for left, right in (('agentscope', 'codex'), ('agentscope', 'pi'), ('codex', 'pi')):
        for split in sorted(set(tasks.values())):
            counts = Counter()
            for task in tasks:
                if tasks[task] != split:
                    continue
                a, b = actual.get((left, task), {}), actual.get((right, task), {})
                if not valid_row(a) or not valid_row(b):
                    counts['either_invalid_or_unscored'] += 1
                else:
                    counts['both_pass' if a['score'] == b['score'] == 1 else
                           'both_zero' if a['score'] == b['score'] == 0 else
                           'left_only_pass' if a['score'] == 1 else 'right_only_pass'] += 1
            paired.append({'left': left, 'right': right, 'split': split, **counts})
    return {'sources': sources, 'planned': len(rows), 'observed': len(actual),
            'missing': sum(r['execution_status'] == 'missing' for r in rows),
            'all_trials_valid_scored': all(valid_row(r) for r in rows),
            'groups': groups, 'appworld_scenarios': scenarios, 'paired': paired,
            'note': 'Single-rollout native-profile comparison; no ranking or stable pass^k claim.', 'results': rows}


def render(value):
    headings = ('Harness', '分区', '计划', '有效评分', '通过', '官方零分', '无效/未评分', '通过/计划')
    table = []
    for g in value['groups']:
        cells = [g['agent'], g['split'], g['planned'], g['valid_scored'], g['passed'], g['official_zero'],
                 g['invalid_or_unscored'], f"{100*g['pass_rate_planned']:.1f}%"]
        table.append('<tr>' + ''.join('<td>' + html.escape(str(c)) + '</td>' for c in cells) + '</tr>')
    return ('<!doctype html><meta charset="utf-8"><title>Business full acceptance</title>'
            '<style>body{font:16px system-ui;margin:32px}table{border-collapse:collapse}td,th{padding:9px;border:1px solid #ccc}pre{white-space:pre-wrap}</style>'
            '<h1>AppWorld / τ-bench 全量验收</h1><p>逐题详情与轨迹见各运行的离线报告。单轮对比不能作为稳定排名。</p>'
            '<table><tr>' + ''.join('<th>' + h + '</th>' for h in headings) + '</tr>' + ''.join(table) + '</table>'
            '<h2>AppWorld scenario（三个变体全部通过）</h2><pre>' + html.escape(json.dumps(value['appworld_scenarios'], indent=2)) + '</pre>'
            '<h2>同题配对结果</h2><pre>' + html.escape(json.dumps(value['paired'], indent=2)) + '</pre>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', type=Path, nargs='+', help='Directories with manifest.json and summary.json')
    parser.add_argument('--inventory', type=Path, default=Path(__file__).resolve().parents[1] / 'external/business/inventory.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    runs = [(json.loads((d / 'manifest.json').read_text()), json.loads((d / 'summary.json').read_text())) for d in args.runs]
    value = evaluate(json.loads(args.inventory.read_text()), runs)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'business-acceptance.json').write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    (args.output / 'business-acceptance.html').write_text(render(value))
    print(json.dumps({k: value[k] for k in ('planned', 'observed', 'missing', 'all_trials_valid_scored')}))


if __name__ == '__main__':
    main()

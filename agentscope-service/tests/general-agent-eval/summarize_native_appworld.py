# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Publish sanitized first-attempt results, preserving pending and unscored states."""
import argparse
from collections import Counter
import json
from pathlib import Path
import statistics


TARGETS = ['agentscope', 'pi', 'codex']


def summarize(lanes):
    if sorted(lane['target'] for lane in lanes) != sorted(TARGETS):
        raise ValueError('Exactly the three expected lanes are required')
    tasks = lanes[0]['manifest']['tasks']
    if len(tasks) != len(set(tasks)):
        raise ValueError('Duplicate planned task')
    output = {'benchmark': 'AppWorld', 'batch': 'native-business-20261103-r2',
              'mode': 'native-context-first-attempt', 'planned': len(tasks) * 3,
              'tasks': tasks, 'instructions': {}, 'rows': [], 'summary': {},
              'compaction': 'Not established by this summary; cumulative tokens are not context length.',
              'limitations': ['Development subset, not the full official benchmark.',
                  'Runtime rollout interference occurred during this batch; elapsed times are not controlled throughput results.',
                  'No scores or usage are imputed for missing observations; selective retries are excluded.']}
    for lane in lanes:
        target, manifest, rows = lane['target'], lane['manifest'], lane['rows']
        if manifest['tasks'] != tasks or manifest['repeats'] != 1:
            raise ValueError('Task list or repeat count differs')
        if [row['task'] for row in rows] != tasks[:len(rows)] or any(
                row['target'] != target or row['repeat'] != 0 for row in rows):
            raise ValueError('Duplicate, reordered or out-of-plan result')
        if any(row.get('reward') not in [None, 0, 1] for row in rows):
            raise ValueError('Unexpected official score')
        for task, instruction in lane['instructions'].items():
            if task in output['instructions'] and output['instructions'][task] != instruction:
                raise ValueError('Task instruction differs across lanes')
            output['instructions'][task] = instruction
        for original in rows:
            row = dict(original)
            if row['trial'] == 'agentscope-6171bbc_1-0-f8bd9ff3':
                category = 'evaluation_orchestrator_startup'
            elif row['reward'] == 0:
                category = 'official_task_failed'
            elif row['status'] == 'completed' and row['reward'] == 1:
                category = None
            elif 'usage_unavailable' in (row.get('error') or ''):
                category = 'infrastructure_usage_unavailable'
            elif row['status'] == 'budget_exceeded':
                category = 'budget_' + ('model_calls' if 'max_model_calls' in (row.get('error') or '') else 'deadline')
            else:
                category = row['status']
            row['failure_category'] = category
            row['provider_tokens_are_lower_bound'] = row['provider_usage_missing'] > 0
            output['rows'].append(row)
        counts = Counter(row['failure_category'] for row in output['rows'] if row['target'] == target
                         and row['failure_category'] is not None)
        scored = sum(row['reward'] is not None for row in rows)
        success = sum(row['reward'] == 1 and row['status'] == 'completed' for row in rows)
        output['summary'][target] = {'attempted': len(rows), 'planned': len(tasks),
            'official_scored': scored, 'official_passes': sum(row['reward'] == 1 for row in rows),
            'official_zeros': sum(row['reward'] == 0 for row in rows),
            'unscored': len(rows) - scored, 'pending': len(tasks) - len(rows),
            'scoring_coverage_of_attempts': scored / len(rows) if rows else None,
            'end_to_end_success_of_attempts': success / len(rows) if rows else None,
            'total_elapsed_seconds': sum(row['elapsed_seconds'] for row in rows),
            'median_elapsed_seconds': statistics.median(row['elapsed_seconds'] for row in rows) if rows else None,
            'provider_known_tokens': sum(row['provider_known_tokens'] for row in rows),
            'provider_usage_missing_calls': sum(row['provider_usage_missing'] for row in rows),
            'failure_categories': dict(counts), 'active': lane['active'], 'pending_requests': lane['pending'],
            'held_leases': lane['leases'], 'captured_at': lane['captured_at']}
    output['complete'] = all(value['attempted'] == value['planned'] and not value['active']
                             and not value['pending_requests'] and not value['held_leases']
                             for value in output['summary'].values())
    output['configuration_comparison'] = {key: sorted(set(lane['manifest'][key] for lane in lanes))
        for key in ['commit', 'model', 'model_profile_sha256', 'evaluation_profile_sha256']}
    output['task_comparison'] = {}
    for task in tasks:
        rows = [row for row in output['rows'] if row['task'] == task]
        output['task_comparison'][task] = {key: {'values': sorted(set(row[key] for row in rows if row.get(key))),
            'observed_lanes': sum(bool(row.get(key)) for row in rows)}
            for key in ['initial_hash', 'definition_sha256', 'task_sha256']}
    return output


def markdown(report):
    lines = ['# AppWorld 新题首轮评分', '',
        '**' + ('首轮已全部尝试，现场已收尾。' if report['complete'] else '运行中快照，尚非最终结果。') + '**', '',
        '每题每个 Harness 首轮一次。1 = 官方任务测试全部通过；0 = 已完成官方评分但未全部通过；'
        '未评分 = 执行或评分链路异常，没有有效官方分数；待完成 = 本快照尚无终态结果。原失败不被补跑覆盖。', '',
        '| Harness | 已尝试/计划 | 官方 1 | 官方 0 | 未评分 | 有效评分覆盖（已尝试） | 端到端成功率（已尝试） |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for target in TARGETS:
        s = report['summary'][target]
        pct = lambda value: '—' if value is None else f'{value:.1%}'
        lines.append(f"| {target} | {s['attempted']}/{s['planned']} | {s['official_passes']} | {s['official_zeros']} | {s['unscored']} | {pct(s['scoring_coverage_of_attempts'])} | {pct(s['end_to_end_success_of_attempts'])} |")
    lines += ['', '## 每题评分与任务', '', '| Task ID | 任务原文 | AgentScope | PI | Codex |', '| --- | --- | --- | --- | --- |']
    for task in report['tasks']:
        cells = []
        for target in TARGETS:
            row = next((r for r in report['rows'] if r['task'] == task and r['target'] == target), None)
            cells.append('待完成' if row is None else '未评分' if row['reward'] is None else str(row['reward']))
        instruction = report['instructions'].get(task, '任务原文待归档').replace('|', '\\|').replace('\n', ' ')
        lines.append('| `' + task + '` | ' + instruction + ' | ' + ' | '.join(cells) + ' |')
    lines += ['', '## 工具、调用与消耗', '',
        'M/T 表示已观察模型请求/业务工具调用；循环表示模型产生工具调用的可观察轮次，不代表内部 reasoning 步骤。'
        '累计 token 与单次输入峰值分列；缺失用量时累计值只是已知下界。原生压缩是否触发仍需事件证据，不能按累计 token 推断。', '',
        '| Task | Harness | 可用工具数 | M/T | 工具循环 | 耗时秒 | 已知 token | 缺失用量请求 | 输入峰值 token | 失败分类 |',
        '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    for task in report['tasks']:
        for target in TARGETS:
            row = next((r for r in report['rows'] if r['task'] == task and r['target'] == target), None)
            if row is None:
                continue
            r = row
            lines.append(f"| `{task}` | {target} | {r['catalog_tool_count'] if r['catalog_tool_count'] is not None else '未加载'} | {r['model_calls']}/{r['tool_calls']} | {r['model_tool_cycles']} | {r['elapsed_seconds']:.1f} | {r['provider_known_tokens']} | {r['provider_usage_missing']} | {r['peak_input_tokens'] if r['peak_input_tokens'] is not None else '—'} | {r['failure_category'] or '—'} |")
    lines += ['', 'AgentScope `6171bbc_1` 是评测续跑脚本在创建 Session 前的启动错误；与 Harness 能力失败分列。'
        '`usage_unavailable` 项涉及平台用量/连接异常，部署变更时间重叠的证据另见验收记录；不得改成官方 0。', '',
        '[机器可读数据与哈希比较](report.json) · [部署、任务与异常记录](../README.md)', '',
        '本报告是开发子集结果，不是完整官方榜单。运行中包含部署干扰，不作受控并发性能排名。', '']
    return '\n'.join(lines)


def markdown_en(report):
    lines = ['# AppWorld first-attempt scores', '',
             '**' + ('All first attempts and cleanup are complete.' if report['complete'] else
                       'In-progress snapshot, not final results.') + '**', '',
             'One first attempt per task and Harness. 1 means all official task tests passed; '
             '0 means official grading completed without all tests passing. Unscored means no valid '
             'official score; Pending means this snapshot has no terminal result. Retries never replace originals.', '']
    for line in markdown(report).splitlines():
        if not line.startswith('|'):
            continue
        if line.startswith('| Harness |'):
            line = '| Harness | Attempted/planned | Official 1 | Official 0 | Unscored | Scoring coverage of attempts | End-to-end success of attempts |'
        elif line.startswith('| Task ID |'):
            lines += ['', '## Task instructions and scores', '']
            line = '| Task ID | Official instruction | AgentScope | PI | Codex |'
        elif line.startswith('| Task |'):
            lines += ['', '## Tools, calls and usage', '']
            line = '| Task | Harness | Tools available | Model/tool calls | Tool cycles | Seconds | Known tokens | Missing usage calls | Peak input tokens | Failure category |'
        lines.append(line.replace('未评分', 'Unscored').replace('待完成', 'Pending').replace('未加载', 'Not loaded'))
    lines += ['', 'Cycles count observable model requests producing tool calls, not hidden reasoning steps. '
        'Known tokens are a lower bound when usage is missing. Cumulative tokens do not measure context length '
        'or demonstrate compaction; event evidence is still required.', '',
        'AgentScope 6171bbc_1 failed in our continuation script before Session creation. This is evaluation '
        'orchestration failure, not Harness capability failure. usage_unavailable denotes accounting/transport '
        'failure; overlapping deployment evidence is documented separately. These cases are not official zeros.', '',
        '[Machine-readable results and hash comparison](report.json) · [Acceptance and incidents](../README.en.md)', '',
        'Development subset, not a complete official leaderboard or controlled concurrency performance ranking.', '']
    return '\n'.join(lines)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    report = summarize(json.loads(Path(args.snapshot).read_text()))
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    (output / 'cases.md').write_text(markdown(report))
    (output / 'cases.en.md').write_text(markdown_en(report))

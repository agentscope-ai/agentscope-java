# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Read-only cgroup and Kubernetes evidence before/after an evaluation batch."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import time


# Kernel-documented cumulative counters only. Vendor extensions such as
# current_bw may decrease normally; preserve them as snapshots, not resets.
COUNTER_KEYS = {
    'cpu.stat': {'nr_periods', 'nr_throttled', 'throttled_usec', 'nr_bursts',
                 'burst_time', 'burst_usec', 'usage_usec', 'user_usec', 'system_usec'},
    'memory.events': {'low', 'high', 'max', 'oom', 'oom_kill', 'oom_group_kill', 'sock_throttled'},
}


def parse_cgroup(output):
    section, values = None, {}
    for line in output.splitlines():
        if line in {'cpu.stat', 'memory.events', 'memory.current', 'memory.failcnt', 'memory.oom_control'}:
            section = line
            values[section] = {}
        elif section:
            fields = line.split()
            if len(fields) == 2 and fields[1].isdigit():
                values[section][fields[0]] = int(fields[1])
            elif len(fields) == 1 and fields[0].isdigit():
                values[section]['value'] = int(fields[0])
    cpu = values.get('cpu.stat', {})
    if 'throttled_time' in cpu:
        # cgroup v1 reports nanoseconds; v2 reports microseconds.
        cpu['throttled_usec'] = cpu.pop('throttled_time') // 1000
    if 'memory.oom_control' in values:
        events = values.setdefault('memory.events', {})
        oom = values.pop('memory.oom_control')
        if 'oom_kill' in oom:
            events['oom_kill'] = oom['oom_kill']
    if 'memory.failcnt' in values:
        count = values.pop('memory.failcnt').get('value')
        if count is not None:
            values.setdefault('memory.events', {})['max'] = count
    return values


def capture(kubeconfig, targets):
    command = ['kubectl', '--kubeconfig', kubeconfig, '--request-timeout=20s']
    rows = []
    for target in targets:
        pods = json.loads(subprocess.check_output(command + ['-n', target['namespace'], 'get', 'pods', '-o', 'json']))['items']
        for pod in pods:
            if not pod['metadata']['name'].startswith('ma-managed-agents-'):
                continue
            for container in pod.get('status', {}).get('containerStatuses', []):
                rows.append({'target': target['name'], 'namespace': target['namespace'],
                    'pod': pod['metadata']['name'], 'uid': pod['metadata']['uid'],
                    'container': container['name'], 'restarts': container['restartCount'],
                    'state': container.get('state'), 'last_state': container.get('lastState')})
    def read(row):
        script = '''if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
for f in cpu.stat memory.events memory.current; do echo "$f"; cat "/sys/fs/cgroup/$f"; done
else
echo cpu.stat; cat /sys/fs/cgroup/cpu/cpu.stat
echo memory.oom_control; cat /sys/fs/cgroup/memory/memory.oom_control
echo memory.failcnt; cat /sys/fs/cgroup/memory/memory.failcnt
echo memory.current; cat /sys/fs/cgroup/memory/memory.usage_in_bytes
fi'''
        result = subprocess.run(command + ['-n', row['namespace'], 'exec', row['pod'], '-c', row['container'],
                                          '--', 'sh', '-c', script], capture_output=True, text=True, timeout=30)
        if result.returncode:
            row['cgroup_unavailable'] = True
        else:
            row['cgroup_version'] = 1 if 'memory.oom_control' in result.stdout else 2
            row['cgroup'] = parse_cgroup(result.stdout)
        return row
    with ThreadPoolExecutor(max_workers=4) as pool:
        return {'captured_at': time.time(), 'containers': list(pool.map(read, rows))}


def compare(before, after):
    old = {(r['uid'], r['container']): r for r in before['containers']}
    result = []
    for row in after['containers']:
        item = {k: row[k] for k in ['target', 'pod', 'container']}
        prior = old.get((row['uid'], row['container']))
        if not prior:
            item['replaced_or_new'] = True
        else:
            item['restart_delta'] = row['restarts'] - prior['restarts']
            for section in ['cpu.stat', 'memory.events']:
                left, right = prior.get('cgroup', {}).get(section), row.get('cgroup', {}).get(section)
                if not left or not right:
                    item['cgroup_unavailable'] = True
                    continue
                item[section + '_delta'] = {k: value - left[k] for k, value in right.items()
                                            if k in left and k in COUNTER_KEYS[section]}
                other = (left.keys() | right.keys()) - COUNTER_KEYS[section]
                if other:
                    item[section + '_unclassified'] = {
                        k: {'before': left.get(k), 'after': right.get(k)} for k in sorted(other)}
                if not item[section + '_delta']:
                    item['cgroup_unavailable'] = True
                if any(v < 0 for v in item[section + '_delta'].values()):
                    item['counter_reset'] = True
        result.append(item)
    return {'seconds': after['captured_at'] - before['captured_at'], 'containers': result,
            'note': 'Counters cover all workload activity in this interval. Replacement, unavailable or reset counters prevent a no-throttling claim.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--before')
    args = parser.parse_args()
    snapshot = capture(args.kubeconfig, json.loads(Path(args.config).read_text())['targets'])
    if args.before:
        snapshot['comparison'] = compare(json.loads(Path(args.before).read_text()), snapshot)
    with Path(args.output).open('x') as stream:
        json.dump(snapshot, stream, indent=2)

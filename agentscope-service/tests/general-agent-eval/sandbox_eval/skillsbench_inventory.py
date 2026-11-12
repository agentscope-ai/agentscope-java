# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Inventory an immutable SkillsBench release without admitting model trials.

Use the official BenchFlow Task parser. Registry content hashes are checked
before reading task configuration; a preparation manifest is not an accepted
execution plan. Reference acceptance and image/skill-mode evidence come later.
"""
import argparse
import hashlib
import json
from pathlib import Path


def task_digest(root):
    root = Path(root)
    digest = hashlib.sha256()
    files = {}
    for path in sorted(root.rglob('*'), key=lambda p: p.relative_to(root).as_posix()):
        if path.is_symlink():
            raise ValueError('Symlink requires explicit task packaging review: ' + str(path))
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        value = hashlib.sha256(path.read_bytes()).digest()
        digest.update(relative.encode() + b'\0' + value)
        files[relative] = value.hex()
    return 'sha256:' + digest.hexdigest(), files


def verified_tasks(registry, source_root):
    if registry.get('name') != 'skillsbench' or registry.get('version') != '1.1':
        raise ValueError('Expected the explicit skillsbench@1.1 registry entry')
    tasks = registry['tasks']
    names = [task['name'] for task in tasks]
    if len(tasks) != 87 or len(set(names)) != len(names):
        raise ValueError('The published roster must contain 87 unique tasks')
    result = []
    for task in tasks:
        if Path(task['name']).name != task['name'] or task['path'] != 'tasks/' + task['name']:
            raise ValueError('Invalid registry task path')
        root = Path(source_root) / task['path']
        actual, files = task_digest(root)
        if actual != task['digest']:
            raise ValueError('Official content digest mismatch: ' + task['name'])
        result.append((task, root, files))
    return result


def inventory(registry, source_root, modes):
    if not modes or len(modes) != len(set(modes)) or set(modes) - {'with-skill', 'no-skill'}:
        raise ValueError('Select distinct explicit skill conditions')
    verified = verified_tasks(registry, source_root)
    from benchflow.task import Task
    rows = []
    for entry, root, files in verified:
        task = Task(root)
        config = task.config.model_dump(mode='json', by_alias=True)
        skills = [name for name in files if name.startswith('environment/skills/')
                  and name.endswith('/SKILL.md')]
        environment = config['environment']
        review = []
        if environment.get('gpus'):
            review.append('GPU environment')
        if config['verifier'].get('service', 'main') != 'main':
            review.append('Multiple services')
        if config['verifier'].get('type', 'test-script') != 'test-script':
            review.append('Non-script verifier')
        if not skills:
            review.append('No bundled SKILL.md')
        if any('compose' in name and name.startswith('environment/') for name in files):
            review.append('Compose environment')
        rows.append({'task': entry['name'], 'revision': entry['git_commit_id'],
                     'digest': entry['digest'], 'files': files, 'config': config,
                     'skills': skills, 'review': review,
                     'image_acceptance': None, 'oracle_acceptance': None,
                     'model_admitted': False})
    slots = [{'task': row['task'], 'target': target, 'skill_mode': mode,
              'repeat': 0, 'status': 'unattempted', 'reward': None}
             for row in rows for mode in modes for target in ('agentscope', 'pi', 'codex')]
    return {'version': 1, 'dataset': 'skillsbench@1.1', 'benchflow_version': '0.6.3',
            'phase': 'preparation', 'ready_for_model': False,
            'tasks': rows, 'slots': slots,
            'agent_timeout_hours_per_condition_per_target': sum(
                row['config']['agent']['timeout_sec'] for row in rows) / 3600}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--registry', required=True)
    parser.add_argument('--source-root', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--skill-mode', action='append', required=True,
                        choices=['with-skill', 'no-skill'])
    args = parser.parse_args()
    result = inventory(json.loads(Path(args.registry).read_text()), args.source_root,
                       args.skill_mode)
    with Path(args.output).open('x') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'tasks': len(result['tasks']), 'slots': len(result['slots']),
                      'ready_for_model': result['ready_for_model']}))


if __name__ == '__main__':
    main()

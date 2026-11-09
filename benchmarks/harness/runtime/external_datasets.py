# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Prepare pinned external tasks explicitly; resolution and execution never download."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import urllib.request
import urllib.error


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def file_hashes(folder):
    result = {}
    for path in sorted(folder.rglob('*')):
        if '__pycache__' in path.parts:
            continue
        if path.is_symlink():
            raise ValueError('Dataset symlinks are not supported: ' + str(path))
        if path.is_file():
            result[path.relative_to(folder).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def read_json(path):
    return json.loads(path.read_text())


def safe_relative(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '..' in path.parts or '\\' in name or str(path) != name:
        raise ValueError('Invalid dataset resource path: ' + name)
    return path


def definitions(root):
    """Small, checked-in task index; no download or generated directories required."""
    base = Path(root) / 'external'
    result = {}
    if (base / 'terminal-bench/source.lock.json').exists():
        lock = read_json(base / 'terminal-bench/source.lock.json')
        for name, task in lock['tasks'].items():
            result[name] = {'kind': 'terminal-bench', 'source': {k: lock[k] for k in ('repository', 'revision')},
                            'task': task}
    if (base / 'business/inventory.json').exists():
        inventory = read_json(base / 'business/inventory.json')
        locks = read_json(base / 'business/sources.lock.json')
        for domain, ids in inventory['tau'].items():
            for index in ids:
                result[f'tau-{domain}-{index}'] = {'kind': 'business', 'source': locks['tau'],
                    'scenario': {'category': 'tau-' + domain,
                                 'business_case': {'dataset': 'tau', 'task_id': f'{domain}:{index}'}}}
        for split, ids in inventory['appworld'].items():
            for task_id in ids:
                name = 'appworld-' + task_id
                if name in result:
                    raise ValueError('Duplicate external task: ' + name)
                result[name] = {'kind': 'business', 'source': locks['appworld'],
                    'scenario': {'category': 'appworld-' + split,
                                 'business_case': {'dataset': 'appworld', 'task_id': task_id}}}
    return result


def identity(root, name, definition):
    root = Path(root)
    kind = definition['kind']
    resource = root / 'external' / kind / ('template' if kind == 'business' else 'overlays/' + name)
    return {'definition': definition, 'resources': file_hashes(resource),
            'preparer': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}


def prepared_path(root, name, definition):
    key = digest(identity(root, name, definition))
    return Path(root) / 'artifacts/datasets/prepared' / key / name


def prepared_task(root, name, definition):
    folder = prepared_path(root, name, definition)
    receipt = folder.parent / (name + '.json')
    if not receipt.is_file() or not folder.is_dir():
        raise ValueError('External task is not prepared: ' + name + '; run eval.py prepare-tasks CONFIG')
    record = read_json(receipt)
    if (record['inputs'] != identity(root, name, definition)
            or record['files'] != file_hashes(folder)):
        raise ValueError('Prepared task content changed: ' + name)
    if definition['kind'] == 'terminal-bench' and record['files'] != definition['task']['prepared_files']:
        raise ValueError('Prepared task differs from locked adaptation: ' + name)
    return folder


def fetch_file(root, source, relative, expected, offline=False):
    """Content-addressed download with strict version/hash validation and atomic publication."""
    safe_relative(relative)
    revision = source['revision']
    if not re.fullmatch('[a-f0-9]{40}', revision):
        raise ValueError('An immutable source commit is required')
    repository = source['repository']
    if not re.fullmatch(r'https://github.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected a GitHub source repository')
    if not re.fullmatch('[a-f0-9]{64}', expected):
        raise ValueError('Expected a SHA256 resource hash')
    cache = Path(root) / 'artifacts/datasets/downloads' / expected
    if cache.exists():
        if cache.is_symlink() or hashlib.sha256(cache.read_bytes()).hexdigest() != expected:
            raise ValueError('Cached dataset resource checksum mismatch: ' + relative)
        return cache
    if offline:
        raise ValueError('Offline resource is missing: ' + relative)
    url = repository.replace('https://github.com/', 'https://raw.githubusercontent.com/') + '/' + revision + '/' + relative
    cache.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=cache.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            try:
                with urllib.request.urlopen(url, timeout=30) as response:
                    shutil.copyfileobj(response, output)
            except urllib.error.URLError as error:
                raise ValueError('Cannot fetch pinned resource: ' + relative) from error
            output.flush()
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
                raise ValueError('Upstream resource checksum mismatch: ' + relative)
            temporary.replace(cache)
        finally:
            temporary.unlink(missing_ok=True)
    return cache


def prepare_one(root, name, definition, offline=False):
    root = Path(root)
    destination = prepared_path(root, name, definition)
    if destination.exists():
        return prepared_task(root, name, definition)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temp:
        task = Path(temp) / name
        if definition['kind'] == 'business':
            # Source task declarations / AppWorld version and LFS pointers are frozen here.
            # Full official worlds still belong in the isolated business runtime, not agent fixtures.
            for relative, expected in definition['source']['files'].items():
                fetch_file(root, definition['source'], relative, expected, offline)
            shutil.copytree(root / 'external/business/template', task)
            scenario = definition['scenario']
            toml = task / 'task.toml'
            toml.write_text(re.sub(r'^tags = .*$', 'tags = ["business", "' + scenario['category'] + '"]',
                                   toml.read_text(), flags=re.MULTILINE))
            (task / 'scenario.json').write_text(json.dumps(scenario, indent=2) + '\n')
            (task / 'environment').mkdir()
        else:
            for relative, expected in definition['task']['files'].items():
                local = task / safe_relative(relative)
                fetched = fetch_file(root, definition['source'], definition['task']['path'] + '/' + relative, expected, offline)
                local.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(fetched, local)
            shutil.copyfile(task / 'tests/test.sh', task / 'tests/upstream-test.sh')
            shutil.copytree(root / 'external/terminal-bench/overlays' / name, task, dirs_exist_ok=True)
            if file_hashes(task) != definition['task']['prepared_files']:
                raise ValueError('Adapted task differs from locked content: ' + name)
        record = {'inputs': identity(root, name, definition), 'files': file_hashes(task)}
        receipt = Path(temp) / (name + '.json')
        receipt.write_text(json.dumps(record, indent=2) + '\n')
        task.rename(destination)
        receipt.replace(destination.parent / receipt.name)
    return prepared_task(root, name, definition)

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
"""Resolve unique task IDs in the canonical dataset and export selected fixtures."""
import json
from pathlib import Path
import re
import shutil
from runtime.external_datasets import definitions, file_hashes, prepared_task, prepare_one


def task_files(folder):
    return file_hashes(folder)


def local_task(root, name):
    root = Path(root).resolve()
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{1,28}', name):
        raise ValueError('Invalid task identity')
    matches = [p for p in (root / 'datasets').glob('*/' + name) if (p / 'task.toml').is_file()]
    if len(matches) > 1:
        raise ValueError('Task must have exactly one definition: ' + name)
    if not matches:
        return None
    folder = matches[0]
    if not folder.resolve().is_relative_to((root / 'datasets').resolve()):
        raise ValueError('Task escapes the datasets directory: ' + name)
    for required in ('instruction.md', 'task.toml', 'tests/test.sh'):
        if not (folder / required).is_file():
            raise ValueError('Missing task input: ' + name + '/' + required)
    return folder


def resolve_task(root, name):
    folder = local_task(root, name)
    external = definitions(root).get(name)
    if folder and external:
        raise ValueError('Task must have exactly one definition: ' + name)
    if folder:
        return folder
    if external:
        return prepared_task(root, name, external)
    raise ValueError('Unknown task: ' + name)


def validate_selection(root, config):
    if 'dataset_root' in config:
        raise ValueError('Legacy dataset_root is not supported; use canonical task IDs')
    if not config['tasks'] or len(set(config['tasks'])) != len(config['tasks']):
        raise ValueError('Tasks must be nonempty and unique')
    external = definitions(root)
    for name in config['tasks']:
        local = local_task(root, name)
        if bool(local) == (name in external):
            raise ValueError('Task must have exactly one definition: ' + name)
    return external


def prepare_tasks(root, config, offline=False):
    external = validate_selection(root, config)
    result = {}
    for name in config['tasks']:
        result[name] = (prepare_one(root, name, external[name], offline) if name in external
                        else resolve_task(root, name))
    return result


def materialize_tasks(root, config, destination):
    """Export a self-contained suite for a runner; never alter the source tasks."""
    destination = Path(destination)
    validate_selection(root, config)
    sources = {name: resolve_task(root, name) for name in config['tasks']}
    destination.mkdir(parents=True, exist_ok=False)
    for name, source in sources.items():
        shutil.copytree(source, destination / name, ignore=shutil.ignore_patterns('__pycache__'))
    # Export exact prepared bytes and portable provenance; never include host cache paths.
    external = definitions(root)
    manifest = {name: {'files': task_files(path), 'source': external[name]['source'] if name in external else {'kind': 'owned'}}
                for name, path in sources.items()}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return {name: destination / name for name in sources}

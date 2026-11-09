# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Hidden file checks; response/tool checks run on trusted controller observations."""
import json
from pathlib import Path


def verify(checks, root):
    result = {}
    for i, check in enumerate(checks):
        kind, args = check['kind'], check['args']
        if not kind.startswith('file_'):
            continue
        relative = Path(args[0]).relative_to('/workspace')
        if '..' in relative.parts:
            raise ValueError('Escaping check path')
        path = root / relative
        if any((root.joinpath(*relative.parts[:i])).is_symlink() for i in range(1, len(relative.parts) + 1)):
            result[str(i) + ':' + kind] = False
            continue
        try:
            value = path.read_text() if path.is_file() else None
        except UnicodeDecodeError:
            result[str(i) + ':' + kind] = False
            continue
        if kind == 'file_equals': ok = value == args[1]
        elif kind == 'file_contains': ok = value is not None and args[1] in value
        elif kind == 'file_excludes': ok = args[1] not in (value or '')
        elif kind == 'file_absent': ok = not path.exists()
        else: raise ValueError('Unknown file assertion: ' + kind)
        result[str(i) + ':' + kind] = ok
    return result


if __name__ == '__main__':
    checks = verify(json.loads(Path('/tests/checks.json').read_text()), Path('/workspace'))
    output = Path('/logs/verifier')
    output.mkdir(parents=True, exist_ok=True)
    (output / 'checks.json').write_text(json.dumps(checks))
    (output / 'reward.txt').write_text(str(int(all(checks.values()))))

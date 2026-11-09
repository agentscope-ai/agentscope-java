# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Verify actual Surefire evidence, failing on missing, skipped or failed contracts."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def verify(root, manifest):
    expected = {item['class']: item for item in manifest['tests']}
    observed = {name: [] for name in expected}
    for path in Path(root).rglob('TEST-*.xml'):
        for case in ET.parse(path).iter('testcase'):
            name = case.get('classname', '').rsplit('.', 1)[-1].split('$')[0]
            if name in observed:
                status = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
                observed[name].append({'test': case.get('name'), 'status': status})
    return {'version': manifest['version'], 'passed': all(rows and all(r['status'] == 'passed' for r in rows) for rows in observed.values()),
            'classes': {name: {'capability': expected[name]['capability'], 'tests': rows, 'missing': not rows} for name, rows in observed.items()},
            'executed_tests': sum(len(rows) for rows in observed.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.root, json.loads(args.manifest.read_text()))
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'classes'}))
    return 0 if result['passed'] else 2


if __name__ == '__main__':
    raise SystemExit(main())

#!/usr/bin/env python3
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
"""Validate profiles, run framework contracts and compare immutable offline results."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    validate = commands.add_parser('validate', help='Check task completeness and actual adapter versions without cloud access')
    validate.add_argument('--config', type=Path, help='Omit to validate every suite profile')
    validate.add_argument('--definitions-only', action='store_true', help='Check selections without requiring external preparation')
    compare = commands.add_parser('compare', help='Rebuild a release decision; never replay models or change rewards')
    compare.add_argument('--candidate', type=Path, required=True)
    compare.add_argument('--baseline', type=Path)
    compare.add_argument('--output', type=Path, required=True)
    contracts = commands.add_parser('contracts', help='Run selected production contract tests with fail-closed XML verification')
    contracts.add_argument('--repo', type=Path, default=ROOT.parents[1])
    contracts.add_argument('--output', type=Path, required=True)
    contracts.add_argument('--verify-only', action='store_true', help='Inspect existing Surefire XML; not a fresh acceptance run')
    args = parser.parse_args(argv)
    if args.command == 'validate':
        from runtime.release import validate_inputs
        paths = [args.config] if args.config else sorted((ROOT/'configs/suites').glob('*.json'))
        entries = []
        for path in paths:
            config = json.loads(path.read_text())
            if 'agents' not in config:
                continue
            cases = validate_inputs(ROOT, config, definitions_only=args.definitions_only)
            entries.append({'config': str(path), 'suite': config['suite'], 'tasks': len(cases),
                'agents': len(config['agents']), 'rollouts': config['rollouts'],
                'planned': len(cases)*len(config['agents'])*config['rollouts'], 'valid': True,
                'validation_scope': 'definitions' if args.definitions_only else 'prepared-tasks'})
        print(json.dumps(entries, indent=2))
        return 0
    args.output.mkdir(parents=True, exist_ok=False)
    if args.command == 'compare':
        from runtime.release import evaluate_release, render_gate
        from reports.unified import summarize, render
        def read(folder):
            manifest = json.loads((folder/'manifest.json').read_text())
            summary = json.loads((folder/'summary.json').read_text())
            # Local downloaded results must agree with the per-trial source records.
            for row in summary['results']:
                path = folder / row.get('archive_path', '') / 'result.json'
                if not row.get('archive_path') or not path.resolve().is_relative_to(folder.resolve()) or not path.is_file():
                    raise ValueError('Require a downloaded run with immutable per-trial evidence')
                if json.loads(path.read_text()) != row:
                    raise ValueError('Summary differs from immutable trial result')
            return manifest, summary
        manifest, summary = read(args.candidate)
        gate = evaluate_release(manifest, summary, read(args.baseline) if args.baseline else None)
        (args.output/'release-gate.json').write_text(json.dumps(gate, indent=2)+'\n')
        (args.output/'release-gate.html').write_text(render_gate(gate))
        # Evidence remains in the source download; the report names its absolute source.
        report = render(manifest, summarize(manifest, summary['results']))
        report = report.replace('href="trials/', 'href="' + args.candidate.resolve().as_uri() + '/trials/')
        (args.output/'report.html').write_text(report)
        print(json.dumps({'status': gate['status'], 'report': str(args.output/'release-gate.html')}))
        return {'pass': 0, 'review': 3, 'fail': 2, 'invalid': 2}[gate['status']]
    from runtime.framework_contract import verify
    manifest = json.loads((ROOT/'contracts/framework-v1.json').read_text())
    for item in manifest['tests']:
        if not (args.repo/item['source']).is_file():
            raise ValueError('Candidate lacks required contract: ' + item['source'])
    if args.verify_only:
        evidence = args.repo
        result = verify(evidence, manifest)
        result['fresh_execution'] = False
    else:
        # Remove only selected generated XML files so stale tests cannot certify a run.
        names = {item['class'] for item in manifest['tests']}
        for path in args.repo.glob('agentscope-*/target/surefire-reports/TEST-*.xml'):
            if any(path.name.endswith('.'+name+'.xml') or '.'+name+'$' in path.name for name in names):
                path.unlink()
        cmd = ['mvn', '-B', '-T1', '-pl', 'agentscope-harness', '-am', 'test',
               '-Dtest='+','.join(sorted(names)), '-Dsurefire.failIfNoSpecifiedTests=false']
        with (args.output/'maven.log').open('w') as log:
            process = subprocess.run(cmd, cwd=args.repo, stdout=log, stderr=subprocess.STDOUT)
        result = verify(args.repo, manifest)
        result.update(fresh_execution=True, command=cmd, maven_exit=process.returncode)
        result['passed'] &= process.returncode == 0
    result['candidate_sha'] = subprocess.check_output(['git','-C',str(args.repo),'rev-parse','HEAD']).decode().strip()
    result['working_tree_diff_sha256'] = hashlib.sha256(subprocess.check_output(['git','-C',str(args.repo),'diff','HEAD','--binary'])).hexdigest()
    (args.output/'framework-contracts.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k != 'classes'},indent=2))
    return 0 if result['passed'] else 2


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)

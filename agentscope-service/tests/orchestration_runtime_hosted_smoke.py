#!/usr/bin/env python3
"""Public Hosted Job with real Agent Service/Go/PostgreSQL/Host and a fake Codex CLI.

The local configuration source is a fixture. No provider/model is called.
"""
import argparse

from orchestration_runtime_team_smoke import run


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='2.1.0-SNAPSHOT')
    parser.add_argument('--fixture-dsn', required=True, help='Dedicated local test PostgreSQL DSN')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--cancel', action='store_true')
    modes.add_argument('--approve', action='store_true')
    modes.add_argument('--deny', action='store_true')
    args = parser.parse_args()
    run(args.version, args.fixture_dsn, args.cancel, team=False, daemon=True,
        approval='approve' if args.approve else 'deny' if args.deny else None)

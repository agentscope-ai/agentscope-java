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
"""Offline entrypoint for the unified evaluation protocol foundation."""
import argparse
import json
from pathlib import Path
import sys

from runtime.protocol import EventProjection, ProtocolError, iter_jsonl, iter_sse, validate_request


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == 'release':
        from release_cli import main as release_main
        return release_main(argv[1:])
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser('release', help='Validate profiles, execute framework contracts or compare releases')
    check = commands.add_parser("protocol-check", help="Validate and reconcile a recorded stream")
    check.add_argument("path", type=Path)
    check.add_argument("--format", choices=("jsonl", "sse"), default="jsonl")
    check.add_argument("--output", type=Path, help="Write canonical history and summary to a new directory")
    request = commands.add_parser("request-check", help="Validate a send-events JSON request")
    request.add_argument("path", type=Path)
    smoke = commands.add_parser("protocol-smoke", help="Replay an offline fixture; no real agent or model")
    smoke.add_argument("--output", type=Path)
    export = commands.add_parser("export-tasks", help="Export self-contained tasks from a suite profile")
    export.add_argument("config", type=Path)
    export.add_argument("--output", type=Path, required=True)
    prepare = commands.add_parser("prepare-tasks", help="Prepare selected external tasks at locked versions")
    prepare.add_argument("config", type=Path)
    prepare.add_argument("--offline", action="store_true", help="Use verified cached downloads only")
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare-tasks":
            from runtime.datasets import prepare_tasks
            tasks = prepare_tasks(Path(__file__).parent, json.loads(args.config.read_text()), args.offline)
            print(json.dumps({"tasks": len(tasks), "prepared": True,
                              "note": "Task preparation does not install or start official business environments."}))
            return 0
        if args.command == "export-tasks":
            from runtime.datasets import materialize_tasks
            tasks = materialize_tasks(Path(__file__).parent, json.loads(args.config.read_text()), args.output)
            print(json.dumps({"tasks": len(tasks), "output": str(args.output)}))
            return 0
        if args.command == "request-check":
            validate_request(json.loads(args.path.read_text(encoding="utf-8")))
            print(json.dumps({"valid": True, "profile": "managed-agents-text-v1"}))
            return 0
        path = (Path(__file__).parent / "tests/fixtures/session.jsonl"
                if args.command == "protocol-smoke" else args.path)
        projection = EventProjection()
        parse = iter_sse if getattr(args, "format", "jsonl") == "sse" else iter_jsonl
        with path.open("rb") as source:
            for event in parse(source):
                projection.accept(event)
        summary = projection.summary()
        if args.command == "protocol-smoke":
            summary["execution"] = "offline_fixture_only"
        if args.output:
            args.output.mkdir(parents=True, exist_ok=False)
            (args.output / "events.jsonl").write_text(
                "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in projection.events), encoding="utf-8")
            (args.output / "summary.json").write_text(
                json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(summary, indent=2, ensure_ascii=False))
        return 0
    except (ProtocolError, OSError, ValueError) as exc:
        # Paths can be shown, but never dump invalid event payloads or credentials.
        parser.exit(2, "Evaluation input failed: %s\n" % (
            str(exc) if isinstance(exc, (ProtocolError, ValueError)) else type(exc).__name__))


if __name__ == "__main__":
    raise SystemExit(main())

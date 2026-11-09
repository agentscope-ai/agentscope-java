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
"""Combine disjoint, compatible completed batches without replaying evaluations."""
import argparse
import json
from pathlib import Path

from report import build_report, coverage, render
from worker import summary


def combine(reports):
    first = reports[0]["manifest"]
    identities = set()
    cases = []
    for report in reports:
        for key in ("candidate_sha", "model", "datasets", "images", "appworld_data_hash"):
            if report["manifest"][key] != first[key]:
                raise ValueError("Cannot pool incompatible " + key)
        if not report.get("completed"):
            raise ValueError("Cannot pool an unfinished batch")
        for original in report["cases"]:
            case = {**original, "batch_id": report["manifest"]["batch_id"]}
            if case["id"] in identities:
                raise ValueError("Overlapping tasks must not be silently counted twice")
            identities.add(case["id"])
            cases.append(case)
    datasets = {d: summary([c for c in cases if c["dataset"] == d and c["outcome"] != "missing"],
                           sum(c["dataset"] == d for c in cases)) for d in first["datasets"]}
    source_batches = [r["manifest"]["batch_id"] for r in reports]
    return {"manifest": {**first, "batch_id": " + ".join(source_batches),
                         "selection": {"name": "累计代表性抽样", "source_batches": source_batches}},
            "cases": cases, "coverage": coverage(cases),
            "summary": {"datasets": datasets, "source_batches": [
                {"batch_id": r["manifest"]["batch_id"], "summary": r["summary"]} for r in reports]},
            "completed": {"time": max(r["completed"]["time"] for r in reports),
                          "results_complete": all(r["completed"]["results_complete"] for r in reports),
                          "valid_scoring_coverage_complete": all(r["completed"]["valid_scoring_coverage_complete"] for r in reports)}}



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batches", type=Path, nargs="+", required=True, help="Local archived batch directories")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    reports = []
    for folder in args.batches:
        keys = [p.relative_to(folder).as_posix() for p in folder.rglob("*.json")]
        reports.append(build_report(lambda key: json.loads((folder / key).read_text()), keys))
    data = combine(reports)
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "report.html").write_text(render(data))
    (args.output / "summary.json").write_text(json.dumps(data["summary"], indent=2))
    (args.output / "coverage.json").write_text(json.dumps(data["coverage"], indent=2))


if __name__ == "__main__":
    main()

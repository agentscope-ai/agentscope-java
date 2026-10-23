#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Compute descriptive statistics for CSV numeric columns and output a Markdown report."""
import argparse
import csv
import math
import sys
from collections import defaultdict


def summarize(input_path: str, output_path: str | None) -> None:
    with open(input_path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print("Empty input", file=sys.stderr)
        sys.exit(1)

    numeric: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        for k, v in row.items():
            try:
                numeric[k].append(float(v))
            except (ValueError, TypeError):
                pass

    lines = [f"# Data Report\n\n**Rows:** {len(rows)}\n"]
    for col, vals in numeric.items():
        n = len(vals)
        mean = sum(vals) / n
        variance = sum((x - mean) ** 2 for x in vals) / n
        lines.append(f"## {col}")
        lines.append(f"- Count: {n}")
        lines.append(f"- Min: {min(vals):.4g}")
        lines.append(f"- Max: {max(vals):.4g}")
        lines.append(f"- Mean: {mean:.4g}")
        lines.append(f"- Std: {math.sqrt(variance):.4g}\n")

    report = "\n".join(lines)
    if output_path:
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"Report written to {output_path}")
    else:
        print(report)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Generate data summary report")
    p.add_argument("--input", required=True, help="Input CSV file")
    p.add_argument("--output", help="Output Markdown file (stdout if omitted)")
    args = p.parse_args()
    summarize(args.input, args.output)
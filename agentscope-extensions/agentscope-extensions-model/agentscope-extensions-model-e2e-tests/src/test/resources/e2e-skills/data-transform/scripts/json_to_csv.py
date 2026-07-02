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

"""Flatten a JSON array to CSV."""
import argparse
import csv
import json
import sys


def json_to_csv(input_path: str, output_path: str | None) -> None:
    with open(input_path, encoding="utf-8") as f:
        rows = json.load(f)
    if not rows:
        print("Empty input", file=sys.stderr)
        sys.exit(1)
    fieldnames = list(rows[0].keys())
    if output_path:
        with open(output_path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fieldnames)
            w.writeheader()
            w.writerows(rows)
        print(f"Written {len(rows)} rows to {output_path}")
    else:
        w = csv.DictWriter(sys.stdout, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(rows)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Convert JSON array to CSV")
    p.add_argument("--input", required=True, help="Input JSON file")
    p.add_argument("--output", help="Output CSV file (stdout if omitted)")
    args = p.parse_args()
    json_to_csv(args.input, args.output)
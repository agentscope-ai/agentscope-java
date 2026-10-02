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

"""Convert a CSV file to a JSON array."""
import argparse
import csv
import json
import sys


def csv_to_json(input_path: str, output_path: str | None) -> None:
    with open(input_path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    result = json.dumps(rows, indent=2, ensure_ascii=False)
    if output_path:
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"Written {len(rows)} records to {output_path}")
    else:
        print(result)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Convert CSV to JSON")
    p.add_argument("--input", required=True, help="Input CSV file")
    p.add_argument("--output", help="Output JSON file (stdout if omitted)")
    args = p.parse_args()
    csv_to_json(args.input, args.output)
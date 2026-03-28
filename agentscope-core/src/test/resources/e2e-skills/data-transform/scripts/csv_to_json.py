#!/usr/bin/env python3
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

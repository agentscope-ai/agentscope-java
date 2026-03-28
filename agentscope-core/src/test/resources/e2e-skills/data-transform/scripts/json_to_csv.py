#!/usr/bin/env python3
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

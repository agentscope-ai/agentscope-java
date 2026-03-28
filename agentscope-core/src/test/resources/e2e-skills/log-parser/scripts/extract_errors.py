#!/usr/bin/env python3
"""Extract ERROR and WARN lines from application log files."""
import argparse
import json
import re
import sys
from pathlib import Path

LOG_PATTERN = re.compile(
    r"(?P<timestamp>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?)"
    r".*?\b(?P<level>ERROR|WARN(?:ING)?)\b"
    r"(?P<rest>.*)"
)


def extract_errors(input_path: str, output_path: str | None, level: str) -> None:
    levels = {"ERROR", "WARN", "WARNING"} if level == "ALL" else {level, level + "ING"}
    entries = []

    with open(input_path, encoding="utf-8", errors="replace") as f:
        for lineno, line in enumerate(f, 1):
            m = LOG_PATTERN.search(line)
            if m and m.group("level") in levels:
                entries.append(
                    {
                        "line": lineno,
                        "timestamp": m.group("timestamp"),
                        "level": m.group("level"),
                        "message": m.group("rest").strip(),
                    }
                )

    summary = {
        "source": input_path,
        "total_matches": len(entries),
        "entries": entries,
    }

    result = json.dumps(summary, indent=2, ensure_ascii=False)
    if output_path:
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"Found {len(entries)} entries. Written to {output_path}")
    else:
        print(result)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Extract errors/warnings from log files")
    p.add_argument("--input", required=True, help="Log file path")
    p.add_argument("--output", help="Output JSON file (stdout if omitted)")
    p.add_argument(
        "--level", default="ALL", choices=["ALL", "ERROR", "WARN"], help="Filter by level"
    )
    args = p.parse_args()
    extract_errors(args.input, args.output, args.level)

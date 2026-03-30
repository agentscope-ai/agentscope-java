---
name: log-parser
description: Use this skill when the task involves inspecting, searching, or extracting information from application log files. Suitable for tasks like "my application is crashing and I need to find the errors", "scan these logs for warnings", "extract all exceptions from this log file", or "find slow requests in my server logs". Triggered by debugging, incident investigation, or log analysis needs.
---
# Log Parser Skill

Parses application log files to extract errors, warnings, and patterns.

## Available Scripts

- `scripts/extract_errors.py` — Scan a log file and extract all ERROR/WARN lines with timestamps into a structured summary

## Usage

```
python3 scripts/extract_errors.py --input app.log --output errors.json
python3 scripts/extract_errors.py --input app.log --level ERROR
```

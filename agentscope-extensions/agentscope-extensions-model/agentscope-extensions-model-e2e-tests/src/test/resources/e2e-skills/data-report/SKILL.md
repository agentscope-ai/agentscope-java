---
name: data-report
description: Use this skill when the task involves analyzing data to compute statistics, aggregations, or summaries, and presenting the results as a report. Suitable for tasks like "summarize this dataset", "calculate averages and totals", "generate a weekly sales report", or "show me trends in this data". Do NOT use for simple format conversion between file types.
---
# Data Report Skill

Analyzes structured data and generates statistical summary reports.

## Available Scripts

- `scripts/summarize.py` — Compute descriptive statistics (count, mean, min, max, stddev) for numeric columns and output a Markdown report

## Usage

```
python3 scripts/summarize.py --input data.csv --output report.md
```

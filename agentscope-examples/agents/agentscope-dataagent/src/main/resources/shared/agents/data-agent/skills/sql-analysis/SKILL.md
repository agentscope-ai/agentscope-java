---
name: sql-analysis
description: Answer a quantitative business question by writing a SQL query against the data warehouse, validating it, and presenting the result. Use when the user asks "how many...", "what's the trend of...", "compare X vs Y over...", "what's our top N...", or anything that resolves to a query against tabular data. Produces a small result table plus the underlying query.
---

# SQL Analysis Skill

Repeatable SOP for turning a business question into a verifiable SQL answer.

## Steps

1. **Restate the question as a metric.** In one sentence, write:
   *"<metric> by <grouping> over <time window>, filtered by <filter>"*.
   If any of those four (metric / grouping / window / filter) is missing or
   ambiguous, ask **one** clarifying question and stop. Do not guess.

2. **Locate the source.** Decide where the data lives:
   - Look in `knowledge/` first for any schema notes, data-dictionary entries,
     or prior query examples uploaded by the user.
   - If the right table is not obvious, **delegate to the `data-explorer`
     sub-agent** with the metric definition as the prompt — its job is to
     identify the canonical source.

3. **Draft the query.** Write the SQL in a fenced ```sql``` block. Conventions:
   - Always include the time window in a `WHERE` clause — never query the
     full history "just in case".
   - Always `SELECT` an explicit column list — never `SELECT *` in an answer.
   - Use CTEs (`WITH x AS (...)`) over nested subqueries for anything beyond
     two levels of nesting.
   - Comment any non-obvious filter (`-- excludes internal test accounts`).

4. **Validate before reporting.** Run the query and check:
   - Row count is in the ballpark you expected (1 row, 10 rows, ~30 daily
     buckets, etc.). A surprising row count is almost always a bug — investigate.
   - No `NULL`s in the grouping column unless that is the intended cohort.
   - At least one numeric sanity check: a known total, a known reference value,
     or a min/max range that matches reality.
   - If anything looks off, **do not report the number** — fix the query first.

5. **Write the report.** Use this exact structure:

   ```
   ## Answer
   <one-sentence direct answer with the headline number(s)>

   ## Result
   <small markdown table — at most ~15 rows; for longer results, summarise
   and offer to render a chart or attach the full CSV>

   ## Query
   ```sql
   <the exact query you ran>
   ```

   ## Sources & validation
   - **Table(s):** `schema.table_name` (row count, freshness if known)
   - **Validation:** <which sanity checks you ran and the result>
   - **Caveats:** <known data quality issues, missing dates, etc.>
   ```

## Anti-patterns

- ❌ Reporting a number without the query that produced it.
- ❌ Using `LIMIT N` to "make the output fit" without explaining what got cut.
- ❌ Reading a single row and reporting it as a trend.
- ❌ Inventing a table or column name. If unsure, delegate to `data-explorer`.

## When to delegate

- The user's question requires probing several candidate tables / sources
  before any query can be written → **spawn `data-explorer`**.
- The user wants a polished written deliverable summarising several analyses
  (e.g. "weekly health report") → **spawn `report-writer`** after you have
  the underlying numbers ready.

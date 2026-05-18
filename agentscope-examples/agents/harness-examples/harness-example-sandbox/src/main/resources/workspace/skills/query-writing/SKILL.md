---
name: query-writing
description: Discover schema, write SELECT-only SQLite queries, execute, and explain results (aligned with harness-example).
---

# Query writing (sandbox)

1. Call `sql_list_tables` if you do not yet know table names.
2. Call `sql_get_schema` for each table you join or filter on.
3. Run `sql_execute_query` with a single `SELECT` (add `LIMIT` for large scans).
4. Summarise results in plain language.

See the full skill in `agentscope-examples/harness-example` under
`src/main/resources/workspace/skills/query-writing/SKILL.md` for multi-table JOIN patterns.

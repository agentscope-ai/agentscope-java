---
name: query-writing
description: Writes and executes SQL queries ranging from simple single-table SELECTs to complex multi-table JOINs, aggregations, window functions, and subqueries. Use when the user asks to query the database, retrieve data, filter records, rank results, or generate reports.
---

# Query Writing Skill

## When to Use This Skill

Use query-writing when the user:

- Asks "how many …?" / "what are the top …?" / "list all …"
- Needs data aggregated (SUM, COUNT, AVG, MAX, MIN)
- Wants results sorted, filtered, or grouped
- Asks for trend analysis, ranking, or comparison across dimensions

---

## Workflow for Simple Queries (single table)

1. **Identify the table** — which table contains the answer?
2. **Check the schema** — call `sql_get_schema` to confirm column names.
3. **Write a SELECT** — include WHERE / ORDER BY / LIMIT as needed.
4. **Execute** — call `sql_execute_query`.
5. **Present** — show the SQL and the result in plain language.

### Example — "How many customers are from Canada?"

```sql
SELECT COUNT(*) AS canadian_customers
FROM Customer
WHERE Country = 'Canada';
```

---

## Workflow for Complex Queries (multiple tables)

### Step 1 — Plan with todos

Break the query into subtasks:

```
- [ ] Identify all required tables
- [ ] Inspect schemas to find join columns
- [ ] Draft the JOIN structure
- [ ] Add aggregations and grouping
- [ ] Validate and run
```

### Step 2 — Inspect schemas

Call `sql_get_schema` for EACH table involved to find the exact foreign key column names.

### Step 3 — Build the query

```sql
SELECT
    <grouping columns>,
    <aggregates>
FROM <primary table>
[INNER | LEFT] JOIN <table2> ON <fk> = <pk>
[JOIN ...]
WHERE <filters>
GROUP BY <non-aggregate columns>
HAVING <post-aggregation filters>   -- optional
ORDER BY <sort column> [DESC]
LIMIT 10;                           -- always limit unless all rows requested
```

### Step 4 — Validate

Before executing, verify:
- Every JOIN has an ON clause
- Every non-aggregate SELECT column appears in GROUP BY
- Table aliases are consistent
- No DML statements (INSERT / UPDATE / DELETE / DROP)

### Step 5 — Execute and present

Call `sql_execute_query`, then show:
1. The SQL query in a fenced code block
2. The result table
3. A brief plain-language summary

---

## Templates for Common Patterns

### Top-N ranking

```sql
SELECT
    Artist.Name        AS artist,
    SUM(InvoiceLine.UnitPrice * InvoiceLine.Quantity) AS total_revenue
FROM Artist
JOIN Album       ON Album.ArtistId      = Artist.ArtistId
JOIN Track       ON Track.AlbumId       = Album.AlbumId
JOIN InvoiceLine ON InvoiceLine.TrackId = Track.TrackId
GROUP BY Artist.ArtistId, Artist.Name
ORDER BY total_revenue DESC
LIMIT 10;
```

### Revenue by time period

```sql
SELECT
    strftime('%Y-%m', InvoiceDate) AS month,
    ROUND(SUM(Total), 2)           AS monthly_revenue
FROM Invoice
WHERE strftime('%Y', InvoiceDate) = '2013'
GROUP BY month
ORDER BY month;
```

### Entity counts with left join

```sql
SELECT
    e.FirstName || ' ' || e.LastName AS employee,
    COUNT(c.CustomerId)               AS customer_count
FROM Employee e
LEFT JOIN Customer c ON c.SupportRepId = e.EmployeeId
GROUP BY e.EmployeeId
ORDER BY customer_count DESC;
```

---

## Error Recovery

| Symptom              | Action |
|----------------------|--------|
| Empty result         | Check WHERE condition values (case-sensitive strings). Verify column exists. |
| Syntax error         | Re-read schema. Check GROUP BY includes all non-aggregate SELECT columns. |
| Wrong row count      | Look for duplicate rows caused by missing JOIN conditions. |
| Result seems too high | Check for fan-out from multiple JOINs; may need DISTINCT or subquery. |

---

## Quality Rules

- Always apply `LIMIT` (default 10) unless the user explicitly asks for all rows.
- Use table aliases (`e`, `c`, `inv`) for readability in multi-table queries.
- Never use `SELECT *` — name the columns you need.
- Round monetary values to 2 decimal places: `ROUND(SUM(Total), 2)`.
- Always show the executed SQL so users can learn from it.

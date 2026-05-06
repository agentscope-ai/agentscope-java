---
name: schema-exploration
description: Lists tables, describes columns and data types, identifies foreign key relationships, and maps entity relationships in the database. Use when the user asks about database structure, table layout, column types, what tables exist, foreign keys, or how entities relate to each other.
---

# Schema Exploration Skill

## When to Use This Skill

Use schema-exploration when the user:

- Asks "what tables are in the database?"
- Asks "what columns does the X table have?"
- Asks about relationships between tables
- Needs to understand data types before writing a query
- Wants an entity-relationship overview

## Workflow

### Step 1 — List All Tables

Use `sql_list_tables` to see everything available.

```
Tool: sql_list_tables
(no parameters)
```

### Step 2 — Inspect Relevant Tables

Use `sql_get_schema` with the table name(s) you need to understand:

```
Tool: sql_get_schema
tables: "Customer"           # single table
tables: "Invoice,Customer"   # multiple tables at once
```

This returns:
- **Columns** — names, types, NOT NULL constraints, primary keys
- **Foreign keys** — links to other tables
- **Sample data** — 3 example rows to understand content

### Step 3 — Map Relationships

Look for columns ending in `Id` — they are almost always foreign keys.

Example for the Chinook database:
```
Customer.SupportRepId → Employee.EmployeeId
Invoice.CustomerId    → Customer.CustomerId
Track.AlbumId         → Album.AlbumId
Track.GenreId         → Genre.GenreId
```

### Step 4 — Present the Findings

Provide:
- A list of all tables with a one-line purpose summary
- Column names and types for any tables the user asked about
- The relationship chain (which table links to which)
- Sample data to illustrate what the table holds

---

## Examples

### "What tables are available?"

1. Call `sql_list_tables`
2. Return a formatted list with brief descriptions:

```
The Chinook database has 11 tables:

Music Catalog:
  - Artist       — 275 music artists
  - Album        — 347 albums (linked to Artist)
  - Track        — 3,503 tracks (linked to Album, Genre, MediaType)
  - Genre        — 25 music genres
  - MediaType    — 5 file formats (MP3, AAC, …)

Commerce:
  - Customer     — 59 customers from 24 countries
  - Invoice      — 412 purchase invoices
  - InvoiceLine  — 2,240 line items (linked to Invoice and Track)

Staff:
  - Employee     — 8 employees with reporting hierarchy

Playlists:
  - Playlist      — 18 playlists
  - PlaylistTrack — join table linking Playlist ↔ Track
```

### "What does the Customer table look like?"

1. Call `sql_get_schema` with `tables: "Customer"`
2. Present columns with types and notes:

```
Customer table:
  CustomerId   INTEGER  PK
  FirstName    TEXT
  LastName     TEXT
  Company      TEXT     (nullable)
  Address      TEXT
  City         TEXT
  State        TEXT     (nullable)
  Country      TEXT
  PostalCode   TEXT     (nullable)
  Phone        TEXT     (nullable)
  Fax          TEXT     (nullable)
  Email        TEXT
  SupportRepId INTEGER  FK → Employee.EmployeeId

Sample rows show customers from Brazil, Germany, and Canada.
```

### "How are artists connected to sales?"

Map the full JOIN path:

```
Artist (ArtistId)
  ↓ 1:many
Album (ArtistId, AlbumId)
  ↓ 1:many
Track (AlbumId, TrackId)
  ↓ 1:many
InvoiceLine (TrackId, UnitPrice, Quantity)
  ↓ many:1
Invoice (InvoiceId, Total)
```

Then suggest using the **query-writing** skill to write the aggregation query.

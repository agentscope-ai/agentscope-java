# Chinook Database — Schema Reference

The Chinook database represents a digital music store. It was originally created to demonstrate
data modelling in SQLite and is widely used as a sample database for tutorials.

## Entity Relationship Overview

```
Artist (ArtistId, Name)
  └─ 1:many ─► Album (AlbumId, Title, ArtistId)
                 └─ 1:many ─► Track (TrackId, Name, AlbumId, MediaTypeId, GenreId,
                                      Composer, Milliseconds, Bytes, UnitPrice)
                                 └─ 1:many ─► InvoiceLine (InvoiceLineId, InvoiceId,
                                                             TrackId, UnitPrice, Quantity)
                                                └─ many:1 ─► Invoice (InvoiceId, CustomerId,
                                                                        InvoiceDate, BillingAddress,
                                                                        BillingCity, BillingState,
                                                                        BillingCountry, BillingPostalCode,
                                                                        Total)
                                                               └─ many:1 ─► Customer (CustomerId,
                                                                                        FirstName, LastName,
                                                                                        Company, Address,
                                                                                        City, State, Country,
                                                                                        PostalCode, Phone,
                                                                                        Fax, Email,
                                                                                        SupportRepId)
                                                                               └─ many:1 ─► Employee

Genre (GenreId, Name)
MediaType (MediaTypeId, Name)
Playlist (PlaylistId, Name)
  └─ many:many via PlaylistTrack (PlaylistId, TrackId) ─► Track

Employee (EmployeeId, LastName, FirstName, Title, ReportsTo [→ Employee.EmployeeId],
          BirthDate, HireDate, Address, City, State, Country, PostalCode, Phone, Fax, Email)
```

## Table Descriptions

### Artist
| Column   | Type    | Notes |
|----------|---------|-------|
| ArtistId | INTEGER | PK    |
| Name     | TEXT    |       |

275 rows — music artists (AC/DC, Aerosmith, Alanis Morissette, …).

### Album
| Column   | Type    | Notes          |
|----------|---------|----------------|
| AlbumId  | INTEGER | PK             |
| Title    | TEXT    |                |
| ArtistId | INTEGER | FK → Artist    |

347 rows — one or many albums per artist.

### Track
| Column        | Type    | Notes              |
|---------------|---------|--------------------|
| TrackId       | INTEGER | PK                 |
| Name          | TEXT    |                    |
| AlbumId       | INTEGER | FK → Album         |
| MediaTypeId   | INTEGER | FK → MediaType     |
| GenreId       | INTEGER | FK → Genre         |
| Composer      | TEXT    | nullable           |
| Milliseconds  | INTEGER | duration           |
| Bytes         | INTEGER | file size          |
| UnitPrice     | REAL    | default 0.99       |

3,503 rows — the central music catalog table.

### Genre
| Column  | Type    | Notes |
|---------|---------|-------|
| GenreId | INTEGER | PK    |
| Name    | TEXT    |       |

25 rows — Rock, Jazz, Metal, Alternative & Punk, …

### MediaType
| Column      | Type    | Notes |
|-------------|---------|-------|
| MediaTypeId | INTEGER | PK    |
| Name        | TEXT    |       |

5 rows — MPEG audio file, AAC audio file, Protected AAC, …

### Customer
| Column       | Type    | Notes             |
|--------------|---------|-------------------|
| CustomerId   | INTEGER | PK                |
| FirstName    | TEXT    |                   |
| LastName     | TEXT    |                   |
| Company      | TEXT    | nullable          |
| Address      | TEXT    |                   |
| City         | TEXT    |                   |
| State        | TEXT    | nullable          |
| Country      | TEXT    |                   |
| PostalCode   | TEXT    | nullable          |
| Phone        | TEXT    | nullable          |
| Fax          | TEXT    | nullable          |
| Email        | TEXT    | unique            |
| SupportRepId | INTEGER | FK → Employee     |

59 rows — customers from 24 countries.

### Employee
| Column      | Type    | Notes                      |
|-------------|---------|----------------------------|
| EmployeeId  | INTEGER | PK                         |
| LastName    | TEXT    |                            |
| FirstName   | TEXT    |                            |
| Title       | TEXT    |                            |
| ReportsTo   | INTEGER | FK → Employee (nullable)   |
| BirthDate   | TEXT    | ISO-8601                   |
| HireDate    | TEXT    | ISO-8601                   |
| Address     | TEXT    |                            |
| City        | TEXT    |                            |
| State       | TEXT    |                            |
| Country     | TEXT    |                            |
| PostalCode  | TEXT    |                            |
| Phone       | TEXT    |                            |
| Fax         | TEXT    |                            |
| Email       | TEXT    |                            |

8 rows — Sales Support Agents + manager hierarchy.

### Invoice
| Column          | Type    | Notes            |
|-----------------|---------|------------------|
| InvoiceId       | INTEGER | PK               |
| CustomerId      | INTEGER | FK → Customer    |
| InvoiceDate     | TEXT    | ISO-8601         |
| BillingAddress  | TEXT    |                  |
| BillingCity     | TEXT    |                  |
| BillingState    | TEXT    | nullable         |
| BillingCountry  | TEXT    |                  |
| BillingPostalCode | TEXT  | nullable         |
| Total           | REAL    | invoice total    |

412 rows — one invoice per purchase, covering 2009–2013.

### InvoiceLine
| Column        | Type    | Notes            |
|---------------|---------|------------------|
| InvoiceLineId | INTEGER | PK               |
| InvoiceId     | INTEGER | FK → Invoice     |
| TrackId       | INTEGER | FK → Track       |
| UnitPrice     | REAL    |                  |
| Quantity      | INTEGER |                  |

2,240 rows — line items; revenue = UnitPrice × Quantity.

### Playlist
| Column     | Type    | Notes |
|------------|---------|-------|
| PlaylistId | INTEGER | PK    |
| Name       | TEXT    |       |

18 rows — Music, Movies, TV Shows, Classical, …

### PlaylistTrack
| Column     | Type    | Notes              |
|------------|---------|---------------------|
| PlaylistId | INTEGER | FK → Playlist (PK) |
| TrackId    | INTEGER | FK → Track (PK)    |

8,715 rows — composite PK, no surrogate key.

## Common JOIN Paths

```sql
-- Revenue by artist
Artist
  JOIN Album    ON Album.ArtistId      = Artist.ArtistId
  JOIN Track    ON Track.AlbumId       = Album.AlbumId
  JOIN InvoiceLine ON InvoiceLine.TrackId = Track.TrackId

-- Revenue by country
Invoice
  JOIN Customer ON Customer.CustomerId = Invoice.CustomerId
  GROUP BY Customer.Country

-- Employee support load
Employee
  LEFT JOIN Customer ON Customer.SupportRepId = Employee.EmployeeId
```

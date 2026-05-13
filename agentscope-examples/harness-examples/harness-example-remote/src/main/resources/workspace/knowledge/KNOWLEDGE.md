# Chinook Knowledge (remote-store demo)

This Data Agent uses the Chinook SQLite sample database.

Primary entities:
- `Artist` -> `Album` -> `Track`
- `Track` -> `InvoiceLine` -> `Invoice` -> `Customer`

Guidance:
- Always discover schema with `sql_list_tables` and `sql_get_schema`.
- Use read-only `SELECT` statements.
- Add `LIMIT` when the user did not request full output.

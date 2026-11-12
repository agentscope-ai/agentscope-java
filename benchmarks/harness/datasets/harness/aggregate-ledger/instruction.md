Read `/workspace/ledger.csv`. For rows whose `status` is `settled`, sum `amount_cents` by account,
including negative amounts. Exclude all other statuses. Write `/workspace/summary.json` as a JSON
array of objects with exactly `account` and `total_cents`, sorted alphabetically by account.
Keep totals as integers. Do not change the source CSV.

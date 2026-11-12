import hashlib
import json
from pathlib import Path

expected = [{"account": "alpha", "total_cents": 950}, {"account": "beta", "total_cents": 400},
            {"account": "delta", "total_cents": 0}]
checks = {}
original = Path("/tests/ledger.sha256").read_text().strip()
try:
    actual = json.loads(Path("/workspace/summary.json").read_text())
    checks["exact_values_and_order"] = actual == expected
    checks["integer_totals"] = isinstance(actual, list) and all(type(row.get("total_cents")) is int for row in actual)
    checks["source_unchanged"] = hashlib.sha256(Path("/workspace/ledger.csv").read_bytes()).hexdigest() == original
except Exception as error:
    checks["exception"] = type(error).__name__
Path("/logs/verifier/checks.json").write_text(json.dumps(checks, indent=2))
Path("/logs/verifier/reward.txt").write_text("1" if len(checks) == 3 and all(v is True for v in checks.values()) else "0")

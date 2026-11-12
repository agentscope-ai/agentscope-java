#!/usr/bin/env python3
"""Validate actual Rust/native-core requests against the shared protocol schemas."""
import importlib.util
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
protocol = root.parent / "protocol"
spec = importlib.util.spec_from_file_location("protocol_validation", protocol / "scripts/validate.py")
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)
api = json.loads((protocol / "openapi.json").read_text())
requests = json.loads(Path(sys.argv[1]).read_text())
checked = 0
for entry in requests:
    operation = entry["operation"]
    endpoint = api["paths"]["/" + operation]["post"]
    schema = endpoint["requestBody"]["content"]["application/json"]["schema"]["$ref"]
    errors = validation.schema_errors(schema, entry["request"])
    if errors:
        raise SystemExit(f"{operation}: {errors}")
    checked += 1
print(f"Validated {checked} Rust/native-core protocol requests against protocol 0.1.0")

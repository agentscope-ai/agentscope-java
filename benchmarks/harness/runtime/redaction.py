# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Remove configured credential values before exporting raw evidence."""
import os


def redact(raw, values=None):
    values = values or [value for key, value in os.environ.items()
                        if any(part in key for part in ("API_KEY", "ACCESS_KEY", "TOKEN", "PASSWORD"))]
    for value in values:
        if len(value) >= 8:
            raw = raw.replace(value.encode(), b"[REDACTED]")
    return raw

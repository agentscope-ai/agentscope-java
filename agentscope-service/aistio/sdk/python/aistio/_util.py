"""Internal helpers shared across aistio SDK modules."""
from __future__ import annotations

import time
from datetime import datetime, timezone


def now_ms() -> int:
    """Current wall-clock time in unix milliseconds."""
    return int(time.time() * 1000)


def rfc3339(ms: int) -> str:
    """Format unix milliseconds as RFC3339 (UTC, ``Z`` suffix).

    Returns an empty string for non-positive input so optional JSON fields can
    be omitted by the caller.
    """
    if ms <= 0:
        return ""
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return dt.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def truncate(text: str, limit: int) -> str:
    """Truncate ``text`` to at most ``limit`` characters (ellipsis suffix)."""
    if not text or len(text) <= limit:
        return text
    if limit <= 1:
        return text[:limit]
    return text[: limit - 1] + "…"

# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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

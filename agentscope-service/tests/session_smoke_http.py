# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Shared SSE assertions for the current process fixtures."""

import json
from urllib.request import Request, urlopen


def read_sse(address, headers, count):
    events = []
    frame = {}
    with urlopen(Request(address, headers={"Accept": "text/event-stream", **headers}), timeout=8) as response:
        assert response.headers.get_content_type() == "text/event-stream"
        while len(events) < count:
            line = response.readline().decode().rstrip("\r\n")
            if not line:
                if "data" in frame:
                    event = json.loads(frame["data"])
                    assert frame["id"] == event["cursor"]
                    assert frame["event"] == event["type"]
                    events.append(event)
                frame = {}
            elif not line.startswith(":"):
                key, _, value = line.partition(":")
                frame[key] = value.lstrip()
    return events


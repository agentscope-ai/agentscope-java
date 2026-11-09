# Copyright (c) LangChain, Inc.
# SPDX-License-Identifier: MIT
# Attribution and license: see LICENSE and the capabilities dataset README.
# Independently maintained synthetic tools; no upstream test framework dependency.
from __future__ import annotations

from runtime.fixtures import tool


@tool
def get_weather_fake(location: str) -> str:  # noqa: ARG001
    """Return a fixed weather response for eval scenarios."""
    return "It's sunny at 89 degrees F"

# Copyright (c) LangChain, Inc.
# SPDX-License-Identifier: MIT
# Attribution and license: see LICENSE and the capabilities dataset README.
# Independently maintained synthetic tools; no upstream test framework dependency.
from __future__ import annotations

from runtime.fixtures import tool


@tool
def lookup_population(city: str) -> str:
    """Return the population of a city as a string."""
    data = {
        "tokyo": "13,960,000",
        "delhi": "32,900,000",
        "shanghai": "29,200,000",
        "cairo": "21,800,000",
    }
    return data.get(city.lower(), "unknown")


@tool
def lookup_area_km2(city: str) -> str:
    """Return the area of a city in square kilometers as a string."""
    data = {
        "tokyo": "2,194",
        "delhi": "1,484",
        "shanghai": "6,341",
        "cairo": "606",
    }
    return data.get(city.lower(), "unknown")

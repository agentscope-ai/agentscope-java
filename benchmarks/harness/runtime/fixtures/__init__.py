# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0


def tool(fn):
    """Mark a local synthetic tool without importing an agent framework."""
    fn.is_fixture_tool = True
    return fn

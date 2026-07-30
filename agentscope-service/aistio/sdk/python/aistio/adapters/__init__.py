"""框架适配器层（sdk-design §5.2 / framework-integration §3.3）。"""
from __future__ import annotations

from .base import (
    COMMAND_ABORT,
    COMMAND_COMPRESS,
    COMMAND_TERMINATE,
    KNOWN_COMMANDS,
    FrameworkAdapter,
)
from .registry import find_adapter, register_adapter, registered_adapters

__all__ = [
    "FrameworkAdapter",
    "COMMAND_ABORT",
    "COMMAND_COMPRESS",
    "COMMAND_TERMINATE",
    "KNOWN_COMMANDS",
    "register_adapter",
    "registered_adapters",
    "find_adapter",
]

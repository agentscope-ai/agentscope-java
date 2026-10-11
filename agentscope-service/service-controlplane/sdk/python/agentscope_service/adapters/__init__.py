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

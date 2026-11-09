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
"""Remove configured credential values before exporting raw evidence."""
import os


def redact(raw, values=None):
    values = values or [value for key, value in os.environ.items()
                        if any(part in key for part in ("API_KEY", "ACCESS_KEY", "TOKEN", "PASSWORD"))]
    for value in values:
        if len(value) >= 8:
            raw = raw.replace(value.encode(), b"[REDACTED]")
    return raw

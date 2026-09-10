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

# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.

"""Durable local outbox for acknowledged ASDP session events."""
from __future__ import annotations

import hashlib
import json
import os
import struct
from pathlib import Path
from typing import Dict, List

from google.protobuf.message import DecodeError

from .proto import asdp_pb2

_MAX_RECORD_BYTES = 16 * 1024 * 1024


class EventJournal:
    """Length-prefixed protobuf journal with a persistent sequence checkpoint.

    Access is serialized by ``SessionBridge._lock``.
    """

    def __init__(
        self,
        root: str,
        *,
        tenant: str,
        namespace: str,
        agent_key: str,
        instance_key: str,
    ) -> None:
        base = (
            Path(root).expanduser()
            if root
            else Path.home() / ".agentscope" / "aistio" / "event-journal"
        )
        base.mkdir(parents=True, exist_ok=True)
        identity = "\0".join((tenant, namespace, agent_key, instance_key)).encode()
        stem = hashlib.sha256(identity).hexdigest()
        self._path = base / f"{stem}.events"
        self._sequences_path = base / f"{stem}.sequences.json"
        self._pending: List[asdp_pb2.SessionEventMsg] = []
        self._latest: Dict[str, int] = {}
        self._load()

    @property
    def latest_sequences(self) -> Dict[str, int]:
        return dict(self._latest)

    def __len__(self) -> int:
        return len(self._pending)

    def append(self, event: "asdp_pb2.SessionEventMsg") -> None:
        payload = event.SerializeToString()
        with self._path.open("ab", buffering=0) as output:
            output.write(struct.pack(">I", len(payload)))
            output.write(payload)
            os.fsync(output.fileno())
        self._pending.append(event)
        self._latest[event.session_id] = max(self._latest.get(event.session_id, 0), event.seq)

    def first(self, limit: int) -> List["asdp_pb2.SessionEventMsg"]:
        return list(self._pending[:limit])

    def acknowledge(self, committed: Dict[str, int]) -> None:
        if not committed:
            return
        self._persist_sequences()
        self._pending = [
            event
            for event in self._pending
            if event.seq > committed.get(event.session_id, -1)
        ]
        self._rewrite()

    def _load(self) -> None:
        if self._sequences_path.exists():
            try:
                data = json.loads(self._sequences_path.read_text(encoding="utf-8"))
                self._latest.update({str(key): int(value) for key, value in data.items()})
            except (OSError, ValueError, TypeError):
                pass
        if not self._path.exists():
            return
        with self._path.open("rb") as source:
            while True:
                header = source.read(4)
                if not header:
                    break
                if len(header) != 4:
                    break
                (length,) = struct.unpack(">I", header)
                if length <= 0 or length > _MAX_RECORD_BYTES:
                    break
                payload = source.read(length)
                if len(payload) != length:
                    break
                try:
                    event = asdp_pb2.SessionEventMsg.FromString(payload)
                except DecodeError:
                    break
                # Protobuf deliberately accepts unknown fields. Random/torn bytes can
                # therefore decode without raising while producing an empty message.
                # A journal record is usable only when its ordering identity survives.
                if not event.session_id or event.seq <= 0:
                    break
                self._pending.append(event)
                self._latest[event.session_id] = max(
                    self._latest.get(event.session_id, 0), event.seq
                )
        self._rewrite()  # trims a torn tail before a future append

    def _persist_sequences(self) -> None:
        temp = self._sequences_path.with_suffix(".tmp")
        with temp.open("w", encoding="utf-8") as output:
            json.dump(self._latest, output, ensure_ascii=False, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp, self._sequences_path)

    def _rewrite(self) -> None:
        temp = self._path.with_suffix(".tmp")
        with temp.open("wb", buffering=0) as output:
            for event in self._pending:
                payload = event.SerializeToString()
                output.write(struct.pack(">I", len(payload)))
                output.write(payload)
            os.fsync(output.fileno())
        os.replace(temp, self._path)

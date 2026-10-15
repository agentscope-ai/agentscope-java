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

"""Outbound HTTP execution transport using the same fenced ASDP message contract."""
from __future__ import annotations

import json
import logging
import queue
from urllib.request import Request, urlopen

from google.protobuf.json_format import MessageToDict, ParseDict

from .grpc import BACKOFF_INITIAL, BACKOFF_MAX, GrpcTransport
from ..proto import asdp_pb2

log = logging.getLogger(__name__)


class HttpPullTransport(GrpcTransport):
    """Retain unacknowledged exchanges and acknowledge commands after local acceptance.

    Event batches still use the bridge's durable journal and dedicated event ACKs.
    An HTTP 200 alone never acknowledges an event batch. No inbound worker port is
    required for execution; the optional contract server is an independent feature.
    """

    @staticmethod
    def event_wire_size(event) -> int:
        return len(json.dumps(MessageToDict(event), ensure_ascii=False).encode())

    @staticmethod
    def validate_event(event) -> None:
        # JSON escaping can be larger than the protobuf journal record. Reject
        # explicitly while the execution producer can fail or publish an artifact.
        if event.ByteSize() > 16 * 1024 * 1024:
            raise ValueError("session event exceeds the 16 MiB journal limit; publish large tool output as an artifact")
        if HttpPullTransport.event_wire_size(event) > 31 * 1024 * 1024:
            raise ValueError("event exceeds the HTTP 32 MiB request limit; publish large tool output as an artifact")

    @staticmethod
    def _batch_count(messages: list[dict]) -> int:
        size = 0
        for index, message in enumerate(messages):
            length = len(json.dumps(message, ensure_ascii=False).encode()) + 1
            if index and size + length > 16 * 1024 * 1024:
                return index
            size += length
        return len(messages)

    def _run(self) -> None:
        pending: list[dict] = []
        accepted: list[str] = []
        backoff = BACKOFF_INITIAL
        connect = MessageToDict(asdp_pb2.ConnectRequest(
            runtime=self._runtime(), sdk_version=self._sdk_version,
            capabilities=self._capabilities, session_affinity=self._session_affinity,
        ))
        while not self._stop.is_set():
            while len(pending) < 128:
                try:
                    message = self._send_q.get_nowait()
                except queue.Empty:
                    break
                if message is None:
                    return
                value = MessageToDict(message)
                value.pop("meta", None)
                pending.append(value)
            count = self._batch_count(pending)
            sent_acks = list(accepted)
            body = {"meta": MessageToDict(self._meta()), "connect": connect,
                    "messages": pending[:count], "ack": sent_acks,
                    "wait_seconds": 0 if pending else 1}
            request = Request(self._addr.rstrip("/") + "/api/v1/agent-runtime/exchange",
                              data=json.dumps(body, ensure_ascii=False).encode(), method="POST",
                              headers={"Authorization": f"Bearer {self._credential}",
                                       "Content-Type": "application/json"})
            try:
                with urlopen(request, timeout=6) as response:
                    result = json.loads(response.read())
                # On an uncertain response retain the whole exchange for retry. On success
                # the event ACK below is the only operation allowed to drain durable events.
                for rejected in result.get("rejected_reports", []):
                    log.warning("HTTP runtime rejected report index=%s: %s",
                                rejected.get("index"), rejected.get("error", "invalid report"))
                pending = pending[count:]
                accepted = [value for value in accepted if value not in sent_acks]
                for value in result.get("messages", []):
                    self._handle_downstream(ParseDict(value, asdp_pb2.Downstream()))
                for command in result.get("commands", []):
                    down = ParseDict(command["message"], asdp_pb2.Downstream())
                    if self._handle_downstream(down):
                        accepted.append(command["id"])
                backoff = BACKOFF_INITIAL
            except Exception as error:
                self._connected.clear()
                log.debug("HTTP runtime exchange failed; retrying retained messages: %s", error)
                self._stop.wait(backoff)
                backoff = min(backoff * 2, BACKOFF_MAX)

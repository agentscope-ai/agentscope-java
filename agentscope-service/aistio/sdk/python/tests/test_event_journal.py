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

import struct

from aistio.event_journal import EventJournal
from aistio.proto import asdp_pb2


def _event(session_id: str, seq: int, content: str):
    return asdp_pb2.SessionEventMsg(
        session_id=session_id, seq=seq, event_type="message", content=content
    )


def test_journal_survives_restart_and_preserves_sequence_watermark(tmp_path):
    kwargs = dict(
        tenant="tenant", namespace="namespace", agent_key="agent", instance_key="instance"
    )
    journal = EventJournal(str(tmp_path), **kwargs)
    journal.append(_event("s1", 1, "first"))
    journal.append(_event("s2", 1, "other"))
    journal.append(_event("s1", 2, "second"))

    reopened = EventJournal(str(tmp_path), **kwargs)
    assert reopened.latest_sequences == {"s1": 2, "s2": 1}
    assert [event.content for event in reopened.first(20)] == ["first", "other", "second"]

    reopened.acknowledge({"s1": 2, "s2": 1})
    empty = EventJournal(str(tmp_path), **kwargs)
    assert len(empty) == 0
    assert empty.latest_sequences == {"s1": 2, "s2": 1}


def test_journal_preserves_complete_payload(tmp_path):
    journal = EventJournal(
        str(tmp_path),
        tenant="tenant",
        namespace="namespace",
        agent_key="agent",
        instance_key="payload",
    )
    content = "x" * 20_000
    journal.append(_event("s1", 1, content))
    assert journal.first(1)[0].content == content


def test_journal_discards_corrupt_tail_but_keeps_complete_prefix(tmp_path):
    kwargs = dict(
        tenant="tenant", namespace="namespace", agent_key="agent", instance_key="torn"
    )
    journal = EventJournal(str(tmp_path), **kwargs)
    journal.append(_event("s1", 1, "safe"))
    event_file = next(tmp_path.glob("*.events"))
    with event_file.open("ab") as output:
        output.write(struct.pack(">I", 2))
        output.write(b"xx")

    reopened = EventJournal(str(tmp_path), **kwargs)
    assert [event.content for event in reopened.first(10)] == ["safe"]

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
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime.ledger import claim_controller


class MemoryStore:
    def __init__(self):
        self.values = {}

    def get(self, name):
        return self.values.get(name)

    def json(self, name, value, immutable=False):
        if immutable and name in self.values:
            return False
        self.values[name] = json.dumps(value).encode()
        return True


class LedgerTest(unittest.TestCase):
    def test_live_or_unknown_owner_cannot_be_replaced(self):
        for phase in ("Running", "Pending", "Unknown"):
            store = MemoryStore()
            first = {"owner_pod": "a", "owner_uid": "uid-a"}
            claim_controller(store, first, lambda name: None)
            prior = dict(store.values)
            with self.assertRaises(RuntimeError):
                claim_controller(store, {"owner_pod": "b", "owner_uid": "uid-b"},
                    lambda name: {"metadata": {"uid": "uid-a"}, "status": {"phase": phase}})
            self.assertEqual(store.values, prior)

    def test_dead_owner_recovery_preserves_history_and_excludes_competitors(self):
        store = MemoryStore()
        claim_controller(store, {"owner_pod": "a", "owner_uid": "uid-a"}, lambda name: None)
        claim_controller(store, {"owner_pod": "b", "owner_uid": "uid-b"}, lambda name: None)
        self.assertEqual(len(store.values), 2)
        with self.assertRaises(RuntimeError):
            claim_controller(store, {"owner_pod": "c", "owner_uid": "uid-c"}, lambda name:
                {"metadata": {"uid": "uid-b"}, "status": {"phase": "Running"}} if name == "b" else None)

    def test_transient_observation_error_is_not_interpreted_as_death(self):
        store = MemoryStore()
        claim_controller(store, {"owner_pod": "a", "owner_uid": "uid-a"}, lambda name: None)
        def unavailable(name):
            raise TimeoutError("API unavailable")
        with self.assertRaises(TimeoutError):
            claim_controller(store, {"owner_pod": "b", "owner_uid": "uid-b"}, unavailable)
        self.assertEqual(len(store.values), 1)

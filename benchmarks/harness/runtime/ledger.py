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
"""Immutable ownership chain: recover a dead controller without racing a live one."""
import json


def claim_controller(store, current, lookup_pod):
    key = "controller-claim.json"
    while True:
        raw = store.get(key)
        if raw is None:
            if store.json(key, current, immutable=True):
                return
            continue  # Another writer won; inspect the authoritative claim.
        owner = json.loads(raw)
        if owner["owner_uid"] == current["owner_uid"]:
            return
        pod = lookup_pod(owner["owner_pod"])
        if (pod and pod["metadata"]["uid"] == owner["owner_uid"]
                and pod.get("status", {}).get("phase") not in ("Succeeded", "Failed")):
            raise RuntimeError("Another controller is live or its state is unknown; recovery is not permitted")
        key = "controller-recoveries/" + owner["owner_uid"] + ".json"

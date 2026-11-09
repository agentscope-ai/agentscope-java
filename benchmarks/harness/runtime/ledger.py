# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
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

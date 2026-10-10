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

"""Opt-in public HTTP regression against an isolated service-api demo deployment.

Start examples/service-api/bootstrap.py first. This creates invocations and test
credentials in the demo Application. Never point
it at a production deployment. No provider calls are made by the default demo.
"""

import argparse
import concurrent.futures
import json
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError

from agentscope_service import ManagementClient, ServiceClient

TERMINAL = {"completed", "partial_succeeded", "failed", "cancelled", "timed_out"}


def expect_http(statuses, operation):
    try:
        operation()
    except HTTPError as error:
        body = error.read().decode()
        assert error.code in statuses, (error.code, body)
        return {"status": error.code, "body": body}
    raise AssertionError("request unexpectedly succeeded")


def wait_terminal(api, invocation_id, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot = api.snapshot(invocation_id)
        if snapshot["invocation"].get("status") in TERMINAL:
            return snapshot
        time.sleep(0.4)
    raise AssertionError(f"invocation did not finish: {snapshot}")


def all_events(api, invocation_id, after=""):
    events = []
    while True:
        page = api.events(invocation_id, after=after, limit=7)
        batch = page["data"]
        events.extend(batch)
        if not batch:
            return events
        after = batch[-1]["cursor"]


def run(config, platform_token, output, targets):
    prefix = "regression-" + uuid.uuid4().hex[:10]
    management = ManagementClient(config["base_url"], platform_token, "default", "default")
    evidence = {"run": prefix, "checks": {}, "invocations": {}}

    def record(name, value=True):
        evidence["checks"][name] = value
        output.write_text(json.dumps(evidence, indent=2))
        print("PASS", name, flush=True)

    clients = {name: ServiceClient(config["base_url"], ep["key"]) for name, ep in config["endpoints"].items()}

    def execute(name):
        api, endpoint = clients[name], config["endpoints"][name]
        receipt = api.submit(endpoint["slug"], {"request": "Check three sources"},
                             idempotency_key=prefix + "-" + name)
        invocation_id = receipt["invocationId"]
        snapshot = wait_terminal(api, invocation_id)
        assert snapshot["invocation"]["status"] == "completed", snapshot
        return name, invocation_id, snapshot

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(execute, targets))
    for name, invocation_id, snapshot in results:
        evidence["invocations"][name] = invocation_id
        record(name + "_completed", {"invocation_id": invocation_id,
                                     "items": len(snapshot["items"]), "tools": len(snapshot["tools"]),
                                     "steps": len(snapshot["steps"]), "result": snapshot["invocation"].get("result")})
    agent_id = evidence["invocations"]["agent"]
    api = clients["agent"]
    endpoint = config["endpoints"]["agent"]
    snapshot = api.snapshot(agent_id)
    assert len(snapshot["items"]) >= 4 and len(snapshot["tools"]) == 3, snapshot
    assert all(tool.get("status") == "completed" for tool in snapshot["tools"].values()), snapshot
    record("full_multi_message_tool_snapshot")
    events = all_events(api, agent_id)
    assert len({event["id"] for event in events}) == len(events)
    assert events[-1]["cursor"] == snapshot["as_of"]
    assert events[-1]["type"] == "invocation.completed", events[-1]
    assert all(event["invocation_id"] == agent_id and event["schema_version"] == 1 for event in events)
    record("paginated_committed_event_history", {"count": len(events)})
    cut = max(1, len(events) // 2)
    assert all_events(api, agent_id, events[cut - 1]["cursor"]) == events[cut:]
    streamed = list(api.stream(agent_id, after=events[cut - 1]["cursor"]))
    assert streamed == events[cut:]
    record("sse_last_event_id_replays_exact_suffix")
    if "team" in evidence["invocations"]:
        record("foreign_cursor_rejected", expect_http({400}, lambda: clients["team"].events(
            evidence["invocations"]["team"], after=snapshot["as_of"])))
    record("malformed_cursor_rejected", expect_http({400}, lambda: api.events(agent_id, after="not-a-cursor")))
    record("wrong_endpoint_key_rejected", expect_http({401, 403, 404}, lambda: clients["team"].snapshot(agent_id)))
    # Same logical owner with a different credential can replay/read work; a key
    # for a different Application cannot take over that Invocation.
    alternate = management.create_credential(endpoint["id"], config["application_id"], name=prefix,
                                               scopes=["invoke", "read", "cancel", "interact"])
    alternate_api = ServiceClient(config["base_url"], alternate["secret"])
    assert alternate_api.snapshot(agent_id)["as_of"] == snapshot["as_of"]
    record("same_application_cross_credential_read")
    replay = alternate_api.submit(endpoint["slug"], {"request": "Check three sources"}, idempotency_key=prefix + "-agent")
    assert replay["invocationId"] == agent_id
    record("cross_credential_idempotent_replay")
    record("conflicting_idempotency_rejected", expect_http({409}, lambda: api.submit(
        endpoint["slug"], {"request": "different"}, idempotency_key=prefix + "-agent")))
    record("invalid_input_rejected", expect_http({400, 422}, lambda: api.submit(
        endpoint["slug"], {"bad": True}, idempotency_key=prefix + "-bad-input")))
    foreign = management.create_application({"tenant": "default", "namespace": "default", "name": prefix + "-foreign"})["application"]
    foreign_key = management.create_credential(endpoint["id"], foreign["id"], name=prefix + "-foreign", scopes=["read"])
    foreign_api = ServiceClient(config["base_url"], foreign_key["secret"])
    record("foreign_application_rejected", expect_http({403, 404}, lambda: foreign_api.snapshot(agent_id)))
    record("read_only_key_cannot_invoke", expect_http({403}, lambda: foreign_api.submit(
        endpoint["slug"], {"request": "Check three sources"}, idempotency_key=prefix + "-read-only")))
    rotated = management.rotate_credential(endpoint["id"], alternate["credential"]["id"])
    assert alternate_api.snapshot(agent_id)["as_of"] == snapshot["as_of"]
    record("rotation_overlap_preserves_old_key")
    management.revoke_credential(endpoint["id"], alternate["credential"]["id"])
    record("old_key_explicitly_revoked", expect_http({401, 403}, lambda: alternate_api.snapshot(agent_id)))
    rotated_api = ServiceClient(config["base_url"], rotated["secret"])
    assert rotated_api.snapshot(agent_id)["as_of"] == snapshot["as_of"]
    record("rotated_key_preserves_application_ownership")
    management.revoke_credential(endpoint["id"], rotated["credential"]["id"])
    record("explicit_key_revocation", expect_http({401, 403}, lambda: rotated_api.snapshot(agent_id)))
    # A real in-flight stream reconnects after several messages/tool calls were
    # produced with no observer. Closing this reader must not cancel execution.
    active = api.submit(endpoint["slug"], {"request": "Check three sources"}, idempotency_key=prefix + "-disconnect")
    active_id = active["invocationId"]
    stream = api.stream(active_id)
    first = next(event for event in stream if event["type"] == "item.delta")
    stream.close()
    time.sleep(5)
    restored = api.snapshot(active_id)
    assert restored["items"] or restored["tools"], restored
    suffix = list(api.stream(active_id, after=restored["as_of"]))
    final = wait_terminal(api, active_id)
    assert final["invocation"]["status"] == "completed" and len(final["tools"]) == 3, final
    assert all(event["cursor"] != restored["as_of"] for event in suffix)
    record("live_disconnect_snapshot_and_suffix", {"invocation_id": active_id, "first_cursor": first["cursor"], "suffix_count": len(suffix)})
    # Stop a running job and verify command receipt is distinct from confirmed
    # termination. Retrying the command must return the same command identity.
    cancellation = api.submit(endpoint["slug"], {"request": "Check three sources"}, idempotency_key=prefix + "-cancel-job")
    cancellation_id = cancellation["invocationId"]
    time.sleep(3)
    command = api.command(cancellation_id, "cancel", idempotency_key=prefix + "-cancel")
    repeated = api.command(cancellation_id, "cancel", idempotency_key=prefix + "-cancel")
    assert repeated["command"]["id"] == command["command"]["id"], (command, repeated)
    cancelled = wait_terminal(api, cancellation_id)
    assert cancelled["invocation"]["status"] == "cancelled", cancelled
    record("cancel_running_and_repeat", {"invocation_id": cancellation_id, "command": command})
    record("capability_discovery", api.capabilities(endpoint["slug"]))
    record("terminal_capability_discovery", api.invocation_capabilities(agent_id))
    assert api.snapshot(agent_id)["as_of"] == snapshot["as_of"]
    record("completed_snapshot_is_stable")
    return evidence


if __name__ == "__main__":
    import os

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--targets", nargs="+", default=["agent", "team", "workflow"], choices=["agent", "team", "workflow"])
    args = parser.parse_args()
    if "agent" not in args.targets:
        parser.error("--targets must include agent, which supplies the shared API contract checks")
    run(json.loads(args.config.read_text()), os.environ["AGENTSCOPE_PLATFORM_TOKEN"], args.output, args.targets)

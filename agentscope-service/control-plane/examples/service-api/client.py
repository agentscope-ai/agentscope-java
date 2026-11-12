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

"""Use the public API only: submit, snapshot/reconnect, commands, approvals and result."""
import argparse
import json
import os
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError

from managed_agents import ServiceClient

TERMINAL = {"completed", "partial_succeeded", "failed", "cancelled", "timed_out", "interrupted"}


def snapshot_status(snapshot, turn_id):
    for event in snapshot["resources"]["turns"]["data"]:
        if event["data"].get("turn_id") == turn_id:
            return event["data"].get("status")
    raise ValueError("Turn is missing from its resource snapshot")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="service-demo.json")
    parser.add_argument("--target", choices=["agent", "team", "workflow"], default="agent")
    parser.add_argument("--turn")
    parser.add_argument("--session")
    parser.add_argument("--key", default=str(uuid.uuid4()))
    parser.add_argument("--cancel", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--input")
    parser.add_argument("--request-id")
    parser.add_argument("--decision", choices=["approved", "rejected"])
    parser.add_argument("--response", default="{}", help="JSON response for a required action")
    parser.add_argument("--human", action="store_true", help="Use MANAGED_AGENTS_PLATFORM_TOKEN for the designated approver")
    parser.add_argument("--command-id", help="Check a previously accepted command")
    parser.add_argument("--snapshot-only", action="store_true")
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text())
    target = config["targets"][args.target]
    api = ServiceClient(config["base_url"], config["key"])
    actor = ServiceClient(config["base_url"], api_token=os.environ["MANAGED_AGENTS_PLATFORM_TOKEN"]) if args.human else api
    if args.turn and not args.session:
        parser.error("--turn requires --session")
    session_id = args.session or api.create_session(target, idempotency_key=args.key + "-session")["id"]
    turn_id = args.turn
    if not turn_id:
        receipt = (api.submit(session_id, {"request": "Check three sources"}, idempotency_key=args.key)
                   if args.target == "workflow" else api.submit(session_id, message="Check three sources", idempotency_key=args.key))
        turn_id = receipt["id"]
    print("Session:", session_id, "Turn:", turn_id, "Request key:", args.key, flush=True)
    command = None
    if args.request_id:
        answer = {**json.loads(args.response), "request_id": args.request_id}
        if args.decision:
            answer["allow"] = args.decision == "approved"
        payload = {"answers": [answer]}
        command = actor.command(session_id, turn_id, "actions", payload, idempotency_key=args.key)
    elif args.cancel or args.resume or args.input:
        kind = "cancel" if args.cancel else "resume" if args.resume else "steer"
        command = actor.command(session_id, turn_id, kind, {"message": args.input} if args.input else {}, idempotency_key=args.key)
    if command:
        print("Accepted command:", json.dumps(command), flush=True)
    if args.command_id:
        print(json.dumps(actor.command_status(session_id, turn_id, args.command_id), indent=2))
    snapshot = api.snapshot(session_id, turn_id)
    print("Restored snapshot:", json.dumps(snapshot, ensure_ascii=False, indent=2), flush=True)
    cursor = snapshot["as_of"]
    status = snapshot_status(snapshot, turn_id)
    if args.snapshot_only:
        return
    while status not in TERMINAL:
        try:
            for event in api.stream(session_id, turn_id=turn_id, after=cursor):
                print(json.dumps(event, ensure_ascii=False), flush=True)
                # Persist cursor only after your application has applied the event.
                cursor = event["cursor"]
                if event["type"].startswith("turn."):
                    status = event["data"].get("status", status)
                    if status in TERMINAL:
                        break
        except HTTPError as error:
            if error.code != 410:
                raise
            snapshot = api.snapshot(session_id, turn_id)
            print("Expired cursor; restored snapshot:", json.dumps(snapshot), flush=True)
            cursor, status = snapshot["as_of"], snapshot_status(snapshot, turn_id)
        except (URLError, TimeoutError, ConnectionError) as error:
            print("Observation interrupted; reconnecting:", error, flush=True)
            time.sleep(1)
    print("Result:", json.dumps(api.turn(session_id, turn_id), ensure_ascii=False, indent=2))
    print("Usage:", json.dumps(api.usage(session_id, turn_id), indent=2))


if __name__ == "__main__":
    main()

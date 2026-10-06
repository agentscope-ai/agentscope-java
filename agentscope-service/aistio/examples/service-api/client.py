"""Use the public API only: submit, snapshot/reconnect, commands, approvals and result."""
import argparse
import json
import os
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError

from aistio import ServiceClient

TERMINAL = {"completed", "partial_succeeded", "failed", "cancelled", "timed_out"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="service-demo.json")
    parser.add_argument("--target", choices=["agent", "team", "workflow"], default="agent")
    parser.add_argument("--invocation")
    parser.add_argument("--key", default=str(uuid.uuid4()))
    parser.add_argument("--cancel", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--input")
    parser.add_argument("--request-id")
    parser.add_argument("--expected-version", type=int)
    parser.add_argument("--decision", choices=["approved", "rejected"])
    parser.add_argument("--response", default="{}", help="JSON response for a required action")
    parser.add_argument("--human", action="store_true", help="Use AGENTSCOPE_PLATFORM_TOKEN for the designated approver")
    parser.add_argument("--command-id", help="Check a previously accepted command")
    parser.add_argument("--snapshot-only", action="store_true")
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text())
    target = config["endpoints"][args.target]
    api = ServiceClient(config["base_url"], target["key"])
    actor = ServiceClient(config["base_url"], api_token=os.environ["AGENTSCOPE_PLATFORM_TOKEN"]) if args.human else api
    invocation_id = args.invocation
    if not invocation_id:
        receipt = api.submit(target["slug"], {"request": "Check three sources"}, idempotency_key=args.key)
        invocation_id = receipt["invocationId"]
    print("Invocation:", invocation_id, "Request key:", args.key, flush=True)
    command = None
    if args.request_id:
        if args.expected_version is None:
            parser.error("--expected-version must come from the required action snapshot")
        payload = {"request_id": args.request_id, "expected_version": args.expected_version,
                   "payload": json.loads(args.response)}
        if args.decision:
            payload["decision"] = args.decision
        command = actor.command(invocation_id, "actions", payload, idempotency_key=args.key)
    elif args.cancel or args.resume or args.input:
        kind = "cancel" if args.cancel else "resume" if args.resume else "inputs"
        command = actor.command(invocation_id, kind, {"message": args.input} if args.input else {}, idempotency_key=args.key)
    if command:
        print("Accepted command:", json.dumps(command), flush=True)
    if args.command_id:
        print(json.dumps(actor.command_status(invocation_id, args.command_id), indent=2))
    snapshot = api.snapshot(invocation_id)
    print("Restored snapshot:", json.dumps(snapshot, ensure_ascii=False, indent=2), flush=True)
    cursor = snapshot["as_of"]
    status = snapshot["invocation"].get("status")
    if args.snapshot_only:
        return
    while status not in TERMINAL:
        try:
            for event in api.stream(invocation_id, after=cursor):
                print(json.dumps(event, ensure_ascii=False), flush=True)
                # Persist cursor only after your application has applied the event.
                cursor = event["cursor"]
                if event["type"].startswith("invocation."):
                    status = event["data"].get("status", status)
                    if status in TERMINAL:
                        break
        except HTTPError as error:
            if error.code != 410:
                raise
            snapshot = api.snapshot(invocation_id)
            print("Expired cursor; restored snapshot:", json.dumps(snapshot), flush=True)
            cursor, status = snapshot["as_of"], snapshot["invocation"].get("status")
        except (URLError, TimeoutError, ConnectionError) as error:
            print("Observation interrupted; reconnecting:", error, flush=True)
            time.sleep(1)
    print("Result:", json.dumps(api.invocation(invocation_id), ensure_ascii=False, indent=2))
    print("Usage:", json.dumps(api.usage(invocation_id).get("usage", {}), indent=2))


if __name__ == "__main__":
    main()

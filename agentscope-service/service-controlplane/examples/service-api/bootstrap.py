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

"""Create Agent, mixed Team and Workflow Session targets; keep demo workers alive."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

from agentscope_service import ManagementClient


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="service-demo.json")
    args = parser.parse_args()
    base = os.environ["AGENTSCOPE_BASE_URL"]
    tenant = os.environ.get("AGENTSCOPE_TENANT", "default")
    namespace = os.environ.get("AGENTSCOPE_NAMESPACE", "default")
    api = ManagementClient(base, os.environ["AGENTSCOPE_PLATFORM_TOKEN"], tenant, namespace)
    scope = {"tenant": tenant, "namespace": namespace}
    suffix = uuid.uuid4().hex[:8]
    name = "api-demo-" + suffix
    application = api.create_application({**scope, "name": name})["application"]
    processes = []
    # Only public IDs are temporary. Durable event outboxes live next to output.
    journal_root = Path(args.output).resolve().with_suffix(".workers") / name
    journal_root.mkdir(parents=True, exist_ok=True)
    try:
        identities = []
        for role, runner in (("leader", "custom"), ("member", "ainvoke")):
            key = name + "-" + role
            # External registration atomically creates the Agent and ready binding.
            # A management-created Agent without a binding remains provisioning.
            identity_file = journal_root / (role + ".json")
            env = {**os.environ, "AGENTSCOPE_AGENT_KEY": key, "AGENTSCOPE_RUNNER": runner,
                   "AGENTSCOPE_IDENTITY_FILE": str(identity_file),
                   "AGENTSCOPE_JOURNAL_DIR": str(journal_root / role)}
            processes.append(subprocess.Popen([sys.executable, str(Path(__file__).with_name("worker.py"))], env=env))
            deadline = time.monotonic() + 35
            while not identity_file.exists():
                if processes[-1].poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Worker failed registration; inspect its log above")
                time.sleep(0.2)
            binding = json.loads(identity_file.read_text())
            api.put_runtime_policy(binding["agentId"], {**scope, "agentId": binding["agentId"],
                "selectionMode": "ordered", "fallbackMode": "disabled", "maxConcurrency": 1,
                "candidates": [{"binding": binding, "requiredCapabilities": {
                    "agent-task": True, "event-reporting": True}}]})
            identities.append(binding)
        leader, member = identities
        members = [{"agentId": member["agentId"], "role": "researcher"}]
        managed_id = os.environ.get("AGENTSCOPE_MANAGED_AGENT_ID")
        if os.environ.get("AGENTSCOPE_MANAGED_AGENT_JSON"):
            managed_id = api.create_agent(json.loads(Path(os.environ["AGENTSCOPE_MANAGED_AGENT_JSON"]).read_text()))["agent"]["id"]
        if managed_id:
            # This is a real Managed runtime; model calls may incur cost.
            members.append({"agentId": managed_id, "role": "managed_reviewer"})
        team = api.create_team({**scope, "name": name, "leaderAgentId": leader["agentId"],
                               "policy": {"maxActiveTasks": 4, "maxChildIssues": 4, "maxChildDepth": 2},
                               "members": members})["team"]
        nodes = [{"key": "research", "type": "agent", "agentId": member["agentId"]},
                 {"key": "review", "type": "team", "teamRef": team["id"]}]
        edges = [{"from": "research", "to": "review"}]
        approver = os.environ.get("AGENTSCOPE_APPROVER_USER_ID")
        if approver:
            application = api.update_application(application["id"], {"version": application["version"],
                "members": [{"userId": approver, "roles": ["viewer", "approver"]}]})["application"]
            nodes.append({"key": "approval", "type": "approval", "approval": {
                "approverType": "human", "approverRef": approver, "prompt": "Approve the final result"}})
            edges.append({"from": "review", "to": "approval"})
        definition = api.create_definition({**scope, "name": name, "draftSpec": {"nodes": nodes, "edges": edges}})["definition"]
        revision = api.publish_definition(definition["id"])["revision"]
        targets = {"agent": {"type": "agent", "id": leader["agentId"]},
                   "team": {"type": "team", "id": team["id"]},
                   "workflow": {"type": "workflow", "id": definition["id"], "revisionId": revision["id"]}}
        credential = api.create_credential(application["id"], name="demo",
            scopes=["invoke", "read", "cancel", "interact"],
            targets=[{"type": value["type"], "id": value["id"]} for value in targets.values()])
        output = {"base_url": base, "application_id": application["id"], "targets": targets,
                  "key": credential["apiKey"], "tenant": tenant, "namespace": namespace}
        path = Path(args.output)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.chmod(path, 0o600)
        with os.fdopen(fd, "w") as file:
            json.dump(output, file, indent=2)
        print("Ready. Config:", path.resolve(), flush=True)
        print("Keep this process running. In another terminal: python client.py --config", args.output, "--target workflow", flush=True)
        while all(process.poll() is None for process in processes):
            time.sleep(1)
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

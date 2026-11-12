#!/usr/bin/env python3
"""Real Agent Service + Go runtime machine protocol, without a control-plane process.

Build agent-service and `make build` in control-plane first.
This uses a local memory runtime; durable tasks/providers are separate checks.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SERVICE_SECRET = "runtime-host-smoke-service-secret-at-least-32-bytes"
SIGNING_SECRET = "runtime-host-smoke-signing-secret-at-least-32-bytes"


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(address, method="GET", body=None, headers=None, raw_response=False):
    data = None if body is None else json.dumps(body).encode()
    req = Request(address, data=data, method=method,
                  headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urlopen(req, timeout=12) as response:
            raw = response.read()
            return response.status, raw if raw_response else json.loads(raw) if raw else None
    except HTTPError as error:
        return error.code, error.read().decode()


def credential(host="fixture-host"):
    # The local fixture uses the same v1 Host signing contract as runtimeauth.
    claims = {"hostKey": host, "tenant": "fixture-tenant", "namespace": "fixture-space",
              "expiresAt": int(time.time()) + 300}
    encoded = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=")
    signed = hmac.new(SIGNING_SECRET.encode(), encoded, hashlib.sha256).digest()
    return "asrh_" + encoded.decode() + "." + base64.urlsafe_b64encode(signed).rstrip(b"=").decode()


def run(version, attached_runtime=None, fixture_dsn=None):
    folder = Path(tempfile.mkdtemp(prefix="managed-agents-orchestration-host-smoke-"))
    processes, handles = [], []
    passed = False
    runtime_port, agent_service_port = port(), port()
    runtime_url = attached_runtime or "http://127.0.0.1:" + str(runtime_port)
    agent_service_url = "http://127.0.0.1:" + str(agent_service_port)
    environment = {"PATH": os.environ["PATH"], "TZ": "UTC",
                   "MANAGED_AGENTS_INTERNAL_TOKEN": SERVICE_SECRET, "MANAGED_AGENTS_JWT_SECRET": SIGNING_SECRET}
    if os.environ.get("JAVA_HOME"):
        environment["JAVA_HOME"] = os.environ["JAVA_HOME"]

    def start(name, command, health):
        handle = (folder / (name + ".log")).open("a")
        handles.append(handle)
        process = subprocess.Popen(command, env=environment, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name + " exited with " + str(process.returncode) + "; inspect " + str(handle.name))
            try:
                if request(health)[0] == 200:
                    return process
            except (URLError, TimeoutError):
                pass
            time.sleep(0.2)
        raise RuntimeError(name + " did not become healthy")

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    from agent_service_postgres_fixture import agent_service_arguments
    agent_service_storage_arguments = agent_service_arguments(version, fixture_dsn or os.environ.get('MANAGED_AGENTS_TEST_POSTGRES_DSN'), folder, environment)

    def start_agent_service():
        jar = ROOT / "agent-service/target" / ("managed-agents-agent-service-" + version + ".jar")
        return start("portal", [shutil.which("java"), "-jar", str(jar),
                    "--server.address=127.0.0.1", "--server.port=" + str(agent_service_port),
                    "--spring.profiles.active=test", "--managed-agents.local-dev=false",
                    "--managed-agents.jwt.secret=" + SIGNING_SECRET, "--managed-agents.vault.master-key=host-smoke-only",
                    "--managed-agents.orchestration-runtime-url=" + runtime_url,
                    "--managed-agents.control-plane-url=http://127.0.0.1:1"] + agent_service_storage_arguments, agent_service_url + "/actuator/health")

    try:
        if not attached_runtime:
            start("runtime", [str(ROOT / "control-plane/bin/control-plane"),
                        "--enable-kubernetes=false", "--enable-runtime-protocol=false", "--scope-mode=multi", "--enable-runtime-workers=true",
                        "--http-bind-address=127.0.0.1:" + str(runtime_port),
                        "--storage-driver=memory", "--local-dev"], runtime_url + "/readyz")
        agentService = start_agent_service()
        host_url = agent_service_url + "/api/v1/runtime-hosts"
        body = {"hostKey": "fixture-host", "tenant": "fixture-tenant", "namespace": "fixture-space",
                "poolName": "configured-pool", "capacity": 2}
        headers = {"Authorization": "Bearer " + credential()}
        service = {"X-Managed-Agents-Internal-Token": SERVICE_SECRET}
        assert request(host_url + "/register", "POST", body)[0] == 401
        assert request(host_url + "/register", "POST", body, service)[0] == 401
        assert request(runtime_url + "/api/internal/runtime/runtime-hosts/register", "POST", body, service)[0] == 401
        assert request(runtime_url + "/api/internal/runtime/runtime-hosts/register", "POST", body, headers)[0] == 401
        assert request(runtime_url + "/api/v1/runtime-hosts/register", "POST", body)[0] in (401, 403)
        assert request(host_url + "/register", "POST", {**body, "namespace": "wrong-space"}, headers)[0] == 401
        status, response = request(host_url + "/register", "POST", body, headers)
        assert status == 200, (status, response)
        host = response["host"]
        owned = host_url + "/" + host["id"]
        generation = host["leaseGeneration"]
        status, renewed = request(owned + "/credentials", "POST", {"generation": generation}, headers)
        assert status == 200 and renewed["hostKey"] == body["hostKey"], (status, renewed)
        headers = {"Authorization": "Bearer " + renewed["runtimeToken"]}
        assert request(owned + "/credentials", "POST", {"generation": generation + 1}, headers)[0] == 409
        assert request(owned + "/heartbeat", "POST", {"generation": generation, "active": 0}, headers)[0] == 200
        assert request(owned + "/heartbeat", "POST", {"generation": generation, "active": 0},
                       {"Authorization": "Bearer " + credential("other-host")})[0] == 401
        assert request(owned + "/not-a-protocol", "POST", {}, headers)[0] == 404
        claim = {"generation": generation, "leaseOwner": "fixture-host", "leaseToken": "fixture-claim",
                 "tenant": "fixture-tenant", "namespace": "fixture-space", "runtimePoolName": "configured-pool"}
        assert request(owned + "/execution-attempts/claim", "POST", claim, headers)[0] == 204
        stop(agentService)
        assert request(runtime_url + "/readyz")[0] == 200
        agentService = start_agent_service()
        assert request(owned + "/heartbeat", "POST", {"generation": generation, "active": 0}, headers)[0] == 200
        status, replacement = request(host_url + "/register", "POST", body, headers)
        assert status == 200 and replacement["host"]["id"] == host["id"], (status, replacement)
        assert replacement["host"]["leaseGeneration"] == generation + 1
        assert request(owned + "/heartbeat", "POST", {"generation": generation, "active": 0}, headers)[0] == 409
        assert request(owned + "/credentials", "POST", {"generation": generation}, headers)[0] == 409
        assert request(owned + "/heartbeat", "POST", {"generation": generation + 1, "active": 0}, headers)[0] == 200
        passed = True
        print(json.dumps({"result": "passed", "control_plane_started": False,
                          "portal_restart": True, "host_scope_and_generation_checked": True,
                          "internal_token_is_not_a_host_identity": True, "empty_claim_status": 204,
                          "runtime_memory_non_durable": True, "task_execution_tested": False}))
    finally:
        for process in reversed(processes):
            stop(process)
        for handle in handles:
            handle.close()
        if passed:
            shutil.rmtree(folder)
        else:
            print("Failure logs retained: " + str(folder))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="2.1.0-SNAPSHOT")
    parser.add_argument("--runtime-url", help="Attach to a separately started local fixture runtime.")
    parser.add_argument("--fixture-dsn", help="Dedicated PostgreSQL database for Agent Service schemas.")
    options = parser.parse_args()
    run(options.version, options.runtime_url, options.fixture_dsn)

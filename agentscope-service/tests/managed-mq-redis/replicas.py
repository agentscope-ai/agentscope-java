#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#     https://www.apache.org/licenses/LICENSE-2.0

"""Deploy isolated Agent Service / HarnessRuntime / Tool Worker processes and verify their real protocol.

The start action only deploys; separate actions run bounded acceptance checks.
Local Sandbox mode is explicit; no ACS or model-provider credential is read from the user's setup.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import shutil
import sys
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[3]
SERVICE = ROOT / "managed-agents"
COMPOSE = [
    "docker", "compose", "-p", "managed-mq-redis", "-f",
    str(Path(__file__).with_name("compose.yaml")),
]
LITE_COMPOSE = [
    "docker", "compose", "-p", "managed-mq-redis-lite", "-f",
    str(Path(__file__).with_name("compose-lite.yaml")),
]
PORTS = {"control": 19081, "agent-service-a": 19084, "agent-service-b": 19184,
         "agent-harness-runtime-a": 19082, "agent-harness-runtime-b": 19182, "tool-worker-a": 19085, "tool-worker-b": 19185}


def jar(module):
    values = [p for p in (SERVICE / module / "target").glob("*.jar")
              if not p.name.endswith(("-sources.jar", "-javadoc.jar", "-tests.jar"))]
    if len(values) != 1:
        raise RuntimeError(f"Build {module} first; expected one executable JAR")
    return values[0]


def run(command, log=None, **kwargs):
    if log:
        with log.open("ab") as output:
            return subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                                  check=True, cwd=ROOT, **kwargs)
    return subprocess.run(command, check=True, cwd=ROOT, **kwargs)


def pg(sql, database="postgres"):
    return run(COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-v",
                          "ON_ERROR_STOP=1", "-U", "managed", "-d", database],
               input=sql.encode(), capture_output=True).stdout.decode()


def fact(state, sql, variables):
    database = json.loads((state / "manifest.json").read_text())["database"]
    command = COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-tA", "-v",
                         "ON_ERROR_STOP=1", "-U", "managed", "-d", database]
    for key, value in variables.items():
        command += ["-v", f"{key}={value}"]
    return run(command, input=sql.encode(), capture_output=True).stdout.decode().strip()


def signal_replica(state, name, operation):
    entry = json.loads((state / "manifest.json").read_text())["processes"][name]
    pid = entry["pid"]
    current = subprocess.run(["ps", "-p", str(pid), "-o", "command="],
                             capture_output=True, text=True)
    marker = f"--workspace-root={state / 'workspace'}" if name == "control" else f"-Dmanaged.replica.state={state}"
    executable = str(SERVICE / "control-plane/bin/control-plane") if name == "control" else "java"
    if name == "mcp-proxy":
        marker, executable = f"--state={state}", "mcp-reply-proxy.py"
    if current.returncode != 0 or marker not in current.stdout or executable not in current.stdout:
        raise RuntimeError("Refusing to signal a process not owned by this replica fixture")
    os.kill(pid, operation)


def wait_ready(name, process, port, log):
    path = "/healthz" if name == "control" else "/actuator/health"
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{name} exited {process.returncode}; inspect {log}")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2) as response:
                if response.status == 200:
                    print(f"READY {name} port={port}", flush=True)
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"{name} was not ready in 120 seconds; inspect {log}")


def lite_fixture(profile):
    if profile == "official":
        return LITE_COMPOSE, "127.0.0.1:19981"
    if profile == "min-offset":
        return ["docker", "compose", "-p", "managed-mq-redis-lite-min-offset", "-f",
                str(Path(__file__).with_name("compose-lite.yaml")), "-f",
                str(Path(__file__).with_name("compose-lite-min-offset.yaml"))], "127.0.0.1:19982"
    raise ValueError("Unknown explicit MQ fixture profile")


def fixture_environment(state):
    manifest = json.loads((state / "manifest.json").read_text())
    _, endpoint = lite_fixture(manifest.get("mq_profile", "official"))
    return {"SPRING_PROFILES_ACTIVE": "jdbc",
                "MANAGED_AGENTS_LOCAL_DEV": "true",
                "MANAGED_AGENTS_WEBHOOK_ALLOW_LOOPBACK_HTTP": str(manifest.get("webhooks", False)).lower(),
                "MANAGED_AGENTS_INTERNAL_TOKEN": "managed-replica-fixture-internal-token-2026",
                "MANAGED_AGENTS_VAULT_MASTER_KEY": "managed-replica-fixture-vault-key-2026",
                "MANAGED_AGENTS_JWT_SECRET": "managed-replica-fixture-jwt-secret-2026",
                "MANAGED_AGENTS_CONTROL_URL": "http://127.0.0.1:19081",
                "MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL": "http://127.0.0.1:19082",
                "MANAGED_AGENTS_AGENT_SERVICE_URL": "http://127.0.0.1:19084",
                "MANAGED_AGENTS_WORKSPACE_ROOT": str(state / "workspace"),
                "CONTROL_PLANE_WORKSPACE_ROOT": str(state / "workspace"),
                "CONTROL_PLANE_ARTIFACT_ROOT": str(state / "artifacts"),
                "MANAGED_AGENTS_RUNTIME_REDIS_URI": "redis://127.0.0.1:16389",
                "MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS": endpoint}


def start(state, task_workers=False, mcp_reply_retry=False, concurrency=4, callbacks=64, mq_profile="official", webhooks=False):
    mq_compose, mq_endpoint = lite_fixture(mq_profile)
    if concurrency < 1 or concurrency > 16:
        raise ValueError("Fixture concurrency must be 1..16")
    if callbacks < 1:
        raise ValueError("Fixture callback capacities must be positive")
    if state.exists():
        raise RuntimeError("State directory must be new; existing data is never reset")
    for port in list(PORTS.values()) + [19281, 19282] + ([19381] if mcp_reply_retry else []):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", port))
    jars = {role: jar(module) for role, module in {
        "agent-service": "agent-service", "agent-harness-runtime": "agent-harness-runtime",
        "tool-worker": "tool-worker"}.items()}
    control = SERVICE / "control-plane/bin/control-plane"
    if not control.is_file():
        raise RuntimeError("Build the control-plane binary first")
    model_classes = SERVICE / "agent-harness-runtime/java/brain-agentscope/target/test-classes"
    if not (model_classes / "io/agentcore/managed/server/replica/ManagedReplicaModel.class").is_file():
        raise RuntimeError("Build dataplane test classes for the explicit deterministic model")
    state.mkdir(parents=True)
    artifacts = {}
    for role, original in jars.items():
        deployed = state / f"{role}.jar"
        shutil.copy2(original, deployed)
        with deployed.open("rb") as content:
            digest = hashlib.file_digest(content, "sha256").hexdigest()
        artifacts[role] = {"path": str(deployed), "sha256": digest}
        jars[role] = deployed
    model_jar = state / "deterministic-model-fixture.jar"
    with zipfile.ZipFile(model_jar, "w") as archive:
        for value in (model_classes / "io/agentcore/managed/server/replica").glob("ManagedReplicaModel*.class"):
            if value.name == "ManagedReplicaModel.class" or value.name.startswith("ManagedReplicaModel$"):
                archive.write(value, str(value.relative_to(model_classes)))
    database = "ma_replicas_" + uuid.uuid4().hex[:12]
    suffix = database.removeprefix("ma_replicas_")
    turns_topic = f"ma_replicas_turns_{suffix}"
    resumes_topic = f"ma_replicas_resumes_{suffix}"
    tool_topic, tool_group = f"ma_replicas_tools_{suffix}", f"ma_replicas_tool_events_{suffix}"
    resume_group = f"ma_replicas_resume_{suffix}"
    execute_topic, execute_group = f"ma_replicas_execute_{suffix}", f"ma_replicas_executors_{suffix}"
    control_topic, control_group = f"ma_replicas_control_{suffix}", f"ma_replicas_controllers_{suffix}"
    harness_runtime_group = f"ma_replicas_brain_{suffix}"
    manifest = {"state": str(state), "database": database, "processes": {}, "artifacts": artifacts, "concurrency": concurrency, "turn_callbacks": callbacks, "task_workers": task_workers, "mcp_reply_retry": mcp_reply_retry, "webhooks": webhooks,
                "ports": PORTS, "model": "deterministic test-class fixture", "sandbox": "local process",
                "mq_transport": "LitePush wildcard/FIFO, one physical queue per parent",
                "mq_profile": mq_profile, "mq_endpoint": mq_endpoint,
                "mq_test_only_derivative": mq_profile == "min-offset",
                "mq_version": "5.5.1-min-offset (test derivative)" if mq_profile == "min-offset" else "5.5.1",
                "mq": {"executes": execute_topic, "execute_group": execute_group, "controls": control_topic, "control_group": control_group, "turns": turns_topic, "tools": tool_topic, "tool_group": tool_group, "resumes": resumes_topic, "resume_group": resume_group,
                       "harness_runtime_group": harness_runtime_group}}
    manifest_path = state / "manifest.json"

    def save():
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    save()
    print(f"STATE {state}\nDATABASE {database}", flush=True)
    pg(f'CREATE DATABASE "{database}";')
    pg("CREATE SCHEMA cp; CREATE SCHEMA rt; CREATE SCHEMA portal; CREATE SCHEMA dp;", database)
    jdbc = f"jdbc:postgresql://127.0.0.1:16991/{database}"
    dsn = f"postgres://managed:managed-local-test@127.0.0.1:16991/{database}?sslmode=disable"
    # Only local fixture values are retained here. Clear provider credentials
    # inherited from a shell; this fixture must never invoke a real model.
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(("MANAGED_AGENTS_", "CLAW_", "CONTROL_PLANE_", "SPRING_"))
           and k not in {"DASHSCOPE_API_KEY", "E2B_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
                         "GOOGLE_API_KEY", "GEMINI_API_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"}}
    env.update(fixture_environment(state))
    run([str(control), "--enable-kubernetes=false", "--storage-driver=postgres",
         f"--storage-dsn={dsn}&search_path=rt", f"--product-dsn={dsn}", "--migrate-only"],
        state / "schema-control.log", env=env)
    for role, schema, component in [("agent-service", "portal", "agent-service"), ("agent-harness-runtime", "dp", "agent-harness-runtime"),
                                    ("agent-service", "ma", "runtime")]:
        run(["java", "-XX:ActiveProcessorCount=2",
             "-Dloader.main=io.agentcore.managed.server.web.persistence.ServiceSchemaMain",
             "-cp", str(jars[role]), "org.springframework.boot.loader.launch.PropertiesLauncher",
             f"--component={component}", f"--schema={schema}", f"--url={jdbc}",
             "--username=managed", "--password=managed-local-test"],
            state / f"schema-{component}.log", env=env)
    roles = {name: f"ma_{name.replace(chr(45), chr(95))}_{suffix}" for name in ["control", "agent-service", "data", "tool-worker"]}
    manifest["database_roles"] = roles
    save()
    grants = COMPOSE + ["exec", "-T", "-e", "PGHOST=127.0.0.1", "-e", f"PGDATABASE={database}",
                       "-e", "PGUSER=managed", "-e", "PGPASSWORD=managed-local-test"]
    for name, prefix in [("control", "CONTROL"), ("agent-service", "AGENT_SERVICE"),
                         ("data", "AGENT_HARNESS_RUNTIME"), ("tool-worker", "TOOL_WORKER")]:
        grants += ["-e", f"{prefix}_DB_USER={roles[name]}", "-e", f"{prefix}_DB_PASSWORD=managed-local-test"]
    run(grants + ["postgres", "sh", "-s"], state / "schema-grants.log",
        input=(SERVICE / "deploy/postgres-roles.sh").read_bytes())
    bindings = [(harness_runtime_group, turns_topic), (tool_group, tool_topic), (resume_group, resumes_topic), (execute_group, execute_topic), (control_group, control_topic)]
    manifest["mq"]["physical_bindings"] = bindings
    save()
    mq_environment = {
        "ROCKETMQ_HOME": "/home/rocketmq/rocketmq-5.5.1",
        "MANAGED_MQ_NAMESRV": "namesrv:9876", "MANAGED_MQ_CLUSTER": "ManagedLiteTest",
        "MANAGED_MQ_QUEUES": "1", "MANAGED_AGENTS_RUNTIME_MQ_TURN_TOPIC": turns_topic,
        "MANAGED_AGENTS_RUNTIME_MQ_TOOL_EVENT_TOPIC": tool_topic,
        "MANAGED_AGENTS_RUNTIME_MQ_TOOL_EXECUTE_TOPIC": execute_topic, "MANAGED_AGENTS_RUNTIME_MQ_TOOL_EXECUTE_GROUP": execute_group,
        "MANAGED_AGENTS_RUNTIME_MQ_TOOL_CONTROL_TOPIC": control_topic, "MANAGED_AGENTS_RUNTIME_MQ_TOOL_CONTROL_GROUP": control_group,
        "MANAGED_AGENTS_RUNTIME_MQ_RESUME_TOPIC": resumes_topic, "MANAGED_AGENTS_RUNTIME_MQ_HARNESS_RUNTIME_GROUP": harness_runtime_group,
        "MANAGED_AGENTS_RUNTIME_MQ_TOOL_EVENTS_GROUP": tool_group,
        "MANAGED_AGENTS_RUNTIME_MQ_RESUME_GROUP": resume_group,
    }
    mq_initialize = mq_compose + ["exec", "-T"]
    for key, value in mq_environment.items():
        mq_initialize += ["-e", key + "=" + value]
    run(mq_initialize + ["broker", "sh", "-s"], state / "mq-admin.log",
        input=(SERVICE / "deploy/managed-mq-init.sh").read_bytes())
    common = [f"--managed-agents.runtime.mq.turn-topic={turns_topic}",
              f"--managed-agents.runtime.mq.tool-execute-topic={execute_topic}", f"--managed-agents.runtime.mq.tool-execute-group={execute_group}",
              f"--managed-agents.runtime.mq.tool-control-topic={control_topic}", f"--managed-agents.runtime.mq.tool-control-group={control_group}",
              f"--managed-agents.runtime.mq.tool-event-topic={tool_topic}",
              f"--managed-agents.runtime.mq.tool-events-group={tool_group}",
              f"--managed-agents.runtime.mq.resume-topic={resumes_topic}",
              f"--managed-agents.runtime.mq.resume-group={resume_group}",
              f"--managed-agents.runtime.mq.harness-runtime-group={harness_runtime_group}",
              "--spring.datasource.hikari.maximum-pool-size=6"]
    for name, port in PORTS.items():
        child_env = dict(env)
        if name == "control":
            business_dsn = dsn.replace("postgres://managed:", f"postgres://{roles['control']}:")
            command = [str(control), "--enable-kubernetes=false", "--enable-runtime-protocol=false",
                       "--enable-runtime-host=false", f"--enable-runtime-workers={str(task_workers).lower()}",
                       "--schema-mode=validate",
                       "--storage-driver=postgres", f"--storage-dsn={business_dsn}&search_path=rt",
                       f"--product-dsn={business_dsn}", "--http-bind-address=127.0.0.1:19081",
                       "--health-probe-bind-address=127.0.0.1:19281",
                       "--metrics-bind-address=127.0.0.1:19282", f"--workspace-root={state / 'workspace'}"]
        else:
            role = name.split("-")[0]
            schema = "portal" if role == "agent-service" else "dp" if role == "agent-harness-runtime" else "ma"
            database_user = roles["data" if role == "agent-harness-runtime" else role]
            command = ["java", "-XX:ActiveProcessorCount=2", "-Xmx384m",
                       f"-Dmanaged.replica.state={state}"]
            if role == "agent-harness-runtime":
                command += [f"-Dloader.path={model_jar}",
                            "-Dloader.main=io.agentcore.managed.server.replica.ManagedReplicaModel",
                            "-cp", str(jars[role]), "org.springframework.boot.loader.launch.PropertiesLauncher"]
            else:
                command += ["-jar", str(jars[role])]
            if role == "tool-worker":
                command.append("--platform-service")
            command += common + [f"--server.port={port}", "--server.address=127.0.0.1",
                                 f"--spring.datasource.url={jdbc}?currentSchema={schema}",
                                 f"--spring.datasource.username={database_user}",
                                 "--spring.datasource.password=managed-local-test",
                                 f"--managed-agents.instance-id={name}"]
            if role == "agent-harness-runtime":
                command += [f"--managed-agents.runtime.concurrency={concurrency}", f"--managed-agents.runtime.resume-concurrency={concurrency}",
                            f"--managed-agents.runtime.turn-callbacks={callbacks}"]
                if mcp_reply_retry:
                    command += ["--managed-agents.control-plane-url=http://127.0.0.1:19381"]
            if role == "tool-worker":
                command += ["--managed-agents.runtime.tool-worker.local-test-mode=true",
                            f"--managed-agents.runtime.tool-worker.local-directory={state / 'sandbox'}",
                            f"--managed-agents.runtime.tool-concurrency={concurrency}"]
        log = state / f"{name}.log"
        with log.open("ab") as output:
            process = subprocess.Popen(command, cwd=ROOT, env=child_env, stdin=subprocess.DEVNULL,
                                       stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        manifest["processes"][name] = {"pid": process.pid, "command": command}
        save()
        wait_ready(name, process, port, log)
        if name == "control" and mcp_reply_retry:
            proxy_command = [sys.executable, str(Path(__file__).with_name("mcp-reply-proxy.py")), f"--state={state}"]
            proxy_log = state / "mcp-proxy.log"
            with proxy_log.open("ab") as output:
                proxy = subprocess.Popen(proxy_command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                         stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            manifest["processes"]["mcp-proxy"] = {"pid": proxy.pid, "command": proxy_command}
            save()
            wait_ready("mcp-proxy", proxy, 19381, proxy_log)
    print("STARTED: deployment only; acceptance checks have not run", flush=True)


def stop(state):
    manifest = json.loads((state / "manifest.json").read_text())
    for name, entry in manifest["processes"].items():
        pid = entry["pid"]
        current = subprocess.run(["ps", "-p", str(pid), "-o", "command="],
                                 capture_output=True, text=True)
        if current.returncode == 0 and str(state) in current.stdout:
            os.kill(pid, signal.SIGTERM)
            print(f"STOP {name} pid={pid}", flush=True)
    print("Logs and isolated database retained; no container or volume was removed")


def request(port, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    value = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, headers=headers)
    try:
        with urllib.request.urlopen(value, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        raise RuntimeError(f"HTTP {failure.code} {path}: {failure.read(8192).decode()}") from failure


def fixture_agent(max_iters=4, extra_tools=()):
    identity = uuid.uuid4().hex[:12]
    environment = request(PORTS["control"], "/api/environments", {
        "name": f"Local-process Sandbox fixture {identity}", "type": "cloud",
        "config": {"provider": "acs", "apiBaseUrl": "http://127.0.0.1:9",
                   "envdBaseUrl": "http://127.0.0.1:9", "templateId": "fixture-only"},
    })
    registration = request(PORTS["control"], "/api/v1/agents", {
        "tenant": "default", "namespace": "default", "agentKey": f"replica-{identity}",
        "displayName": "Replica deployment fixture", "binding": {"kind": "managed"},
        "definition": {"name": "Replica deployment fixture",
                       "system": "Use the explicit fixture model.", "maxIters": max_iters,
                       "defaultEnvironmentId": environment["id"],
                       "tools": [{"type": "agent_toolset", "defaultConfig": {"enabled": False},
                                  "configs": [{"name": name, "enabled": True,
                                               "permissionPolicy": {"type": "always_allow"}}
                                              for name in ["execute", *extra_tools]]}]},
    })
    agent = registration["agent"]
    return environment, agent, identity


def checkpoint_evidence(state, session_id):
    """Check deployed DB manifests against actual shared private state bytes."""
    native = json.loads(fact(state,
        "SELECT coalesce(json_agg(e.payload::json ORDER BY e.sequence),'[]'::json) "
        "FROM (SELECT n.sequence,n.payload FROM ma.native_event n JOIN ma.session s "
        "ON s.scope_key=n.scope_key WHERE s.session_id=:'session' "
        "ORDER BY n.sequence LIMIT 1001) e;", {"session": session_id}))
    if not native or len(native) > 1000:
        raise AssertionError("Native checkpoint verification exceeds its bounded history")
    scope_key = fact(state, "SELECT scope_key FROM ma.session WHERE session_id=:'session';", {"session": session_id})
    captures = []
    iterations = {}
    for event in native:
        payload = json.loads(event["payloadJson"])
        if event["type"] == "step/start":
            iterations.setdefault(event["turnId"], []).append(payload["iteration"])
        if "stateJson" in payload:
            raise AssertionError("Deployed DB still contains inline AgentState")
        reference = payload.get("checkpointRef")
        if reference is None:
            continue
        content = base64.b64decode(fact(state,
            "SELECT encode(payload,'base64') FROM ma.private_object WHERE scope_key=:'scope' "
            "AND object_kind='checkpoint' AND object_key=:'key';",
            {"scope": scope_key,
             "key": reference["identity"]["checkpointId"]}))
        if len(content) != reference["sizeBytes"] or hashlib.sha256(content).hexdigest() != reference["contentHash"]:
            raise AssertionError("Deployed checkpoint bytes differ from their DB manifest")
        image = json.loads(content)
        identity = reference["identity"]
        if (image["identity"] != identity or identity["scope"]["sessionId"] != session_id
                or identity["nativeSequence"] != event["seq"]
                or identity["turnId"] != event["turnId"]
                or identity["runId"] != event["executionRunId"]):
            raise AssertionError("Checkpoint identity is not bound to its native source")
        original = json.loads(image["nativePayloadJson"])
        snapshot = original["stateJson"]
        json.loads(snapshot)
        java_length = len(snapshot.encode("utf-16-le")) // 2
        state_hash = hashlib.sha256(f"{java_length}:{snapshot}".encode()).hexdigest()
        if state_hash != reference["stateHash"] or state_hash != image["stateHash"]:
            raise AssertionError("Checkpoint did not preserve the captured native state")
        continuation = image["continuation"]
        if (continuation["epoch"] != identity["epoch"]
                or continuation["nativeSequence"] != identity["nativeSequence"]
                or continuation["runId"] != identity["runId"]):
            raise AssertionError("Private continuation differs from its native capture identity")
        if "iterationPosition" not in continuation or "sessionConfiguration" not in continuation:
            raise AssertionError("Checkpoint lacks the full host continuation")
        captures.append({"checkpoint_id": identity["checkpointId"], "turn_id": identity["turnId"],
                         "sequence": event["seq"], "epoch": identity["epoch"], "size_bytes": len(content),
                         "content_hash": reference["contentHash"],
                         "resume_phase": continuation["resumePhase"],
                         "iteration_position": continuation["iterationPosition"]})
    terminals = [capture for capture in captures if capture["resume_phase"] == "TERMINAL"]
    if not terminals:
        raise AssertionError("Completed work has no independently captured terminal checkpoint")
    suspensions = json.loads(fact(state,
        "SELECT coalesce(json_agg(p.checkpoint::json),'[]'::json) FROM ma.turn_suspension p "
        "JOIN ma.session s ON s.scope_key=p.scope_key WHERE s.session_id=:'session';",
        {"session": session_id}))
    ids = {capture["checkpoint_id"] for capture in captures}
    if any(reference["identity"]["checkpointId"] not in ids for reference in suspensions):
        raise AssertionError("Suspension does not refer to an accepted native checkpoint")
    head = json.loads(fact(state, "SELECT checkpoint FROM ma.session WHERE session_id=:'session';",
                           {"session": session_id}))
    if head["identity"]["checkpointId"] not in ids or head["identity"]["nativeSequence"] != native[-1]["seq"]:
        raise AssertionError("Session head does not reference its final native capture")
    pending_results = int(fact(state,
        "SELECT count(*) FROM ma.tool_result_outbox o JOIN ma.session s ON s.scope_key=o.scope_key "
        "WHERE s.session_id=:'session' AND o.committed_at IS NULL;", {"session": session_id}))
    if pending_results:
        raise AssertionError("Completed Turns still have an unsettled tool-result publication")
    return {"captures": captures, "terminal_count": len(terminals),
            "suspension_count": len(suspensions), "db_inline_state": False,
            "pending_tool_result_publications": pending_results, "iterations": iterations}


def smoke(state, seconds):
    manifest = json.loads((state / "manifest.json").read_text())
    environment, agent, identity = fixture_agent()
    session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    session_id = session["id"]
    base = f"/v1/sessions/{session_id}"
    label = "first-" + identity
    next_label = "second-" + identity
    first = request(PORTS["agent-service-a"], base + "/turns",
                    {"input": {"message": f"fixture:execute:{seconds}:{label}"}}, uuid.uuid4().hex)
    second = request(PORTS["agent-service-b"], base + "/turns",
                     {"input": {"message": "fixture:echo:" + next_label}}, uuid.uuid4().hex)
    run_path = state / f"smoke-{int(time.time())}.json"
    record = {"environment": environment["id"], "agent": agent["id"], "session": session_id,
              "turns": [first["id"], second["id"]], "seconds": seconds,
              "label": label, "passed": False}
    run_path.write_text(json.dumps(record, indent=2) + "\n")
    deadline = time.monotonic() + seconds + 120
    previous = None
    terminal = {"completed", "failed", "cancelled"}
    while time.monotonic() < deadline:
        first = request(PORTS["agent-service-b"], base + "/turns/" + first["id"])
        second = request(PORTS["agent-service-a"], base + "/turns/" + second["id"])
        statuses = (first["status"], second["status"])
        if statuses != previous:
            print(f"TURNS {statuses}", flush=True)
            previous = statuses
        if first["status"] not in terminal and second["status"] not in {"accepted", "preparing"}:
            # The first Turn can finish between these two HTTP reads. The
            # durable event order below is the authoritative ordering check.
            first = request(PORTS["agent-service-b"], base + "/turns/" + first["id"])
            if first["status"] not in terminal:
                raise AssertionError("Second Turn advanced while the first Turn had no terminal receipt")
        if all(status in terminal for status in statuses):
            break
        time.sleep(1)
    if statuses != ("completed", "completed"):
        raise AssertionError(f"Turns did not complete: {statuses}; inspect replica logs and {run_path}")
    verify_smoke(state, run_path)


def verify_turn_lifecycle(lifecycle, first, second):
    """Each Turn can suspend repeatedly; its successor starts only after its terminal event."""
    cycles = {first: 0, second: 0}
    cursor = 0
    for turn in (first, second):
        if cursor >= len(lifecycle) or lifecycle[cursor] != ("turn.running", turn):
            raise AssertionError(f"Turn did not begin after its predecessor: {lifecycle}")
        cursor += 1
        while cursor < len(lifecycle) and lifecycle[cursor] == ("turn.waiting_tool", turn):
            if cursor + 1 >= len(lifecycle) or lifecycle[cursor + 1] != ("turn.running", turn):
                raise AssertionError(f"Suspension has no matching resumed running event: {lifecycle}")
            cycles[turn] += 1
            cursor += 2
        if cursor >= len(lifecycle) or lifecycle[cursor] != ("turn.completed", turn):
            raise AssertionError(f"Turn did not finish before its successor: {lifecycle}")
        cursor += 1
    if cursor != len(lifecycle):
        raise AssertionError(f"Unexpected extra Turn lifecycle events: {lifecycle}")
    return cycles


def verify_smoke(state, run_path):
    """Audit one admitted run without issuing another tool or changing its durable facts."""
    manifest = json.loads((state / "manifest.json").read_text())
    record = json.loads(run_path.read_text())
    session_id, seconds, label = record["session"], record["seconds"], record["label"]
    next_label = "second-" + label.removeprefix("first-")
    base = f"/v1/sessions/{session_id}"
    first, second = [request(PORTS["agent-service-b"], base + "/turns/" + turn) for turn in record["turns"]]
    statuses = (first["status"], second["status"])
    if statuses != ("completed", "completed"):
        raise AssertionError(f"Smoke audit requires two completed Turns: {statuses}")
    events = request(PORTS["agent-service-b"], base + "/events?limit=100")
    if events["has_more"]:
        raise AssertionError("Fixture event history exceeds its bound; ordering cannot be inferred")
    lifecycle = [(event["type"], event["data"].get("turn_id"))
                 for event in events["data"] if event["type"].startswith("turn.")]
    cycles = verify_turn_lifecycle(lifecycle, first["id"], second["id"])
    if seconds > 5 and cycles[first["id"]] < 1:
        raise AssertionError("Long business tool never durably suspended")
    encoded = json.dumps(events)
    if "harness-runtime-confirmed-tool-worker-" + label not in encoded or "harness-runtime-" + next_label not in encoded:
        raise AssertionError("Public events do not contain both confirmed model outputs")
    side_effects = list((state / "sandbox").glob("*/side-effects.txt"))
    matching = [path for path in side_effects if "start-" + label in path.read_text()]
    if len(matching) != 1 or matching[0].read_text().splitlines() != ["start-" + label, "done-" + label]:
        raise AssertionError("Tool process side effects were not executed exactly once")
    handoffs = json.loads(fact(state,
        "SELECT coalesce(json_agg(json_build_object('turn',p.turn_id,'status',p.status,"
        "'delivery',r.delivery_status,'turn_status',t.status,'completed_epoch',t.execution_epoch,"
        "'suspended_epoch',p.execution_epoch,'checkpoint_epoch',p.checkpoint::json->'identity'->>'epoch',"
        "'sequence',p.checkpoint_sequence,'pending',p.pending_tasks::json,'phase',p.resume_phase) "
        "ORDER BY p.checkpoint_sequence),'[]'::json) FROM ma.turn_suspension p "
        "JOIN ma.turn_resume r ON r.scope_key=p.scope_key AND r.suspension_id=p.suspension_id "
        "JOIN ma.turn t ON t.scope_key=p.scope_key AND t.turn_id=p.turn_id "
        "WHERE p.turn_id IN (:'first',:'second');", {"first": first["id"], "second": second["id"]}))
    tasks = json.loads(fact(state,
        "SELECT coalesce(json_agg(json_build_object('id',task_id,'native_call',native_tool_call_id,"
        "'status',status)),'[]'::json) FROM ma.tool_task WHERE turn_id IN (:'first',:'second');",
        {"first": first["id"], "second": second["id"]}))
    by_task = {task["id"]: task for task in tasks}
    for turn_id, count in cycles.items():
        rows = [row for row in handoffs if row["turn"] == turn_id]
        if len(rows) != count:
            raise AssertionError("Public suspension cycles differ from durable handoffs")
        previous_epoch, previous_sequence = 0, 0
        for row in rows:
            if (row["status"] != "resumed" or row["delivery"] != "queued" or row["turn_status"] != "succeeded"
                    or not previous_epoch < row["suspended_epoch"] < row["completed_epoch"]
                    or row["suspended_epoch"] != int(row["checkpoint_epoch"])
                    or row["sequence"] <= previous_sequence or not row["pending"]
                    or any(task not in by_task or by_task[task]["status"] != "succeeded" for task in row["pending"])):
                raise AssertionError(f"Unsettled or inconsistent durable handoff: {row}")
            previous_epoch, previous_sequence = row["suspended_epoch"], row["sequence"]
    business = [task for task in tasks if task["native_call"] == "replica-execute-" + label]
    if len(business) != 1 or (seconds > 5 and not any(business[0]["id"] in row["pending"] for row in handoffs)):
        raise AssertionError("Expected one stable business tool, durably suspended before its result")
    record["handoffs"] = handoffs
    checkpoints = checkpoint_evidence(state, session_id)
    if checkpoints["iterations"].get(first["id"]) != [0, 1] or checkpoints["iterations"].get(second["id"]) != [0]:
        raise AssertionError("Actual restored reasoning reset its iteration budget or leaked it into the next Turn")
    final_epochs = json.loads(fact(state,
        "SELECT json_object_agg(turn_id,execution_epoch) FROM ma.turn WHERE turn_id IN (:'first',:'second');",
        {"first": first["id"], "second": second["id"]}))
    for turn_id in cycles:
        terminal = [capture for capture in checkpoints["captures"]
                    if capture["turn_id"] == turn_id and capture["resume_phase"] == "TERMINAL"]
        if len(terminal) != 1 or terminal[0]["epoch"] != final_epochs[turn_id]:
            raise AssertionError("Terminal checkpoint was not captured by the final durable execution epoch")
    record.update({"passed": True, "statuses": statuses, "events": events,
                   "checkpoints": checkpoints,
                   "side_effects": str(matching[0]), "database": manifest["database"]})
    run_path.write_text(json.dumps(record, indent=2) + "\n")
    print(f"PASSED actual two-Agent Service / two-HarnessRuntime / two-Tool Worker process smoke: {run_path}", flush=True)


def parallel(state, seconds):
    environment, agent, identity = fixture_agent()
    long_session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    quick_session = request(PORTS["agent-service-b"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    long_base = f"/v1/sessions/{long_session['id']}"
    quick_base = f"/v1/sessions/{quick_session['id']}"
    label = "parallel-" + identity
    first = request(PORTS["agent-service-a"], long_base + "/turns",
                    {"input": {"message": f"fixture:execute:{seconds}:{label}"}}, uuid.uuid4().hex)
    record = {"session": long_session["id"], "parallel_session": quick_session["id"],
              "first_turn": first["id"], "seconds": seconds, "passed": False,
              "environment": environment["id"], "agent": agent["id"]}
    output = state / f"parallel-{int(time.time())}.json"
    output.write_text(json.dumps(record, indent=2) + "\n")
    deadline = time.monotonic() + 60
    started = None
    while time.monotonic() < deadline and started is None:
        started = next((path for path in (state / "sandbox").glob("*/side-effects.txt")
                        if "start-" + label in path.read_text()), None)
        if started is None:
            time.sleep(0.5)
    if started is None:
        raise AssertionError("Long tool did not start in the real Tool Worker process")
    print("PARALLEL long tool started", flush=True)
    second = request(PORTS["agent-service-b"], quick_base + "/turns",
                     {"input": {"message": "fixture:echo:" + label}}, uuid.uuid4().hex)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        second = request(PORTS["agent-service-a"], quick_base + "/turns/" + second["id"])
        if second["status"] == "completed":
            break
        time.sleep(0.5)
    first_status = request(PORTS["agent-service-b"], long_base + "/turns/" + first["id"])["status"]
    if second["status"] != "completed" or first_status not in {"running", "waiting_tool"}:
        raise AssertionError(f"Parallel isolation failed: long={first_status}, quick={second['status']}")
    if started.read_text().splitlines() != ["start-" + label]:
        raise AssertionError("Long subprocess had already finished; parallel isolation is unproven")
    print("PARALLEL quick Turn completed while long tool remained running", flush=True)
    deadline = time.monotonic() + seconds + 120
    while time.monotonic() < deadline:
        first = request(PORTS["agent-service-b"], long_base + "/turns/" + first["id"])
        if first["status"] in {"completed", "failed", "cancelled"}:
            break
        time.sleep(1)
    events = request(PORTS["agent-service-a"], long_base + "/events?limit=100")
    if first["status"] != "completed" or "harness-runtime-confirmed-tool-worker-" + label not in json.dumps(events):
        raise AssertionError("Long Turn did not continue after its tool result")
    if started.read_text().splitlines() != ["start-" + label, "done-" + label]:
        raise AssertionError("Long tool side effects were duplicated")
    record.update({"passed": True, "quick_turn": second["id"], "first_status": first["status"],
                   "quick_status": second["status"], "events": events, "side_effects": str(started)})
    output.write_text(json.dumps(record, indent=2) + "\n")
    print(f"PASSED parallel Sessions sharing one physical MQ parent queue: {output}", flush=True)


def wait_turn(base, turn, statuses, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = request(PORTS["agent-service-b"], base + "/turns/" + turn)
        if value["status"] in statuses:
            return value
        if value["status"] in {"failed", "cancelled", "completed"}:
            raise AssertionError(f"Turn reached {value['status']} before {statuses}")
        time.sleep(0.25)
    raise AssertionError(f"Turn {turn} did not reach {statuses} in {timeout}s")


class EventSubscription:
    """Bounded actual HTTP SSE reader; each connection retains its own durable cursor."""

    def __init__(self, port, base, terminal_turn=None, after=None, count=None):
        self.events, self.failure = [], None
        self.ready = threading.Event()

        def read():
            headers = {"Accept": "text/event-stream"}
            if after:
                headers["Last-Event-ID"] = after
            value = urllib.request.Request(
                f"http://127.0.0.1:{port}{base}/events/stream", headers=headers)
            try:
                with urllib.request.urlopen(value, timeout=45) as response:
                    self.ready.set()
                    fields = {}
                    for raw in response:
                        line = raw.decode().rstrip("\r\n")
                        if not line:
                            if "data" in fields:
                                event = json.loads(fields["data"])
                                if fields.get("id") != event["cursor"] or fields.get("event") != event["type"]:
                                    raise AssertionError("SSE frame and durable event disagree")
                                self.events.append(event)
                                if count and len(self.events) == count:
                                    return
                                if (event["type"] in {"turn.completed", "turn.cancelled"}
                                        and event["data"].get("turn_id") == terminal_turn):
                                    return
                            fields = {}
                        elif not line.startswith(":"):
                            key, _, content = line.partition(":")
                            fields[key] = content.lstrip(" ")
                    raise AssertionError("SSE closed before the expected event")
            except Exception as failure:
                self.failure = failure
                self.ready.set()

        self.thread = threading.Thread(target=read, daemon=True)
        self.thread.start()

    def result(self):
        self.thread.join(50)
        if self.thread.is_alive():
            raise AssertionError("SSE subscriber did not observe the terminal event")
        if self.failure:
            raise self.failure
        return self.events


def controls(state):
    _, agent, identity = fixture_agent()
    session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base = f"/v1/sessions/{session['id']}"
    label = "controls-" + identity
    first = request(PORTS["agent-service-a"], base + "/turns",
                    {"input": {"message": f"fixture:execute:30:{label}"}}, uuid.uuid4().hex)
    second = request(PORTS["agent-service-b"], base + "/turns",
                     {"input": {"message": "fixture:echo:" + label}}, uuid.uuid4().hex)
    wait_turn(base, first["id"], {"waiting_tool"})
    prefix = EventSubscription(PORTS["agent-service-a"], base, count=3)
    complete = EventSubscription(PORTS["agent-service-b"], base, terminal_turn=second["id"])
    first_events = prefix.result()
    if not complete.ready.wait(15) or complete.failure:
        raise AssertionError(f"Live subscriber failed: {complete.failure}")
    inputs = []
    for agentService, kind in [("agent-service-a", "inject"), ("agent-service-b", "steer")]:
        body = {"type": kind, "payload": {"message": f"control:{kind}:{identity}"}}
        key = uuid.uuid4().hex
        value = request(PORTS[agentService], base + f"/turns/{first['id']}/inputs", body, key)
        duplicate = request(PORTS["agent-service-a"], base + f"/turns/{first['id']}/inputs", body, key)
        if value["id"] != duplicate["id"] or value["sequence"] != duplicate["sequence"]:
            raise AssertionError("Cross-Agent Service input retry created another command")
        inputs.append(value)
    print("CONTROLS accepted inject / steer with cross-Agent Service idempotent retries", flush=True)
    wait_turn(base, second["id"], {"completed"})
    observed = complete.result()
    resumed = EventSubscription(PORTS["agent-service-b"], base, terminal_turn=second["id"],
                                after=first_events[-1]["cursor"]).result()
    durable = request(PORTS["agent-service-a"], base + "/events?limit=256")
    if durable["has_more"] or observed != durable["data"] or first_events + resumed != durable["data"]:
        raise AssertionError("SSE live / cross-Agent Service cursor replay differs from durable history")
    receipts = request(PORTS["agent-service-b"], base + f"/turns/{first['id']}/inputs?limit=100")["data"]
    if [(v["id"], v["sequence"], v["status"]) for v in receipts] != [
            (v["id"], v["sequence"], "applied") for v in inputs]:
        raise AssertionError(f"Runtime inputs were not applied exactly once in order: {receipts}")
    output = json.dumps(durable)
    expected = f"control:inject:{identity}|control:steer:{identity}"
    if "harness-runtime-confirmed-tool-worker-" + label not in output or expected not in output:
        raise AssertionError("HarnessRuntime did not observe the ordered controls and confirmed tool result")
    path = state / f"controls-{int(time.time())}.json"
    path.write_text(json.dumps({"passed": True, "session": session["id"], "turns": [first["id"], second["id"]],
                                "inputs": receipts, "events": durable, "sse_subscriber_events": len(observed),
                                "reconnect_prefix": len(first_events), "reconnect_suffix": len(resumed)}, indent=2) + "\n")
    print(f"PASSED actual inputs, two SSE subscribers and cross-Agent Service reconnect: {path}", flush=True)


def cancellation(state):
    _, agent, identity = fixture_agent()
    session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base = f"/v1/sessions/{session['id']}"
    label = "cancel-" + identity
    first = request(PORTS["agent-service-a"], base + "/turns",
                    {"input": {"message": f"fixture:execute:60:{label}"}}, uuid.uuid4().hex)
    second = request(PORTS["agent-service-b"], base + "/turns",
                     {"input": {"message": "fixture:echo:" + label}}, uuid.uuid4().hex)
    wait_turn(base, first["id"], {"waiting_tool"})
    body, key = {"type": "cancel", "payload": {}}, uuid.uuid4().hex
    accepted = request(PORTS["agent-service-b"], base + f"/turns/{first['id']}/inputs", body, key)
    duplicate = request(PORTS["agent-service-a"], base + f"/turns/{first['id']}/inputs", body, key)
    if accepted["id"] != duplicate["id"]:
        raise AssertionError("Cancel retry was not idempotent")
    wait_turn(base, first["id"], {"cancelled"})
    wait_turn(base, second["id"], {"completed"})
    receipt = request(PORTS["agent-service-b"], base + f"/turns/{first['id']}/inputs/{accepted['id']}")
    if receipt["status"] != "applied":
        raise AssertionError("Cancellation was not durably applied")
    matching = [p for p in (state / "sandbox").glob("*/side-effects.txt") if "start-" + label in p.read_text()]
    if len(matching) != 1 or matching[0].read_text().splitlines() != ["start-" + label]:
        raise AssertionError("Cancelled process completed or executed more than once")
    events = request(PORTS["agent-service-a"], base + "/events?limit=256")
    if events["has_more"] or "harness-runtime-" + label not in json.dumps(events):
        raise AssertionError("Queued Turn did not advance after cancellation")
    path = state / f"cancellation-{int(time.time())}.json"
    path.write_text(json.dumps({"passed": True, "session": session["id"], "turns": [first["id"], second["id"]],
                                "input": receipt, "events": events, "side_effects": str(matching[0])}, indent=2) + "\n")
    print(f"PASSED cancellation during suspension, one stopped process and queued successor: {path}", flush=True)


def restart_replica(state, name):
    manifest_path = state / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    entry = manifest["processes"][name]
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("MANAGED_AGENTS_", "CLAW_", "CONTROL_PLANE_", "SPRING_"))
                   and key not in {"DASHSCOPE_API_KEY", "E2B_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
                                   "GOOGLE_API_KEY", "GEMINI_API_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"}}
    environment.update(fixture_environment(state))
    log = state / f"{name}.log"
    with log.open("ab") as output:
        process = subprocess.Popen(entry["command"], cwd=ROOT, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    manifest["processes"][name]["pid"] = process.pid
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    wait_ready(name, process, PORTS[name], log)
    return process.pid


def execution_owners(state, turns):
    result = {}
    for name in ["agent-harness-runtime-a", "agent-harness-runtime-b"]:
        path = state / (name + ".log")
        for line in path.read_text().splitlines():
            if "managed_runtime " not in line:
                continue
            try:
                event = json.loads(line.split("managed_runtime ", 1)[1])
            except json.JSONDecodeError:
                continue
            if event.get("event") == "brain.execution.started" and event.get("turn_id") in turns:
                result[event["turn_id"]] = name
    return result


def tool_worker_restart(state):
    _, agent, identity = fixture_agent()
    session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base, label = f"/v1/sessions/{session['id']}", "tool-worker-restart-" + identity
    first = request(PORTS["agent-service-a"], base + "/turns",
                    {"input": {"message": f"fixture:execute:120:{label}"}}, uuid.uuid4().hex)
    second = request(PORTS["agent-service-b"], base + "/turns",
                     {"input": {"message": "fixture:echo:" + label}}, uuid.uuid4().hex)
    wait_turn(base, first["id"], {"waiting_tool"})
    assert fact(state, "SELECT execution_token IS NULL FROM ma.turn WHERE turn_id=:'turn';",
                {"turn": first["id"]}) == "t", "Suspension retained execution ownership"
    before = fact(state, "SELECT task_id,execution_id,process_handle FROM ma.tool_task WHERE native_tool_call_id=:'call';",
                  {"call": "replica-execute-" + label})
    if not before or not before.rsplit("|", 1)[-1]:
        raise AssertionError("Tool has no persisted process handle before Tool Worker restart")
    manifest = json.loads((state / "manifest.json").read_text())
    old_pids = {name: manifest["processes"][name]["pid"] for name in ["tool-worker-a", "tool-worker-b"]}
    for name in old_pids:
        signal_replica(state, name, signal.SIGKILL)
    time.sleep(1)
    print("HANDS both original workers killed while detached subprocess continues", flush=True)
    new_pids = {name: restart_replica(state, name) for name in old_pids}
    wait_turn(base, second["id"], {"completed"}, timeout=210)
    after = fact(state, "SELECT task_id,execution_id,process_handle FROM ma.tool_task WHERE native_tool_call_id=:'call';",
                 {"call": "replica-execute-" + label})
    if before != after:
        raise AssertionError(f"Replacement Tool Worker changed the original tool execution: {before} -> {after}")
    matching = [p for p in (state / "sandbox").glob("*/side-effects.txt") if "start-" + label in p.read_text()]
    if len(matching) != 1 or matching[0].read_text().splitlines() != ["start-" + label, "done-" + label]:
        raise AssertionError("Replacement Tool Worker repeated or lost the subprocess")
    events = request(PORTS["agent-service-a"], base + "/events?limit=256")
    if events["has_more"] or "harness-runtime-confirmed-tool-worker-" + label not in json.dumps(events):
        raise AssertionError("HarnessRuntime did not recover the original confirmed tool outcome")
    path = state / f"tool-worker-restart-{int(time.time())}.json"
    path.write_text(json.dumps({"passed": True, "session": session["id"], "turns": [first["id"], second["id"]],
                                "original_tool": before, "recovered_tool": after, "old_pids": old_pids,
                                "new_pids": new_pids, "events": events, "side_effects": str(matching[0])}, indent=2) + "\n")
    print(f"PASSED both Tool Worker replacements query the original execution without rerunning: {path}", flush=True)


def redis_restart(state):
    _, agent, identity = fixture_agent()
    session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base, label = f"/v1/sessions/{session['id']}", "redis-restart-" + identity
    first = request(PORTS["agent-service-a"], base + "/turns",
                    {"input": {"message": f"fixture:execute:90:{label}"}}, uuid.uuid4().hex)
    second = request(PORTS["agent-service-b"], base + "/turns",
                     {"input": {"message": "fixture:echo:" + label}}, uuid.uuid4().hex)
    wait_turn(base, first["id"], {"waiting_tool"})
    log = state / "redis-restart-infrastructure.log"
    accepted = None
    try:
        # Stop only this task's named Compose service. Redis data and every other stack are retained.
        run(COMPOSE + ["stop", "redis"], log)
        print("REDIS fixture stopped; accepting steer through persistent Agent Service admission", flush=True)
        accepted = request(PORTS["agent-service-b"], base + f"/turns/{first['id']}/inputs",
                           {"type": "steer", "payload": {"message": "control:steer:" + identity}}, uuid.uuid4().hex)
        if accepted["status"] != "accepted" or accepted["delivery_status"] == "delivered":
            raise AssertionError(f"Offline admission did not retain a pending input: {accepted}")
    finally:
        run(COMPOSE + ["up", "-d", "--wait", "redis"], log)
    print("REDIS fixture restarted; waiting for durable input refill and original tool result", flush=True)
    wait_turn(base, second["id"], {"completed"}, timeout=210)
    receipt = request(PORTS["agent-service-a"], base + f"/turns/{first['id']}/inputs/{accepted['id']}")
    if receipt["status"] != "applied":
        raise AssertionError(f"Offline input was not recovered: {receipt}")
    matching = [p for p in (state / "sandbox").glob("*/side-effects.txt") if "start-" + label in p.read_text()]
    if len(matching) != 1 or matching[0].read_text().splitlines() != ["start-" + label, "done-" + label]:
        raise AssertionError("Redis outage repeated or lost the original tool process")
    events = request(PORTS["agent-service-b"], base + "/events?limit=256")
    encoded = json.dumps(events)
    if events["has_more"] or "harness-runtime-confirmed-tool-worker-" + label not in encoded or "control:steer:" + identity not in encoded:
        raise AssertionError("Recovered HarnessRuntime did not observe tool and durable input")
    path = state / f"redis-restart-{int(time.time())}.json"
    path.write_text(json.dumps({"passed": True, "session": session["id"], "turns": [first["id"], second["id"]],
                                "offline_input": accepted, "recovered_input": receipt,
                                "events": events, "side_effects": str(matching[0])}, indent=2) + "\n")
    print(f"PASSED real Redis outage, offline input recovery and one original tool execution: {path}", flush=True)


def takeover(state, seconds):
    environment, agent, identity = fixture_agent()
    session = request(PORTS["agent-service-a"], "/v1/sessions", {"agent": agent["id"]}, uuid.uuid4().hex)
    base = f"/v1/sessions/{session['id']}"
    # Initialize this fixture workspace before measuring the lease-loss window. Cold-start
    # RPCs are covered by smoke; this check must pause while the requested long tool runs.
    warmup = request(PORTS["agent-service-a"], base + "/turns",
                     {"input": {"message": "fixture:echo:warmup-" + identity}}, uuid.uuid4().hex)
    wait_turn(base, warmup["id"], {"completed"})
    label = "takeover-" + identity
    first = request(PORTS["agent-service-a"], base + "/turns",
                    {"input": {"message": f"fixture:execute:{seconds}:{label}"}}, uuid.uuid4().hex)
    second = request(PORTS["agent-service-b"], base + "/turns",
                     {"input": {"message": "fixture:echo:next-" + label}}, uuid.uuid4().hex)
    output = state / f"takeover-{int(time.time())}.json"
    record = {"session": session["id"], "turns": [first["id"], second["id"]],
              "environment": environment["id"], "agent": agent["id"], "label": label,
              "seconds": seconds, "warmup_turn": warmup["id"], "passed": False}
    output.write_text(json.dumps(record, indent=2) + "\n")
    deadline = time.monotonic() + 60
    started = None
    while time.monotonic() < deadline and started is None:
        started = next((path for path in (state / "sandbox").glob("*/side-effects.txt")
                        if "start-" + label in path.read_text()), None)
        if started is None:
            time.sleep(0.5)
    if started is None:
        raise AssertionError("Takeover tool never started")
    owners = [name for name in ("agent-harness-runtime-a", "agent-harness-runtime-b")
              if "replica-execute-" + label in (state / f"{name}.log").read_text()]
    if len(owners) != 1:
        raise AssertionError(f"Could not identify exactly one initial HarnessRuntime: {owners}")
    owner = owners[0]
    query = "SELECT execution_epoch FROM ma.turn WHERE turn_id=:'turn';"
    before = int(fact(state, query, {"turn": first["id"]}))
    signal_replica(state, owner, signal.SIGSTOP)
    record.update({"paused": owner, "initial_epoch": before})
    output.write_text(json.dumps(record, indent=2) + "\n")
    print(f"TAKEOVER paused {owner} epoch={before}; subprocess keeps running", flush=True)
    try:
        deadline = time.monotonic() + 180
        after = before
        while time.monotonic() < deadline:
            after = int(fact(state, query, {"turn": first["id"]}))
            if after > before:
                break
            time.sleep(2)
        if after <= before:
            raise AssertionError("Another HarnessRuntime did not take over after lease expiry")
        if started.read_text().splitlines() != ["start-" + label]:
            raise AssertionError("Tool finished before takeover; concurrent stale execution is unproven")
        print(f"TAKEOVER new epoch={after}; resuming stale {owner}", flush=True)
        signal_replica(state, owner, signal.SIGCONT)
        deadline = time.monotonic() + seconds + 120
        while time.monotonic() < deadline:
            first = request(PORTS["agent-service-b"], base + "/turns/" + first["id"])
            second = request(PORTS["agent-service-a"], base + "/turns/" + second["id"])
            if first["status"] == second["status"] == "completed":
                break
            if first["status"] in {"failed", "cancelled"}:
                raise AssertionError(f"Recovered Turn terminated as {first['status']}")
            time.sleep(1)
        if first["status"] != "completed" or second["status"] != "completed":
            raise AssertionError("Recovered Turn or its queued successor did not complete")
        tasks = int(fact(state, "SELECT count(*) FROM ma.tool_task WHERE native_tool_call_id=:'call';",
                         {"call": "replica-execute-" + label}))
        if tasks != 1 or started.read_text().splitlines() != ["start-" + label, "done-" + label]:
            raise AssertionError("Takeover created another tool execution or duplicate side effects")
        events = request(PORTS["agent-service-b"], base + "/events?limit=100")
        if events["has_more"] or "harness-runtime-confirmed-tool-worker-" + label not in json.dumps(events):
            raise AssertionError("Recovery public event result is missing or incomplete")
        epochs = json.loads(fact(state,
            "SELECT COALESCE(json_agg(json_build_object('sequence', e.sequence, 'epoch', "
            "((e.payload::jsonb->>'payloadJson')::jsonb#>>'{checkpointRef,identity,epoch}')::bigint) "
            "ORDER BY e.sequence),'[]'::json) FROM ma.native_event e "
            "JOIN ma.session s ON s.scope_key=e.scope_key WHERE s.session_id=:'session' "
            "AND (e.payload::jsonb->>'payloadJson')::jsonb ? 'checkpointRef';",
            {"session": session["id"]}))
        if not epochs or max(item["epoch"] for item in epochs) < after:
            raise AssertionError("Replacement epoch did not commit a checkpoint")
        watermark, sequence = 0, -1
        for committed in epochs:
            if committed["sequence"] > sequence and committed["epoch"] < watermark:
                raise AssertionError("An older epoch appended a later committed checkpoint")
            sequence = committed["sequence"]
            watermark = max(watermark, committed["epoch"])
        record.update({"passed": True, "takeover_epoch": after, "tool_tasks": tasks,
                       "events": events, "side_effects": str(started), "committed_checkpoint_epochs": epochs})
        output.write_text(json.dumps(record, indent=2) + "\n")
        print(f"PASSED paused HarnessRuntime takeover, stale resume and one tool execution: {output}", flush=True)
    finally:
        signal_replica(state, owner, signal.SIGCONT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status", "smoke", "parallel", "takeover", "controls", "cancellation", "toolWorker-restart", "redis-restart"])
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=3)
    parser.add_argument("--mq-profile", choices=["official", "min-offset"], default="official",
                        help="Explicit test-only Broker choice; never an automatic fallback")
    parser.add_argument("--callbacks", type=int, default=64)
    parser.add_argument("--task-workers", action="store_true", help="Enable actual AgentTask Outbox/observer workers in this owned fixture")
    parser.add_argument("--mcp-reply-retry", action="store_true", help="Explicitly discard and retry committed MCP completion replies")
    parser.add_argument("--webhooks", action="store_true", help="Explicitly allow loopback HTTP webhook receivers in this owned fixture")
    parser.add_argument("--concurrency", type=int, default=4, help="Per-replica active Harness / physical Tool Worker tool capacity (1..16)")
    args = parser.parse_args()
    state = args.state.resolve()
    if args.action == "start":
        if args.mcp_reply_retry and not args.task_workers:
            parser.error("--mcp-reply-retry requires --task-workers")
        start(state, args.task_workers, args.mcp_reply_retry, args.concurrency, args.callbacks, args.mq_profile, args.webhooks)
    elif args.action == "stop":
        stop(state)
    elif args.action == "smoke":
        if not 0 <= args.seconds <= 240:
            parser.error("--seconds must be between 0 and 240")
        smoke(state, args.seconds)
    elif args.action == "parallel":
        if not 60 <= args.seconds <= 240:
            parser.error("parallel --seconds must be between 60 and 240")
        parallel(state, args.seconds)
    elif args.action == "takeover":
        if args.seconds != 240:
            parser.error("takeover --seconds must be 240 to keep the subprocess running through expiry")
        takeover(state, args.seconds)
    elif args.action == "controls":
        controls(state)
    elif args.action == "cancellation":
        cancellation(state)
    elif args.action == "toolWorker-restart":
        tool_worker_restart(state)
    elif args.action == "redis-restart":
        redis_restart(state)
    else:
        print((state / "manifest.json").read_text())


if __name__ == "__main__":
    main()

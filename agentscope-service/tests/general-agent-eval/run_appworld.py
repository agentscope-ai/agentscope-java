# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""AppWorld official HTTP MCP track, one isolated API server slot at a time."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid

from appworld import load_task_ids, update_root
from appworld_process import AppWorldProcess
from session_client import SessionClient
from task_selection import select_tasks
from evaluation import (EnvironmentLease, admit_case, cases, digest, failure_status, finish_case,
                        load_profile, remaining, check_preflight, CaseBudgetExceeded,
                        require_completed, EvaluationGateway)


def main(args):
    os.umask(0o077)
    config = json.loads(Path(args.config).read_text())
    evaluation, settings = load_profile(args.evaluation_profile, "appworld")
    planned_repeats = evaluation['sampling']['formalRepeats' if args.formal else 'smokeRepeats']
    if args.repeats is None:
        args.repeats = planned_repeats
    if args.repeats != planned_repeats:
        raise ValueError('Repeat count must match the frozen sampling profile')
    profile = json.loads(Path(args.model_profile).read_text())
    preflight = check_preflight(args.preflight, config, profile, evaluation)
    bindings = profile["brain"]["model"]["bindings"]
    if len(bindings) != 1:
        raise ValueError("Evaluation profile must select exactly one model")
    model, model_binding = next(iter(bindings.items()))
    update_root(args.root)
    split = getattr(args, 'split', 'dev')
    tasks = select_tasks(load_task_ids(split), getattr(args, 'task_list', None), args.offset, args.count)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    schedule = list(cases(config['targets'], tasks, args.repeats))

    def save(name, data):
        (output / name).write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str))

    save("manifest.json", {"benchmark": "AppWorld", "track": "official-http-mcp",
        "commit": config["appworld_commit"], "data_version": "0.2.0", "split": split, "tasks": tasks,
        "repeats": args.repeats, "model": model, "max_iters": evaluation["nativeIterationGuard"], "model_binding": model_binding, "model_profile_sha256": digest(profile),
        "evaluation_profile": evaluation, "evaluation_profile_sha256": digest(evaluation),
        "targets": [{"name": t["name"], "namespace": t["namespace"]} for t in config['targets']],
        "note": "Mutable model alias. Official MCP output_type=content_only. No native shell tools."})
    lease = EnvironmentLease(config, "appworld", output)
    save('preflight.json', preflight)
    for target, task, repeat in schedule:
        client = SessionClient(target["cluster_url"], target["headers"])
        environments = client.api("/api/environments")
        environment = next(e["id"] for e in environments if e["name"] == "general-agent-eval-20261030")
        agent, gateway, world = None, None, None
        trial = f'{target["name"]}-{task}-{repeat}-{uuid.uuid4().hex[:8]}'
        row = {"trial": trial, "task": task, "target": target["name"], "repeat": repeat,
               "started": time.time(), "status": "running"}
        save(trial + ".json", row)
        try:
            request = urllib.request.Request('http://127.0.0.1:9000/_evaluation/reset',
                json.dumps({'owner': lease.owner}).encode(),
                {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + config['control_key']})
            with urllib.request.urlopen(request, timeout=30) as response:
                row['environment_reset'] = json.load(response)
            world = AppWorldProcess(args.root, task, trial)
            initial = world.initial['databases']
            row['initial_hash'] = digest(initial)
            save(trial + '-initial-databases.json', initial)
            if agent is None:
                definition = {"name": "AppWorld MCP evaluation", "model": model,
                    "maxIters": evaluation["nativeIterationGuard"], "defaultEnvironmentId": environment,
                    "system": "You are a personal assistant operating AppWorld applications through the provided tools. "
                        "Read tool descriptions and complete the user task. Do not invent results. "
                        "Use the supervisor tools to obtain account information as needed. "
                        "When finished, call the supervisor complete_task API, including any requested answer.",
                    "tools": [{"type": "agent_toolset", "defaultConfig": {"enabled": False}},
                        {"type": "mcp_toolset", "mcpServerName": "appworld",
                         "defaultConfig": {"enabled": True, "permissionPolicy": {"type": "always_allow"}}}],
                    "mcpServers": [{"name": "appworld", "transport": "http", "required": True,
                        "url": config["evaluation_gateway_cluster_base"].rstrip('/') + '/appworld/' + trial + '/mcp/',
                        "headers": {"Authorization": "Bearer " + config['tool_key']},
                        "timeout": "PT90S", "initializationTimeout": "PT90S"}]}
                gateway = EvaluationGateway(config, lease, row, definition['system'], row['initial_hash'],
                                            f"appworld:{config['appworld_commit']}:0.2.0:{task}")
                created = client.api("/api/v1/agents", {"agentKey": "appworld-eval-" + uuid.uuid4().hex[:12],
                    "displayName": definition["name"], "binding": {"kind": "managed"},
                    "definition": definition})
                agent = created["agent"]["id"]
            create_key, send_key = str(uuid.uuid4()), str(uuid.uuid4())
            row.update(agent=agent, environment=environment, create_key=create_key, send_key=send_key)
            save(trial + ".json", row)
            session = client.create(agent, environment,
                {"benchmark": "appworld-" + split + "-mcp", "trial": trial}, create_key)["id"]
            row["session"] = session
            row["budget"] = admit_case(client, session, settings, row["started"])
            row["definition_sha256"] = digest({**definition, "defaultEnvironmentId": "<evaluation-environment>", "mcpServers": [{k: v for k, v in server.items() if k not in {"headers", "url"}} for server in definition["mcpServers"]]})
            save(trial + "-definition.json", {**definition, "mcpServers": [
                {k: v for k, v in server.items() if k != "headers"}
                for server in definition["mcpServers"]]})
            task_info = world.initial['task']
            row['task_sha256'] = digest(task_info)
            save(trial + '-initial.json', task_info)
            save(trial + ".json", row)
            remaining(row['budget'], settings['turnTimeoutSeconds'])
            turn = client.send(session, json.dumps(task_info, default=str), send_key)
            row["turn"] = turn
            save(trial + ".json", row)
            state, events = client.wait(session, turn, remaining(row["budget"], settings["turnTimeoutSeconds"]), monitor_budget=True)
            save(trial + "-trajectory.json", {"state": state, "events": events})
            require_completed(state)
            score = world.evaluate()
            row.update(status="completed", result=score, reward=int(score["success"]))
            world.close()
        except Exception as error:
            row.update(status=failure_status(error), error=type(error).__name__ + ": " + str(error))
        finally:
            if world is not None:
                world.stop()
        finish_case(client, row)
        if gateway:
            try:
                save(trial + '-audit.json', gateway.snapshot())
            except Exception as error:
                row.update(status='unknown_error', audit_error=type(error).__name__)
        row["elapsed_seconds"] = time.time() - row["started"]
        save(trial + ".json", row)
        with (output / "results.jsonl").open("a") as stream:
            stream.write(json.dumps(row, default=str) + "\n")
        print(json.dumps({k: row.get(k) for k in ["trial", "status", "reward", "elapsed_seconds"]}), flush=True)
        if row["status"] != "completed":
            raise RuntimeError("Trial failed; preserve world and inspect before reusing the slot")

    lease.release()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation-profile", required=True, help="Common case budgets and sampling profile")
    parser.add_argument('--preflight', required=True, help='Successful deployment preflight JSON')
    parser.add_argument("--model-profile", required=True, help="Shared Helm model profile JSON")
    parser.add_argument("--config", required=True)
    parser.add_argument("--root", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--split", choices=['train', 'dev', 'test_normal', 'test_challenge'], default='dev')
    parser.add_argument("--task-list", help="JSON array of unique task IDs from the selected split")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--repeats", type=int)
    parser.add_argument("--formal", action="store_true", help="Use the frozen formal repeat count")
    main(parser.parse_args())

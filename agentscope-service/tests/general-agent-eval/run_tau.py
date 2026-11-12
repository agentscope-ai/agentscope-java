# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Sequential original tau-bench retail trials through Managed Agents Sessions."""
import argparse
import json
import os
from pathlib import Path
import time
import uuid

import litellm
import tau_bench.envs.user as users
from session_client import SessionClient
from evaluation import (EnvironmentLease, admit_case, cases, digest, failure_status, finish_case,
                        load_profile, remaining, check_preflight, CaseBudgetExceeded,
                        HarnessFailure, ProviderFailure, require_completed, EvaluationGateway)


def main(args):
    os.umask(0o077)
    config = json.loads(Path(args.config).read_text())
    evaluation, settings = load_profile(args.evaluation_profile, "tau")
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
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    usage = []

    def completion(**kwargs):
        kwargs["temperature"] = evaluation["simulator"]["temperature"]
        started = time.time()
        evidence = {'started': started, 'model': kwargs.get('model'),
                    'temperature': kwargs['temperature'],
                    'request_sha256': digest(kwargs), 'status': 'pending'}
        usage.append(evidence)
        try:
            result = litellm.completion(**kwargs, api_base=config["simulator_base"],
                api_key=config["simulator_key"], timeout=evaluation["simulator"]["timeoutSeconds"], num_retries=0)
        except Exception as error:
            evidence.update(status='provider_error', elapsed_seconds=time.time() - started,
                            error_type=type(error).__name__)
            raise ProviderFailure('Simulator request failed: ' + type(error).__name__) from None
        evidence.update(status='completed', elapsed_seconds=time.time() - started,
                        returned_model=result.model, usage=result.usage.model_dump() if result.usage else None)
        return result

    users.completion = completion

    def control(path, body):
        return lease.call(path, body)

    def save(name, data):
        (output / name).write_text(json.dumps(data, ensure_ascii=False, indent=2))

    manifest = {"benchmark": "original-tau-bench", "domain": "retail", "split": "test",
        "commit": config["tau_commit"], "tasks": args.tasks, "repeats": args.repeats,
        "model": model, "simulator_model": evaluation["simulator"]["model"], "model_binding": model_binding, "model_profile_sha256": digest(profile),
        "evaluation_profile": evaluation, "evaluation_profile_sha256": digest(evaluation),
        "max_actions": settings["maxActions"], "turn_timeout_seconds": settings["turnTimeoutSeconds"],
        "note": "Mutable model alias; not a pinned model snapshot. MCP names have a connection prefix.",
        "targets": [{"name": t["name"], "namespace": t["namespace"]} for t in config["targets"]]}
    save("manifest.json", manifest)
    save('preflight.json', preflight)
    schedule = list(cases(config['targets'], args.tasks, args.repeats))
    lease = EnvironmentLease(config, "tau", output)
    for target, task, repeat in schedule:
        client = SessionClient(target["cluster_url"], target["headers"])
        environments = client.api("/api/environments")
        environment = next(e["id"] for e in environments if e["name"] == "general-agent-eval-20261030")
        agent = None
        trial = f'{target["name"]}-{task}-{repeat}-{uuid.uuid4().hex[:8]}'
        row = {"trial": trial, "target": target["name"], "task": task,
               "repeat": repeat, "started": time.time(), "status": "running"}
        transcript, usage_start, gateway = [], len(usage), None
        try:
            prepared = control("/reset", {"task": task, "split": "test", "trial": trial})
            row["initial_hash"] = prepared["initial_hash"]
            row['tools_sha256'] = digest(prepared['tools'])
            row['task_sha256'] = digest(prepared['instruction'])
            save(trial + '-initial.json', prepared)
            gateway = EvaluationGateway(config, lease, row, prepared['wiki'], prepared['initial_hash'],
                                        f"tau:{config['tau_commit']}:retail:test:{task}")
            if agent is None:
                definition = {"name": "Original tau retail evaluation", "system": prepared["wiki"],
                    "model": model, "maxIters": evaluation["nativeIterationGuard"],
                    "defaultEnvironmentId": environment,
                    "tools": [{"type": "agent_toolset", "defaultConfig": {"enabled": False}},
                        {"type": "mcp_toolset", "mcpServerName": "retail",
                         "defaultConfig": {"enabled": True, "permissionPolicy": {"type": "always_allow"}}}],
                    "mcpServers": [{"name": "retail", "transport": "http", "required": True,
                        "url": config['evaluation_gateway_cluster_base'].rstrip('/') + '/tau/' + trial + '/mcp/',
                        "headers": {"Authorization": "Bearer " + config["tool_key"], "X-Eval-Trial": trial},
                        "timeout": "PT60S", "initializationTimeout": "PT30S"}]}
                created = client.api("/api/v1/agents", {"agentKey": "tau-eval-" + uuid.uuid4().hex[:12],
                    "displayName": definition["name"], "binding": {"kind": "managed"},
                    "definition": definition})
                agent = created["agent"]["id"]
            simulator = users.LLMUserSimulationEnv(evaluation["simulator"]["model"], "openai")
            observation = simulator.reset(prepared["instruction"])
            create_key = str(uuid.uuid4())
            row.update(agent=agent, environment=environment, create_key=create_key)
            save(trial + ".json", row)
            session = client.create(agent, environment,
                {"benchmark": "original-tau-retail", "trial": trial}, create_key)["id"]
            row["session"] = session
            row["budget"] = admit_case(client, session, settings, row["started"])
            row["definition_sha256"] = digest({**definition, "defaultEnvironmentId": "<evaluation-environment>", "mcpServers": [{k: v for k, v in server.items() if k not in {"headers", "url"}} for server in definition["mcpServers"]]})
            save(trial + "-definition.json", {**definition, "mcpServers": [
                {k: v for k, v in server.items() if k != "headers"}
                for server in definition["mcpServers"]]})
            save(trial + ".json", row)
            for index in range(settings["maxActions"]):
                send_key = str(uuid.uuid4())
                row.update(pending_message=observation, pending_key=send_key)
                save(trial + ".json", row)
                remaining(row['budget'], settings['turnTimeoutSeconds'])
                turn = client.send(session, observation, send_key)
                row["turn"] = turn
                save(trial + ".json", row)
                state, events = client.wait(session, turn, remaining(row["budget"], settings["turnTimeoutSeconds"]), monitor_budget=True)
                transcript.append({"user": observation, "turn": turn, "state": state, "events": events})
                save(trial + "-trajectory.json", transcript)
                require_completed(state)
                result = control("/result", {})
                if result["done"]:
                    break
                response = client.text(events)
                if not response:
                    raise HarnessFailure("Completed turn has no assistant text")
                remaining(row['budget'], settings['turnTimeoutSeconds'])
                observation = simulator.step(response)
                result = control("/respond", {"content": response, "user_observation": observation})
                if result["done"]:
                    break
            if not result['done']:
                raise CaseBudgetExceeded('Official action limit reached without a terminal environment')
            row.update(status="completed", result=result)
        except Exception as error:
            # Keep processing failures visible; never turn them into benchmark scores.
            row.update(status=failure_status(error), error=type(error).__name__ + ": " + str(error))
        finally:
            finish_case(client, row)
            if gateway:
                try:
                    save(trial + '-audit.json', gateway.snapshot())
                except Exception as error:
                    row.update(status='unknown_error', audit_error=type(error).__name__)
            row["simulator_usage"] = usage[usage_start:]
            row["elapsed_seconds"] = time.time() - row["started"]
            save(trial + ".json", row)
            with (output / "results.jsonl").open("a") as stream:
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
            print(json.dumps({k: row.get(k) for k in ["trial", "status", "elapsed_seconds"]}), flush=True)
        if row["status"] != "completed":
            # Stop on uncertain Session/tool state; never reuse its environment slot.
            raise RuntimeError("Trial failed; inspect saved state before resuming")

    lease.release()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation-profile", required=True, help="Common case budgets and sampling profile")
    parser.add_argument('--preflight', required=True, help='Successful deployment preflight JSON')
    parser.add_argument("--model-profile", required=True, help="Shared Helm model profile JSON")
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--tasks", type=int, nargs="+", default=[0])
    parser.add_argument("--repeats", type=int)
    parser.add_argument("--formal", action="store_true", help="Use the frozen formal repeat count")
    main(parser.parse_args())

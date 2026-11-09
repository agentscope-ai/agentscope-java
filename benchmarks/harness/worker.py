# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Official business environments and a serial Java tool-runner integration."""
import hashlib
import copy
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import multiprocessing
import os
from pathlib import Path
import random
import threading
import time
import traceback
import urllib.error
import urllib.request


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, default=str).encode()


def summary(cases, planned):
    scored = [c for c in cases if c.get("score") is not None]
    successes = sum(c["score"] == 1 for c in scored)
    return {"planned": planned, "attempted": len(cases), "valid_scores": len(scored),
            "scoring_coverage": len(scored) / planned if planned else None,
            "official_success_on_scored": successes / len(scored) if scored else None,
            "end_to_end_success": successes / planned if planned else None,
            "missing": planned - len(cases)}


def java(path, body=None):
    data = None if body is None else encoded(body)
    request = urllib.request.Request("http://127.0.0.1:8080" + path, data=data,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=930) as response:
        return json.load(response)


class Tau:
    def __init__(self, task_id, trace):
        domain, separator, index = str(task_id).partition(":")
        if not separator:
            domain, index = "retail", domain
        if domain not in ("retail", "airline") or not index.isdecimal():
            raise ValueError("Expected a retail/airline task index")
        task_index = int(index)
        from tau_bench.envs import get_env
        from tau_bench.envs import user
        from tau_bench.types import Action, RESPOND_ACTION_NAME
        self.Action, self.respond = Action, RESPOND_ACTION_NAME
        self.trace = trace
        original = user.completion

        def completion(**kwargs):
            request = copy.deepcopy(kwargs)
            result = original(**kwargs, api_key=os.environ["DASHSCOPE_API_KEY"],
                              api_base=os.environ["MODEL_BASE_URL"], timeout=90,
                              num_retries=0, temperature=0, max_tokens=8192)
            result._hidden_params.setdefault("response_cost", None)
            trace("user_model", {"request": request, "response": result.model_dump()})
            return result

        user.completion = completion
        try:
            self.env = get_env(domain, user_strategy="llm", user_model=os.environ["MODEL_ID"],
                               user_provider="openai", task_split="test", task_index=task_index)
            self.observation = self.env.reset(task_index=task_index).observation
        except BaseException:
            user.completion = original
            raise
        self.restore = lambda: setattr(user, "completion", original)
        self.done = False
        self.reward = None
        official_score = self.env.calculate_reward

        def score():
            # The upstream scorer mutates its environment while replaying reference actions.
            trace("final_business_state", {"hash": self.env.get_data_hash(), "data": copy.deepcopy(self.env.data)})
            result = official_score()
            trace("official_reward", result.model_dump())
            return result

        self.env.calculate_reward = score
        self.system = self.env.wiki
        self.tools = [tool["function"] for tool in self.env.tools_info]
        self.initial_hash = self.env.get_data_hash()
        trace("initial_business_state", {"hash": self.initial_hash, "data": copy.deepcopy(self.env.data)})

    def step(self, name, arguments):
        if self.done:
            return "The task has already terminated."
        response = self.env.step(self.Action(name=name, kwargs=arguments))
        self.done = response.done
        if self.done:
            self.reward = response.info.reward_info.model_dump()
        return response.observation

    def tool(self, name, arguments):
        return self.step(name, arguments)

    def respond_to_agent(self, text):
        return self.step(self.respond, {"content": text})

    def score(self):
        if not self.done or self.reward is None:
            return None, None, "official_termination_not_reached"
        return self.reward["reward"], self.reward, None

    def close(self):
        self.restore()


class AppWorldTask:
    def __init__(self, task_id, trace):
        from appworld import AppWorld
        self.world = AppWorld(task_id=task_id, experiment_name="harness-" + task_id,
                              random_seed=0, max_interactions=120, timeout_seconds=60)
        self.trace = trace
        self.done = False
        self.system = (
            "Complete the user's task in AppWorld using execute_code. Python execution persists "
            "between tool calls. Use the provided apis object. Discover documentation with "
            "apis.api_docs.show_app_descriptions(), apis.api_docs.show_api_descriptions(app_name=...), "
            "and apis.api_docs.show_api_doc(app_name=..., api_name=...). Print results you need to inspect. "
            "Use the supervisor app to get credentials and call supervisor.complete_task when finished. "
            "Completion contract: pass answer only if the task asks a question. The answer must be "
            "concise (a number, yes/no, or the requested entity), without explanation. For tasks that "
            "only request actions, omit answer (its default is None). Before changing business state, "
            "identify the requested records and every selection constraint; verify the final state "
            "against those original constraints before completing the task. "
            "Only operate on the simulated business APIs. Do not inspect implementation files or tests."
        )
        self.observation = (
            "Simulated world date and time: " + str(self.world.task.datetime) + ". "
            "Resolve all relative dates and expiry checks against this simulated time, "
            "regardless of the host computer's current date.\n"
            + self.world.task.instruction + "\nSupervisor: " + json.dumps(self.world.task.supervisor)
        )
        self.tools = [{"name": "execute_code", "description": "Execute Python code in the official AppWorld environment.",
                       "parameters": {"type": "object", "properties": {"code": {"type": "string"}}, "required": ["code"]}}]
        self.initial_hash = hashlib.sha256(encoded({"task_id": task_id, "instruction": self.observation})).hexdigest()

    def tool(self, name, arguments):
        if name != "execute_code":
            raise ValueError("Unknown business tool")
        if self.done:
            return "Task already completed."
        result = self.world.execute(arguments["code"])
        self.done = self.world.task_completed()
        return result

    def respond_to_agent(self, text):
        self.done = True
        return ""

    def score(self):
        from appworld.evaluator import TestTracker

        # Upstream suppress_errors catches all exceptions, including broken databases.
        # This task owns an isolated process: scope the guard to scoring and restore it.
        original_exit = TestTracker.__exit__

        def assertions_only(tracker, error_type, error, tb):
            if error_type is not None and not issubclass(error_type, AssertionError):
                return False
            return original_exit(tracker, error_type, error, tb)

        TestTracker.__exit__ = assertions_only
        try:
            result = self.world.evaluate(suppress_errors=True)
            raw = result.to_dict(stats_only=False)
            score, reason = float(result.success), None
        except Exception as error:
            score, reason = None, "evaluator_exception"
            raw = {"error_type": type(error).__name__, "error": str(error),
                   "traceback": traceback.format_exc()}
        finally:
            TestTracker.__exit__ = original_exit
        self.trace("official_reward", raw)
        return score, raw, reason

    def close(self):
        self.world.close()


def appworld_process(connection, task_id, factory):
    # AppWorld freezes process time and uses SIGALRM. Keep both in a dedicated
    # main thread, without cloud credentials, away from OSS signing and deadlines.
    for name in ("DASHSCOPE_API_KEY", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET"):
        os.environ.pop(name, None)
    adapter = None
    events = []
    try:
        adapter = factory(task_id, lambda kind, value: events.append((kind, value)))
        connection.send({"value": {name: getattr(adapter, name) for name in
                                  ("system", "observation", "tools", "initial_hash", "done")}, "events": events})
        while True:
            operation, arguments = connection.recv()
            events = []
            try:
                value = getattr(adapter, operation)(*arguments)
                connection.send({"value": value, "done": adapter.done, "events": events})
                if operation == "close":
                    adapter = None
                    break
            except Exception as error:
                connection.send({"error_type": type(error).__name__, "events": events})
    except Exception as error:
        connection.send({"error_type": type(error).__name__, "events": events})
    finally:
        if adapter is not None:
            adapter.close()
        connection.close()


class IsolatedAppWorldTask:
    def __init__(self, task_id, trace, _factory=AppWorldTask):
        self.trace = trace
        context = multiprocessing.get_context("spawn")
        self.connection, child = context.Pipe()
        self.process = context.Process(target=appworld_process, args=(child, task_id, _factory), daemon=True)
        self.process.start()
        child.close()
        try:
            self.__dict__.update(self.receive())
        except BaseException:
            self.process.terminate()
            self.process.join(timeout=10)
            self.connection.close()
            raise

    def receive(self):
        if not self.connection.poll(180):
            raise TimeoutError("AppWorld subprocess response timed out")
        response = self.connection.recv()
        for kind, value in response.get("events", []):
            self.trace(kind, value)
        if "error_type" in response:
            raise RuntimeError("AppWorld subprocess: " + response["error_type"])
        if "done" in response:
            self.done = response["done"]
        return response["value"]

    def call(self, operation, *arguments):
        self.connection.send((operation, arguments))
        return self.receive()

    def tool(self, name, arguments):
        return self.call("tool", name, arguments)

    def respond_to_agent(self, text):
        return self.call("respond_to_agent", text)

    def score(self):
        return self.call("score")

    def close(self):
        try:
            self.call("close")
        finally:
            self.process.join(timeout=10)
            if self.process.is_alive():
                self.process.terminate()
                self.process.join(timeout=10)
            self.connection.close()


def run_case(case, archive, manifest):
    case_id = f"{case['dataset']}-{case['task_id']}-r{case['repeat']}"
    identity = {"batch_id": manifest["batch_id"], "candidate_sha": manifest["candidate_sha"],
                "dataset_revision": manifest["datasets"][case["dataset"]]["revision"], **case}
    # OSS conditional creation rejects duplicate Pods and never replays an attempted case.
    archive.put("claims/" + case_id + ".json", identity, True)
    archive.event(case_id, "claimed", identity=identity)
    sequence = 0

    def trace(kind, value):
        nonlocal sequence
        sequence += 1
        archive.put(f"traces/{case_id}/{sequence:06d}-{kind}.json", value, True)

    result = {**identity, "status": "running", "score": None, "unscored_reason": None}
    adapter = None
    started = time.monotonic()
    tool_calls = 0
    used_tools = set()
    model_calls = 0
    usage_calls = 0
    input_tokens = 0
    output_tokens = 0
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            nonlocal tool_calls
            if self.path != "/tool":
                self.send_error(404)
                return
            try:
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                with lock:
                    archive.event(case_id, "tool_started", name=request["name"])
                    trace("tool_request", request)
                    tool_calls += 1
                    used_tools.add(request["name"])
                    response = adapter.tool(request["name"], request["arguments"])
                    trace("tool_result", {"result": response})
                    archive.event(case_id, "tool_completed", name=request["name"])
                raw = response.encode() if isinstance(response, str) else encoded(response)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(raw)
            except Exception as error:
                archive.event(case_id, "tool_failed", error_type=type(error).__name__)
                self.send_error(500, "Business environment failure")

    server = HTTPServer(("127.0.0.1", 8081), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        random.seed(case.get("seed", 0))
        adapter = Tau(case["task_id"], trace) if case["dataset"] == "tau" else IsolatedAppWorldTask(case["task_id"], trace)
        archive.event(case_id, "initialized", initial_hash=adapter.initial_hash,
                      tools_hash=hashlib.sha256(encoded(adapter.tools)).hexdigest())
        receipt = java("/start", {"case_id": case_id, "system": adapter.system, "tools": adapter.tools})
        if receipt["candidate_sha"] != manifest["candidate_sha"]:
            raise ValueError("Candidate SHA mismatch")
        if manifest.get("candidate_snapshot") and receipt.get("candidate_source_sha256") != manifest["candidate_snapshot"]["sha256"]:
            raise ValueError("Candidate source snapshot mismatch")
        trace("runner_receipt", receipt)
        message = adapter.observation
        for turn in range(manifest["max_turns"]):
            if time.monotonic() - started > manifest["case_timeout_seconds"]:
                result["unscored_reason"] = "case_timeout"
                break
            archive.event(case_id, "running", turn=turn)
            trace("user_message", message)
            response = java("/chat", {"message": message})
            trace("agent_response", response)
            for event in response.get("trace", []):
                if event.get("type") == "model_response":
                    model_calls += 1
                    usage = event.get("response", {}).get("usage")
                    if usage is not None:
                        usage_calls += 1
                        input_tokens += usage.get("inputTokens", 0)
                        output_tokens += usage.get("outputTokens", 0)
            with lock:
                if adapter.done:
                    break
                message = adapter.respond_to_agent(response["text"])
                if adapter.done:
                    break
        # All Java calls and synchronous tool requests have settled before scoring.
        closed = java("/close", {})
        trace("agent_closed", closed)
        archive.event(case_id, "scoring")
        with lock:
            score, raw, reason = adapter.score()
        result.update(status="completed" if score is not None else "failed", score=score,
                      official_result=raw, unscored_reason=reason or result["unscored_reason"],
                      tools_exposed=len(adapter.tools))
    except Exception as error:
        result.update(status="failed", unscored_reason=type(error).__name__)
        archive.event(case_id, "failed", error_type=type(error).__name__)
        # A failed HTTP call may still be running: do not score/reset that environment.
        result["execution_state"] = "unknown" if isinstance(error, (TimeoutError, urllib.error.URLError)) else "settled"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=10)
        if adapter is not None and result.get("execution_state") != "unknown":
            try:
                adapter.close()
            except Exception as error:
                result["cleanup_error"] = type(error).__name__
                result["execution_state"] = "unknown"
    result.update(duration_seconds=time.monotonic() - started, tool_calls=tool_calls,
                  used_tools=sorted(used_tools), model_calls=model_calls,
                  token_usage={"known_input_tokens": input_tokens, "known_output_tokens": output_tokens,
                               "covered_calls": usage_calls, "total_calls": model_calls} if usage_calls else None)
    archive.put("cases/" + case_id + ".json", result, True)
    archive.event(case_id, "archived", status=result["status"], score=result["score"])
    print(json.dumps({k: result[k] for k in ("dataset", "task_id", "repeat", "status", "score", "unscored_reason")}), flush=True)
    return result

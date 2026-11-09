# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Render local archived evidence as a self-contained, offline evaluation report."""
import argparse
import ast
import base64
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re


TRACE_KINDS = {"user_message", "agent_response", "tool_request", "tool_result", "user_model"}


def case_id(case):
    return f"{case['dataset']}-{case['task_id']}-r{case['repeat']}"


def outcome(case):
    if case.get("status") == "missing":
        return "missing"
    if case.get("score") is None:
        return "unscored"
    return "passed" if case["score"] == 1 else "not_passed"


def requested_apis(timeline):
    """Static call sites in submitted Python, not proof each branch executed."""
    names = set()
    for event in timeline:
        if event["kind"] != "tool_request" or event["value"].get("name") != "execute_code":
            continue
        try:
            tree = ast.parse(event["value"].get("arguments", {}).get("code", ""))
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            fn = node.func
            if (isinstance(fn, ast.Attribute) and isinstance(fn.value, ast.Attribute)
                    and isinstance(fn.value.value, ast.Name) and fn.value.value.id == "apis"):
                names.add(fn.value.attr + "." + fn.attr)
    return sorted(names)


def coverage(cases):
    groups = {}
    for case in cases:
        group = case.get("category", case["dataset"])
        group_counts = groups.setdefault(group, {"planned": 0, "passed": 0, "not_passed": 0, "unscored": 0, "missing": 0})
        group_counts["planned"] += 1
        group_counts[case["outcome"]] += 1
    return {"categories": groups,
            "tau_tools_case_counts": dict(sorted(Counter(tool for c in cases if c["dataset"] == "tau"
                                                         for tool in set(c.get("used_tools", []))).items())),
            "appworld_requested_api_case_counts": dict(sorted(Counter(api for c in cases if c["dataset"] == "appworld"
                                                                      for api in c.get("requested_apis", [])).items())),
            "appworld_api_count_semantics": "Static direct apis.app.method call sites in submitted code, counted once per case; not runtime API invocation counts."}


def build_report(read, keys):
    manifest = read("manifest.json")
    results = [read(k) for k in keys if k.startswith("cases/") and k.endswith(".json")]
    archived = {case_id(c): c for c in results}
    expected = [case_id(c) for c in manifest["cases"]]
    if len(archived) != len(results) or len(set(expected)) != len(expected) or not set(archived).issubset(expected):
        raise ValueError("Unexpected or duplicate case identity")
    initial_hashes = {}
    for key in sorted(keys):
        match = re.fullmatch(r"ledger/([^/]+)/[^/]+\.json", key)
        if match and match[1] in expected:
            event = read(key)
            if event.get("event") == "initialized" and event.get("initial_hash"):
                previous = initial_hashes.setdefault(match[1], event["initial_hash"])
                if previous != event["initial_hash"]:
                    raise ValueError("Conflicting initial business state")
    trace_keys = []
    for key in sorted(keys):
        match = re.fullmatch(r"traces/([^/]+)/([0-9]+)-([^/]+)\.json", key)
        if match and match[1] in expected and match[3] in TRACE_KINDS:
            trace_keys.append((key, match[1], match[2], match[3]))
    traces = {identity: [] for identity in expected}
    def read_trace(entry):
        value = read(entry[0])
        if entry[3] == "agent_response":
            return {"text": value.get("text", "")}
        if entry[3] == "user_model":
            response = value.get("response", {})
            return {"request": {"messages": [m for m in value.get("request", {}).get("messages", [])
                                               if m.get("role") == "system"]},
                    "response": {"model": response.get("model"), "usage": response.get("usage")}}
        return value
    with ThreadPoolExecutor(max_workers=8) as pool:
        for (key, identity, sequence, kind), value in zip(trace_keys, pool.map(read_trace, trace_keys)):
            traces[identity].append((sequence, kind, value, key))
    cases = []
    for planned in manifest["cases"]:
        identity = case_id(planned)
        case = dict(archived.get(identity, {**planned, "status": "missing", "score": None}))
        if identity in initial_hashes:
            if case.get("initial_hash", initial_hashes[identity]) != initial_hashes[identity]:
                raise ValueError("Result differs from initial business state receipt")
            case["initial_hash"] = initial_hashes[identity]
        case.update(id=identity, outcome=outcome(case), instruction="", timeline=[])
        simulator = {"calls": 0, "covered_calls": 0, "input_tokens": 0, "output_tokens": 0, "models": []}
        for sequence, kind, value, key in traces[identity]:
            if kind == "user_model":
                for message in value.get("request", {}).get("messages", []):
                    content = message.get("content", "")
                    if message.get("role") == "system" and isinstance(content, str):
                        match = re.search(r"Instruction: (.*?)(?:\n\nRules:|$)", content, re.S)
                        if match and not case["instruction"]:
                            case["instruction"] = match[1].strip()
                response = value.get("response", {})
                simulator["calls"] += 1
                usage = response.get("usage") or {}
                if usage.get("prompt_tokens") is not None and usage.get("completion_tokens") is not None:
                    simulator["covered_calls"] += 1
                    simulator["input_tokens"] += usage["prompt_tokens"]
                    simulator["output_tokens"] += usage["completion_tokens"]
                if response.get("model") and response["model"] not in simulator["models"]:
                    simulator["models"].append(response["model"])
                # Never embed repeated prompts or provider reasoning in the report.
                continue
            if kind == "user_message" and not case["instruction"]:
                case["instruction"] = value.split("\nSupervisor:", 1)[0] if case["dataset"] == "appworld" else value
            if kind == "agent_response":
                value = value.get("text", "")
            case["timeline"].append({"sequence": sequence, "kind": kind, "value": value, "source": key})
        case["simulator_usage"] = simulator
        case["requested_apis"] = requested_apis(case["timeline"])
        cases.append(case)
    return {"manifest": {key: manifest.get(key) for key in
                         ("batch_id", "tag", "candidate_sha", "candidate_snapshot", "model", "model_snapshot_locked", "datasets", "images", "appworld_data_hash", "selection")},
            "summary": read("report.json") if "report.json" in keys else None,
            "completed": read("completed.json") if "completed.json" in keys else None,
            "cases": cases, "coverage": coverage(cases)}


def render(data):
    payload = base64.b64encode(json.dumps(data, ensure_ascii=False).encode()).decode()
    template = Path(__file__).parent / "reports/templates/business.html"
    return template.read_text(encoding="utf-8").replace("__REPORT_DATA__", payload)



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", type=Path, required=True, help="Archived batch directory")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    keys = [p.relative_to(args.local).as_posix() for p in args.local.rglob("*.json")]
    data = build_report(lambda key: json.loads((args.local / key).read_text()), keys)
    args.output.write_text(render(data), encoding="utf-8")
    print("Report written: " + str(args.output))


if __name__ == "__main__":
    main()

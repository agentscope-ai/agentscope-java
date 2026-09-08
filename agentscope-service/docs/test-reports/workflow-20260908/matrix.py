"""Deterministic HTTP acceptance; creates only namespaced acceptance fixtures."""
import runpy, sys, time, json, pathlib
sys.argv = ["probe.py", "helpers"]
m = runpy.run_path(str(pathlib.Path(__file__).with_name("probe.py")))
api, state = m["api"], m["state"]
cases = {}
for mode in ["all", "any", "quorum"]:
    label = "join-" + mode
    spec = {"nodes": [{"key": "yes", "type": "condition"}, {"key": "no", "type": "condition", "condition": "false"}, {"key": "also", "type": "condition"}, {"key": "merge", "type": "join", "join": {"mode": mode, **({"quorum": 2} if mode == "quorum" else {})}}], "edges": [{"from": source, "to": "merge"} for source in ["yes", "no", "also"]]}
    cases[label] = (spec, "succeeded", {"merge": "skipped" if mode == "all" else "succeeded", "no": "skipped"})
for policy in ["fail_fast", "continue", "partial_success"]:
    cases["failure-" + policy] = ({"nodes": [{"key": "bad", "type": "condition", "input": {"value": "run.input.missing"}, "failurePolicy": policy}, {"key": "recover", "type": "condition", "input": {"value": "42"}}], "edges": [{"from": "bad", "to": "recover", "on": ["failed"]}]}, "partial_succeeded" if policy == "partial_success" else "failed", {"bad": "failed", "recover": "cancelled" if policy == "fail_fast" else "succeeded"})
child, rev = m["definition"]("timeout-child", {"nodes": [{"key": "wait", "type": "signal", "signalName": "never"}]})
cases["timeout-subrun"] = ({"nodes": [{"key": "child", "type": "subrun", "definitionRevisionId": rev, "timeoutSeconds": 1}]}, "failed", {"child": "failed"})
cases["timeout-approval"] = ({"nodes": [{"key": "approval", "type": "approval", "approval": {"approverRef": "admin"}, "timeoutSeconds": 1}]}, "failed", {"approval": "failed"})
for label, (spec, _, _) in cases.items():
    d, r = m["definition"](label, spec)
    m["launch"](label + "-verified", d, r)
for label, (_, want, nodes) in cases.items():
    runid = state["runs"][label + "-verified"]["id"]
    for _ in range(30):
        g = api("/api/v1/orchestration-runs/" + runid + "/graph")
        if g["run"]["state"] in ["succeeded", "failed", "cancelled", "partial_succeeded"]:
            break
        time.sleep(1)
    assert g["run"]["state"] == want, (label, g["run"])
    assert all(next(n for n in g["nodes"] if n["nodeKey"] == key)["state"] == value for key, value in nodes.items()), (label, g["nodes"])
    if label == "timeout-subrun":
        assert len(g["childRuns"]) == 1 and g["childRuns"][0]["state"] == "cancelled", g
    if label == "timeout-approval":
        approvals = api("/api/v1/approvals?tenant=default&namespace=default&runId=" + runid)["items"]
        approvals = [a for a in approvals if a.get("runId") == runid]
        assert len(approvals) == 1 and approvals[0]["status"] == "cancelled", approvals
    state["checks"][label] = True
    (m["ROOT"] / "evidence" / (label + "-verified-graph.json")).write_text(json.dumps(m["scrub"](g), indent=2))
    print(label, "PASS", want)
    m["persist"]()

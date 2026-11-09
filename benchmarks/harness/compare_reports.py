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
"""Compare the exact same sampled tasks; retain unscored cases and all regressions."""
import argparse
import base64
from collections import Counter
from html import escape
import json
from pathlib import Path
import re

from worker import summary


def read_html(content):
    return json.loads(base64.b64decode(re.search(r"atob\('([^']+)'\)", content)[1]))


def metrics(cases):
    usage = [c.get("token_usage") or {} for c in cases]
    simulators = [c.get("simulator_usage") or {} for c in cases]
    return {**summary([c for c in cases if c["outcome"] != "missing"], len(cases)),
            "outcomes": dict(Counter(c["outcome"] for c in cases)),
            "tool_calls": sum(c.get("tool_calls", 0) for c in cases),
            "model_calls": sum(c.get("model_calls", 0) for c in cases),
            "duration_seconds": sum(c.get("duration_seconds", 0) for c in cases),
            "agent_input_tokens": sum(u.get("known_input_tokens", 0) for u in usage),
            "agent_output_tokens": sum(u.get("known_output_tokens", 0) for u in usage),
            "agent_usage_covered_calls": sum(u.get("covered_calls", 0) for u in usage),
            "simulator_input_tokens": sum(u.get("input_tokens", 0) for u in simulators),
            "simulator_output_tokens": sum(u.get("output_tokens", 0) for u in simulators),
            "simulator_calls": sum(u.get("calls", 0) for u in simulators),
            "simulator_usage_covered_calls": sum(u.get("covered_calls", 0) for u in simulators)}


def compare(before, after):
    for key in ("model", "datasets", "appworld_data_hash"):
        if before["manifest"][key] != after["manifest"][key]:
            raise ValueError("Comparison changes " + key)
    for data in (before, after):
        if not data.get("completed", {}).get("results_complete"):
            raise ValueError("Comparison requires every planned result")
        if len({c["id"] for c in data["cases"]}) != len(data["cases"]):
            raise ValueError("Duplicate task identity")
    previous = {c["id"]: c for c in before["cases"]}
    if set(previous) != {c["id"] for c in after["cases"]}:
        raise ValueError("Comparison must use exactly the same tasks")
    pairs = []
    for current in after["cases"]:
        prior = previous[current["id"]]
        if not prior.get("initial_hash") or prior.get("initial_hash") != current.get("initial_hash"):
            raise ValueError("Initial business environment changed: " + current["id"])
        comparable = prior.get("score") is not None and current.get("score") is not None
        change = "unscored"
        if comparable:
            change = ("improved" if current["score"] > prior["score"] else
                      "regressed" if current["score"] < prior["score"] else "unchanged")
        fields = ("score", "outcome", "unscored_reason", "tool_calls", "model_calls", "duration_seconds",
                  "token_usage", "simulator_usage", "official_result", "audit")
        pairs.append({"id": current["id"], "instruction": current["instruction"], "dataset": current["dataset"],
                      "category": current.get("category"), "change": change,
                      "before": {k: prior.get(k) for k in fields},
                      "after": {k: current.get(k) for k in fields}})
    paired = [p for p in pairs if p["change"] != "unscored"]
    return {"before_manifest": before["manifest"], "after_manifest": after["manifest"],
            "notice": "本轮是旧发布版与当前分支加未提交修复的单次比较；代码基线、Harness 配置与提示均有变化。"
                      "模型别名未锁定底层快照，用户模拟器具有随机性，不能将变化全部归因于补丁。"
                      "基线使用复核口径，评分器异常单列；对照重放成绩未计入。耗时为逐题之和，Token 不是费用。",
            "before": metrics(before["cases"]), "after": metrics(after["cases"]),
            "datasets": {d: {"before": metrics([c for c in before["cases"] if c["dataset"] == d]),
                              "after": metrics([c for c in after["cases"] if c["dataset"] == d])}
                         for d in before["manifest"]["datasets"]},
            "paired": {"valid_pairs": len(paired),
                       "before_passed": sum(p["before"]["score"] == 1 for p in paired),
                       "after_passed": sum(p["after"]["score"] == 1 for p in paired),
                       "changes": dict(Counter(p["change"] for p in pairs))}, "cases": pairs}


def render(data):
    labels = {"passed": "通过", "not_passed": "未通过", "unscored": "未评分", "missing": "缺失",
              "improved": "改善", "regressed": "回退", "unchanged": "不变"}
    def e(value):
        return escape(str(value))
    def metric(key):
        return f"<td>{e(data['before'][key])}</td><td>{e(data['after'][key])}</td>"
    rows = "".join("<tr><th>" + title + "</th>" + metric(key) + "</tr>" for key, title in [
        ("outcomes", "结果分类"), ("valid_scores", "有效评分题数"),
        ("tool_calls", "工具调用"), ("model_calls", "Agent 模型调用"),
        ("agent_input_tokens", "Agent 输入 Token"), ("agent_output_tokens", "Agent 输出 Token"),
        ("agent_usage_covered_calls", "Agent Token 有记录的调用数"),
        ("simulator_input_tokens", "模拟用户输入 Token"), ("simulator_output_tokens", "模拟用户输出 Token"),
        ("simulator_calls", "模拟用户模型调用"),
        ("simulator_usage_covered_calls", "模拟用户 Token 有记录的调用数"),
        ("duration_seconds", "逐题耗时合计（秒）")])
    details = []
    order = {"regressed": 0, "improved": 1, "unscored": 2, "unchanged": 3}
    for case in sorted(data["cases"], key=lambda c: (order[c["change"]], c["id"])):
        a, b = case["before"], case["after"]
        details.append(f"<details data-change='{case['change']}'><summary><b>{e(case['id'])}</b> — "
                       f"{labels[a['outcome']]} → {labels[b['outcome']]} · {labels[case['change']]}</summary>"
                       f"<p>{e(case['instruction'])}</p><p>工具调用 {a['tool_calls']} → {b['tool_calls']}；"
                       f"模型调用 {a['model_calls']} → {b['model_calls']}</p>"
                       "<div class='pair'>" + "".join(
                           f"<section><h3>{title}</h3><pre>{e(json.dumps(value,ensure_ascii=False,indent=2))}</pre></section>"
                           for title, value in [("修复前证据", a), ("修复后证据", b)]) + "</div></details>")
    paired = data["paired"]
    return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Harness 修复前后对比</title><style>
body{font:15px/1.7 system-ui,sans-serif;color:#183047;background:#f4f7fa;margin:0}main{max-width:1200px;margin:auto;padding:32px}h1{font-size:30px}table{border-collapse:collapse;width:100%;background:white}td,th{padding:10px;border-bottom:1px solid #dde4eb;text-align:left}details{background:white;border:1px solid #dde4eb;border-radius:8px;margin:12px 0;padding:16px}summary,select{cursor:pointer}.pair{display:grid;grid-template-columns:1fr 1fr;gap:20px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;max-height:500px;overflow:auto}a{color:#245cab}.notice{background:#e5edf5;padding:15px;border-radius:8px}@media(max-width:700px){.pair{grid-template-columns:1fr}main{padding:16px}}[hidden]{display:none!important}</style><main>
<h1>Harness 修复前后对比</h1>""" + f"<p class='notice'>{e(data['notice'])}</p>" + \
        f"<p>有效配对 {paired['valid_pairs']} 题：通过 {paired['before_passed']} → {paired['after_passed']}。" + \
        " · ".join(f"{labels[k]} {v}" for k, v in paired["changes"].items()) + "</p>" + \
        "<p><a href='baseline-report.html'>修复前逐题轨迹</a> · <a href='report.html'>修复后逐题轨迹</a></p>" + \
        "<table><tr><th>指标</th><th>修复前</th><th>修复后</th></tr>" + rows + "</table>" + \
        "<h2>逐题变化</h2><select id='filter'><option value=''>全部</option><option value='regressed'>回退</option>" + \
        "<option value='improved'>改善</option><option value='unscored'>未评分</option><option value='unchanged'>不变</option></select>" + \
        "".join(details) + "<details><summary>版本与快照</summary><pre>" + \
        e(json.dumps({k: data[k] for k in ("before_manifest", "after_manifest")}, ensure_ascii=False, indent=2)) + \
        "</pre></details></main><script>document.getElementById('filter').addEventListener('change',function(){for(const d of document.querySelectorAll('details[data-change]'))d.hidden=this.value!==''&&d.dataset.change!==this.value});</script></html>"



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True, help="Local baseline report HTML")
    parser.add_argument("--candidate", type=Path, required=True, help="Local candidate report HTML")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = compare(read_html(args.baseline.read_text()), read_html(args.candidate.read_text()))
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "comparison.html").write_text(render(data))
    (args.output / "comparison.json").write_text(json.dumps(data, indent=2))


if __name__ == "__main__":
    main()

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
"""Static per-task reports; no remote scripts, credentials, or hidden missing results."""
import html
import json
import re


def trial_keys(manifest):
    expected = {(agent['name'], task, rollout) for agent in manifest['agents']
                for task in manifest['tasks'] for rollout in range(manifest['rollouts'])}
    if 'selected_trials' not in manifest:
        return expected
    selected=[(item['agent'],item['task'],item['rollout']) for item in manifest['selected_trials']]
    if not selected or len(set(selected))!=len(selected) or not set(selected).issubset(expected):
        raise ValueError('Explicit trial selection contains duplicate or unexpected identities')
    return set(selected)


def summarize(manifest, results):
    expected = trial_keys(manifest)
    actual = {}
    for result in results:
        key = (result["agent"], result["task"], result["rollout"])
        if key not in expected or key in actual:
            raise ValueError("Unexpected or duplicate trial result")
        actual[key] = result
    rows = []
    for key in sorted(expected):
        rows.append(actual.get(key, {"agent": key[0], "task": key[1], "rollout": key[2],
            "execution_status": "missing", "scoring_status": "unscored", "score": None,
            "protocol_valid": False, "error_type": "MissingResult"}))
    scored = sum(r.get("scoring_status") == "scored" and r.get("score") is not None for r in rows)
    passed = sum(r.get("scoring_status") == "scored" and r.get("score") == 1 for r in rows)
    complete = all(r.get("execution_status") == "completed" and r.get("protocol_valid")
                   and r.get("native_completed") and r.get("archive_complete") and r.get("score") == 1
                   and r.get("scoring_status") == "scored" for r in rows)
    return {"run_id": manifest["run_id"], "suite": manifest["suite"], "model": manifest["model"],
            "planned": len(expected), "scored": scored, "passed": passed,
            "missing": sum(r["execution_status"] == "missing" for r in rows),
            "environment_unavailable": sum(r["execution_status"] == "environment_unavailable" for r in rows),
            "adapter_unsupported": sum(r["execution_status"] == "adapter_unsupported" for r in rows),
            "execution_errors": sum(r["execution_status"] in ("failed", "indeterminate", "cancelled") for r in rows),
            "scoring_coverage": scored / len(expected) if expected else None, "scored_pass_rate": passed / scored if scored else None,
            "end_to_end_pass_rate": passed / len(expected) if expected else None, "smoke_passed": complete, "results": rows}


def display_text(value):
    # Per-trial business credentials can occur in native command logs. Keep the
    # immutable archive intact, but omit those credentials from the HTML view.
    return re.sub(r"(?i)(Bearer(?:\s|['\"]|\+)*)([a-f0-9]{48,64})\b",
                  lambda match: match[1] + "[REDACTED]", str(value))


def render(manifest, summary, *, public=False):
    escape = lambda value: html.escape(display_text(value))
    rows, details = [], []
    for result in summary["results"]:
        detail = ""
        if not public and result.get("archive_path"):
            prefix = escape(result["archive_path"])
            detail = f'<a href="{prefix}/result.json">JSON</a>'
            if result.get("event_counts"):
                detail += f' · <a href="{prefix}/protocol/events.jsonl">事件</a>'
        content = '<h3>题目</h3><pre>' + escape(result.get("instruction", "未归档")) + '</pre>'
        if result.get('upstream'):
            content += '<h3>上游来源与适配</h3><pre>' + escape(json.dumps(result['upstream'], ensure_ascii=False, indent=2)) + '</pre>'
        if not public and result.get('capability_checks') is not None:
            content += '<h3>能力评测断言</h3><pre>' + escape(json.dumps({'correctness': result['capability_checks'], 'judge': result.get('judge_results'), 'observed_efficiency': result.get('observed_efficiency'), 'upstream_efficiency_reference_only': result.get('upstream_efficiency_expectations'), 'tool_trace_checks': result.get('tool_trace_checks')}, ensure_ascii=False, indent=2)) + '</pre>'
        if result.get('audit_notes'):
            content += '<h3>解释限制</h3>' + ''.join('<p>⚠ '+escape(note)+'</p>' for note in result['audit_notes'])
        if result.get('recovery_kind') or result.get('verification_recovery'):
            content += '<h3>基础设施恢复</h3><pre>' + escape(json.dumps({key:result.get(key) for key in (
                'original_outcome','result_run','recovery_kind','verification_recovery')},ensure_ascii=False,indent=2)) + '</pre>'
        content += '<h3>评分检查</h3><pre>' + escape(json.dumps({"artifact_checks": result.get("verifier_checks", {}), "behavior_checks": result.get("behavior_checks", {}), "release_checks": result.get("release_checks", {}), "framework_contracts_passed": result.get("framework_contracts_passed"), "official_result": result.get("official_result") if not public else None, "official_tests": result.get("official_test_result") if not public else None, "unsupported_reason": result.get("unsupported_reason"), "unscored_reason": result.get("unscored_reason")}, ensure_ascii=False, indent=2)) + '</pre>'
        if not public:
            content += '<h3>最终回答</h3><pre>' + escape(result.get("final_answer", "—")) + '</pre>'
            content += '<h3>事件时间线</h3><p>每条预览最多 2000 字符，完整内容见事件归档。</p>'
            for event in result.get("timeline", []):
                content += '<details><summary>' + escape(event["processed_at"] + " · " + event["type"]) + '</summary><pre>' + escape(event["text"]) + '</pre></details>'
            content += '<h3>执行证据与用量</h3><pre>' + escape(json.dumps({key: result.get(key) for key in ("error_type", "protocol_error", "usage", "observed_model_usage", "event_counts", "started_at", "finished_at")}, ensure_ascii=False, indent=2)) + '</pre>'
        identity = escape(result['agent']+'-'+result['task']+'-r'+str(result['rollout']))
        search = escape(result['agent']+' '+result['task']+' '+result.get('upstream', {}).get('source_function', '')+' '+result.get('category', ''))
        details.append('<details class="trial" data-search="'+search+'" id="'+identity+'"><summary>' + escape(result["agent"] + " / " + result["task"] + " / rollout " + str(result["rollout"])) + '</summary>' + content + '</details>')
        rows.append('<tr data-search="'+search+'">' + "".join("<td>" + escape(result.get(key, "—")) + "</td>"
            for key in ("agent", "task", "rollout", "execution_status", "scoring_status", "score", "protocol_valid"))
            + "<td>" + detail + "</td></tr>")
    metadata = {key: manifest.get(key) for key in ("run_id", "suite", "score_version", "model", "model_snapshot_locked", "agents", "candidate", "images", "harbor")}
    metadata["task_files"] = {key: value for key, value in manifest.get("input_files", {}).items() if key.startswith("datasets/")}
    metadata["input_bundle_sha256"] = manifest.get("bundle", {}).get("sha256")
    if public:
        metadata["candidate"] = {key: manifest.get("candidate", {}).get(key) for key in ("base_sha", "sha256", "diff_sha256", "version")}
        metadata.pop("images", None)
    release_links = ('<p><a href="release-gate.html">发布回归结论</a> · <a href="comparison.html">公共能力对比</a></p>' if manifest.get('release') and not public else '')
    return """<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Harness evaluation</title><style>body{font:15px system-ui;margin:32px;color:#18202a;background:#f5f7fa}
table{border-collapse:collapse;background:white;width:100%}td,th{text-align:left;padding:12px;border-bottom:1px solid #dde3eb}
details.trial{margin:16px 0;padding:16px;background:white}summary{cursor:pointer}pre{overflow-wrap:anywhere;white-space:pre-wrap;background:white;padding:16px}input{padding:10px;margin:12px 0;width:300px}a{color:#145dcc}</style>
<h1>Harness 评测结果</h1><p>""" + escape(summary["run_id"]) + " · " + escape(summary["model"]) + "</p><p>计划 " + str(summary["planned"]) + \
        " · 已结束 " + str(summary["planned"] - summary["missing"]) + " · 已评分 " + str(summary["scored"]) + " · 通过 " + str(summary["passed"]) + \
        " · 全题均通过：" + escape(summary["smoke_passed"]) + "</p><p>这是固定样本评测，不代表官方全量基准成绩。未评分、不支持与缺失项计入计划总数。</p>" + \
        release_links + '<input id="filter" placeholder="按 Agent 或题目筛选"><table id="trials-table"><thead><tr><th>Agent</th><th>题目</th><th>轮次</th><th>执行</th><th>评分</th><th>得分</th><th>事件覆盖</th><th>证据</th></tr></thead><tbody>' + \
        "".join(rows) + "</tbody></table><h2>逐题查看</h2>" + "".join(details) + "<h2>固定配置</h2><pre>" + escape(json.dumps(metadata, ensure_ascii=False, indent=2)) + \
        "</pre><script>document.getElementById('filter').oninput=e=>document.querySelectorAll('#trials-table tbody tr,details.trial').forEach(r=>r.hidden=!r.dataset.search.toLowerCase().includes(e.target.value.toLowerCase()))</script></html>"

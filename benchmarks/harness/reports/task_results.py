# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Separate task outcomes from migration-added tool-path gates, retaining raw scores."""
from copy import deepcopy
import html

from reports.full_run import groups
from reports.unified import render, summarize

# These two additions promote upstream efficiency expectations into hard gates.
# Keep native delegation/compaction evidence and translated custom assertions intact.
TRACE_GATES = {"required_fixture_calls", "todo_sequence"}


def result_view(manifest, original):
    rows = deepcopy(original["results"])
    differences = []
    for row in rows:
        original_score = row.get("score")
        row["frozen_strict_score"] = original_score
        checks = row.get("capability_checks") or {}
        gates = {k: v for k, v in checks.items() if k.split(":", 1)[-1] in TRACE_GATES}
        retained = {k: v for k, v in checks.items() if k not in gates}
        row["tool_trace_checks"] = gates
        # Never manufacture a score for missing evidence, incomplete execution,
        # an unsupported profile, or a failed original answer/file/behavior check.
        eligible = (row.get("scoring_status") == "scored" and original_score == 0
                    and row.get("execution_status") == "completed"
                    and row.get("native_completed") and row.get("protocol_valid")
                    and row.get("archive_complete") and gates
                    and any(value is False for value in gates.values()))
        all_other_checks = [*retained.values(), *(row.get("verifier_checks") or {}).values(),
                            *(row.get("behavior_checks") or {}).values()]
        if eligible and all_other_checks and all(value is True for value in all_other_checks):
            row["score"] = 1.0
            differences.append({"agent": row["agent"], "task": row["task"],
                "upstream": row.get("upstream"), "frozen_strict_score": original_score,
                "task_result_score": 1.0, "tool_trace_checks": gates})
        row.setdefault("audit_notes", []).append(
            f"任务结果视图：任务结果分 {row.get('score')}；冻结严格分 {original_score}。"
            "工具路径/状态序列检查另列，不证明未满足该路径就没有完成任务。原始 JSON 与归档不变。")
    summary = summarize(manifest, rows)
    summary["score_view"] = "capabilities-task-results-v1"
    metrics = {"run_id": manifest["run_id"], "view": summary["score_view"],
        "model_calls": 0, "original_results_modified": False,
        "excluded_gates": sorted(TRACE_GATES),
        "frozen_strict_by_agent": groups(original["results"], "agent"),
        "task_results_by_agent": groups(rows, "agent"),
        "task_results_by_category": groups(rows, "category"), "differences": differences}
    page = render(manifest, summary)
    banner = ("<h2>任务结果与调用符合度分开统计</h2><p>这是版本化派生视图，不是原始严格分数或官方榜单。"
              "仅拆出迁移新增的 required_fixture_calls / todo_sequence 门槛；原始文件、回答、语义裁判和原生能力证据仍保留。"
              "不支持与未评分保持原状，没有重跑模型。JSON 链接仍指向冻结的原始结果。</p>"
              '<p><a href="report-overview.html">查看原始严格报告</a> · '
              '<a href="result-metrics.json">查看两种口径及差异清单</a></p>'
              "<table><tr><th>Harness</th><th>任务结果通过</th><th>冻结严格通过</th><th>已评分</th><th>其他未评分</th><th>不支持</th></tr>")
    for agent, result in metrics["task_results_by_agent"].items():
        strict = metrics["frozen_strict_by_agent"][agent]
        banner += "<tr>" + "".join("<td>" + html.escape(str(v)) + "</td>" for v in
            (agent, result["passed"], strict["passed"], result["scored"], result["other_unscored"], result["adapter_unsupported"])) + "</tr>"
    page = page.replace('<input id="filter"', banner + '</table><input id="filter"', 1)
    return summary, metrics, page

---
title: "故障修复服务：从告警到可审查 PR"
description: "将诊断和仓库上下文交给后台 Job，用 PR、提交与测试证据验收。"
en_link: /v2/en/service/cases/incident-to-pr
---

研发平台已经知道“订单列表忽略筛选和分页”，用户点击“生成修复”。平台把诊断、仓库与目标提交交给 Agent 服务，取回 PR 和测试证据，由现有代码审查流程决定是否合并。任务从一个修复 Job 开始，不要求先搭建完整研发团队。

## 1. 准备可验证的故障

在练习仓库中放置以下文件，去掉下载文件名末尾的 `.txt`：

| 文件 | 放置位置 |
| --- | --- |
| [OrderQuery.java](/examples/service/incident-to-pr/OrderQuery.java.txt) | 仓库根目录 |
| [OrderQueryTest.java](/examples/service/incident-to-pr/OrderQueryTest.java.txt) | 仓库根目录 |
| [CI workflow](/examples/service/incident-to-pr/ci.yml.txt) | .github/workflows/ci.yml |
| [Job 请求](/examples/service/incident-to-pr/input.json.txt) | 本地 input.json，不含凭证 |

```bash
mkdir -p out
javac --release 17 -d out OrderQuery.java OrderQueryTest.java
java -cp out OrderQueryTest
```

起点故意缺少筛选与分页，五项检查中三项失败。保留测试并提交起点代码，将 input.json 的 repository 和 base_commit 替换为实际练习仓库与提交。样例只提供查询函数，不包含线上订单服务。

## 2. 定义修复服务

准备具备仓库读取、代码修改、Java 17 测试和创建 PR 权限的执行目标。可以用 Managed 环境或 Hosted Coding Agent；先验证 provider、工作目录和工具身份。应用传入仓库地址不会自动授予访问权限。

服务指令明确：只修复筛选与分页；先筛选再分页；保持顺序、不修改输入；page 从 1 开始、size 为 1..100；超大页码返回空列表。保留原测试，补充空白 status、无匹配、size 边界和最大整数页码测试。交付 PR、head SHA、测试命令/退出码及日志；不执行合并或部署。

[发布 job Endpoint](/v2/zh/service/endpoints) `incident-repair`，input schema 接受请求样例中的字段。Bash、curl、jq、BASE_URL 与具备 invoke/read 的 ENDPOINT_KEY 按发布指南准备。

## 3. 提交一个修复轮次

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/incident-repair/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: incident-204-repair-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

研发平台保存 incident_id、仓库、base_commit、修复轮次与 Invocation 的关联。告警接收器先归并重复告警，再决定是否创建新轮次；网络重发使用原 key。本文不替你配置 GitHub Webhook 或创建真实 PR。

执行过程中用[快照与事件](/v2/zh/service/service-api)展示进度。工具等待确认时，由有权限的人处理实际 action；不要把“允许创建 PR”扩展为“允许合并”。

## 4. 接回研发平台

若有公网 HTTPS 回调接收器和 webhooks:write scope，可为 Invocation 注册完成及失败事件，按[Webhook 协议](/v2/zh/service/service-api#凭据预算和后台通知)验签与去重。快速任务可能先完成，所以注册后仍主动查询状态；回调也只作为重新查询的通知。

应用读取最终 result 和 Artifact，核对 PR 所属仓库、head SHA、测试日志及当前 CI。输出字段由服务契约约定，PR URL 不等于平台已经验证了 PR。

## 5. 验收与返工

| 检查 | 合格证据 |
| --- | --- |
| 修复范围 | diff 只涉及目标行为及必要测试 |
| 固定基线 | 修复前 5 项中 3 项失败，修复后原 5 项全部通过 |
| 边界情况 | 新增检查实际执行，命令、退出码和日志可查 |
| 提交一致性 | 测试对应交付的 head SHA，合并前检查最新 CI |
| 人工审查 | 由仓库授权审查者决定，不由 Invocation completed 代替 |

执行完成后发现问题，应用用新的 key 提交修订 Job，引用原 PR 和最新提交，保存前后关联。执行中取消需等待确认终态；已推送的提交或 PR 不会因取消自动撤销。

本地资料检查脚本会验证故意失败的基线及参考修复。真实模型、provider、GitHub、CI 和审查流程仍需实际运行验证。

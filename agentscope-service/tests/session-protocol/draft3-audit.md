# Draft 3 全局复查 / Global Draft 3 audit

2026-10-19，`/Users/ken/agentcore/agentcore-managed-agents`，`master`。
本轮只修改独立项目；原 AgentScope Java 项目保持只读。已有改动保留，未提交 Git。
This audit changes only the standalone checkout and preserves existing local changes. No Git commit is created.

## 修复 / Fixes

| 范围 / Area | 发现与修复 / Finding and correction |
| --- | --- |
| Agent schema | 真实 MCP 工具集遗漏 `configs/default_config`，原生工具配置被丢弃。现在输出完整配置和实际默认权限，从带 scope 校验的加密固定配置读取；不公开 MCP 私有认证；隐式 MCP always_ask、未映射 Skills/工具和 stdio 明确拒绝。官方 SDK fixture 使用禁用原生工具和显式 always_allow 的 MCP 配置。 / Complete native/MCP toolsets with effective policies; reject unverified mappings and interactive defaults. |
| Protocol | 新键取消终态 Turn 统一 409 `turn_terminal`；原键仍先重放冻结回执。未知/重复查询参数和重复协议 Header 明确拒绝，保留普通 SSE 的 unsupported 语义。 / Specific terminal conflicts, frozen replay and strict query/header handling. |
| SDK | Java/Python/TS 补齐 Turn 的 `input_event_id` 和 Input 的 `status/event_id`；拒绝会被静默丢弃的输入字段。Python 空键明确报错。 / Matching filters and strict input envelopes. |
| Result reads | 只读重试 503，重试保持查询和分页位置；等待期限覆盖 HTTP/分页，TS 取消可中断正在读取的请求；拒绝循环/不完整分页；终态无输出正常返回空集合。 / Retryable, bounded and validated final-output reads. |
| Console | SSE 持续不可用仍轮询所选非终态 Turn，结果投影暂不可用继续重试；观察失败不改写执行状态；cancel 不确定响应保留幂等键。 / Independent observation and stable cancellation retries. |
| Native boundaries | 原生内部 Session/Turn/Input 回执保留；必要二进制标识、SQL 表名、MQ 消费组不变。 / Native internal contracts remain separate from public projections. |

实际集群进一步发现：持久 Session 使用 `encrypted_definition`，不能按明文配置读取。
公共 Agent 投影和事件受理现在复用 `ManagedCatalog.resolve(scope)`，验证加密配置 scope 后读取固定 Agent；没有明文降级。
HTTP fixture 已统一为真实加密 bootstrap，并增加投影/私密字段、默认确认策略及 scope 错配回归。
Production stores sealed definitions. Public projection and admission use the existing scoped catalog resolver;
HTTP fixtures now use encrypted bootstraps, with explicit policy, scope and private-header regressions.

## 本地验证 / Local verification

| 检查 / Check | 结果 / Result |
| --- | --- |
| Full Java reactor, official SDK + Go loopback enabled | 708 discovered, 555 executed, 153 environment-gated skips; zero failures/errors |
| Spotless | Passed |
| Isolated PostgreSQL `ManagedSessionProtocolTest` | See `release/dist/deploy-draft3-audit/verification/postgres.log` for the separate 14-case run |
| Official SDK | Python 1.15.0 and Java 2.73.0 strict response validation; TypeScript 0.135.0 field/configuration assertions; create/get/send/list against real HTTP/auth/JPA fixture |
| Platform SDK | Python 120 passed; TypeScript 32 passed + build; Java 15 tests in the reactor |
| Console | 189 unit tests; lint 0 errors, 97 existing warnings; production build; 3 Playwright cases including persistent SSE outage and cancel retry |
| Control Plane | `make build`, `make test` passed |
| Naming / frozen SQL, binary and MQ IDs | Checker passed; checker regression 5 passed; release helper 43 passed |

完整 XML 和跳过类清单保存在 `release/dist/deploy-draft3-audit/verification/`。
153 项跳过仍是验证缺口；独立 PostgreSQL 协议测试和部署验收不等同于整套旧基础设施集成测试已运行。
Full XML and skipped classes are retained with the release. Separate PostgreSQL and cluster acceptance do not erase the skipped legacy integration coverage.

本轮先运行 `clean verify`；加密读取修正后重新运行整个 `verify` reactor。复现最终源码可运行 / An initial clean verification was followed by a full reactor verification after the sealed-definition fix. Reproduce the final source with:

```sh
MANAGED_AGENTS_OFFICIAL_SDK_TESTS=true \
MANAGED_TEST_GO_BIN=/usr/local/go/bin/go \
MANAGED_TEST_GO_MODULE="$PWD/managed-agents/control-plane" \
mvn -B -ntp -T1 clean verify
mvn -B -ntp -T1 spotless:check
```

## Draft 3 首批检查矩阵 / First-release evidence map

| 验收项 / IDs | 主要证据 / Evidence |
| --- | --- |
| C01, C05, C06 | `ManagedAgentProjectionTest`, `SessionApiContractTest`, pinned official SDK clients; deployed standard-history validation |
| C02, C03 | HTTP ordinary-message rejection, protocol fixed historical versions/time/type filters and same-key tests |
| C04, T07, T08 | Protocol target/terminal tests, runtime cancellation/settlement tests; `verify_interrupt.py` checks queued T2 plus ordinary interrupt/follow-up, idle/end_turn and frozen replay |
| T01–T05 | `ManagedPipelineTest`, `ManagedHarnessIntegrationTest`, `ManagedInputSuspensionsTest`, finalization and tool recovery tests; real HTTP MCP fixture includes replacement Runtime and one physical invocation |
| T02, T03 | `verify_session.py`: real 90-second MCP, waiting_tool/T2 queue, same-Turn injected context, physical call count, suspension/resume SQL facts and final outputs |
| R01, R02 | Sealed immutable output paging, missing projection 503; three SDK retry/timeout/cursor/terminal-empty cases; Console SSE-503 browser cases |
| I01–I06 | Protocol concurrent/frozen replay and atomic rollback tests; HTTP original-actor and native RPC boundaries; persistent runtime publication/recovery tests |
| E01, E02 | Pending-to-processed stable Event IDs, finalized message deduplication and duplicate native fact projection tests |
| S02, S05 | Public stream initial-boundary, bounded connection/backpressure, cleanup and authorization revocation tests; Console history reconciliation |
| A01 | Original actor, scope, invoke/cancel grants and foreign IDs in HTTP/service tests |
| U01 | Three platform SDKs, Console and bilingual design/MCP manuals; current release manifest records exact source/SDK versions |

## 发布与升级 / Publication and upgrade

本轮目标：ACR `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentcore`，Kubernetes `agencore-ma`，Helm release `ma`。
五个组件以同一源码指纹、独立构建标签发布 linux/amd64 与 linux/arm64；保留未提交源码指纹及镜像 SBOM/provenance。
不修改 Git 干净工作区发布保护；使用已验证 JAR、全新控制台和双架构 Go 二进制的打包路径。
A shared source fingerprint and unique build tag identify all five multi-platform images, including dirty-source provenance. Git publication guards remain intact.

升级先确认无在途 Turn，执行增量 schema Jobs，再应用已有最小权限授权脚本并验证七张协议表权限，最后 Helm atomic upgrade。
旧执行记录和手动测试空 Session 保留，不回填旧版本公开历史，不引入 Sandbox/NAS。
The upgrade drains active work, applies additive schemas and existing least-privilege grants, then performs an atomic Helm rollout. Historical records and the empty manual Session are retained.

实际版本、镜像摘要、升级结果和新协议集群验收以以下文件为准：

- `release/dist/current-acr-publication.json` → 对应 `publication.json` 和源码清单 / publication and source snapshot.
- `release/dist/deploy-draft3-audit/deployment.json` → 部署及检查 / deployment and verification.
- `release/dist/deploy-draft3-audit/acceptance/report.json` → 真实 MCP 90 秒 / real MCP acceptance.
- `release/dist/deploy-draft3-audit/interrupt/report.json` → 中断、排队和终态重试 / interrupt and replay.

兼容等级仍为 `candidate`。完整 Claude 平台、未开启的 Files/生命周期/客户端工具答复、Threads、durable delivery、snapshot、preview 不属于这次声明。
Compatibility remains candidate for the explicitly tested subset; future capabilities are not advertised by passing these tests.


## 集群验收后验证工具修正 / Post-publication verification-only correction

中断脚本原先把可选 `managed_agents.turn.cancelled` 通知当作标准 idle 的下界，
会误判按 processed_at 排序的有效历史。现按真实 `user.interrupt` 至下一轮 running 的时间边界
检查 `session.status_idle` / `end_turn`，并覆盖相同时间戳；5 项脚本回归通过，真实中断用例重新执行。
本轮发布后修正仅涉及验收脚本、脚本测试及本记录；另有并行工作更新的两份 tool-call-batch-dispatch 设计文档，单独记录并保留。应用、SDK、控制台源码
与镜像构建快照一致，不需要再替换已验证的运行镜像。最终部署报告记录这几项文件的独立摘要。

The interrupt harness incorrectly used an optional Turn cancellation notice as the lower boundary
for a standard idle event. The corrected check uses the actual interrupt and next running timestamps,
including ties. Five harness regressions pass and real interruption is rerun. Our post-publication changes only affect verification tools and this record. Two concurrent tool-call-batch-dispatch design documents are preserved and recorded separately. Application, SDK and Console sources still match the image
build snapshot. Their verification-only file hashes are recorded separately in deployment.json.

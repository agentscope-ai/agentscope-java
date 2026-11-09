# Harness 评测与 Benchmark

这套 benchmark 评估 Agent 能否通过真实 Harness 完成任务，覆盖工具调用、文件操作、记忆、
上下文压缩、技能、子代理和会话生命周期。它用于 AgentScope Java 的发布回归，也支持与原生
Codex、Pi Agent 对比。适配器保留各框架自己的执行循环，通过任务结果与行为记录进行评测。

English: [English guide](README.md)。

## 定位与当前可用范围

适合用它排查能力短板、验证 Harness 改动是否引入回归，以及在明确的题目、模型和预算约束下
比较不同 Agent。成绩只代表选定题目及适配环境中的表现，不等同于官方上游排行榜成绩，
也不能单独归因于模型能力。

| 能力 | 可用入口与要求 |
| --- | --- |
| 离线协议及套件检查 | `eval.py protocol-smoke`、`protocol-check`、`release validate` |
| 准备、导出题目 | `eval.py prepare-tasks`、`export-tasks`，不调用模型 |
| 本地调用真实 AgentScope | 原生 Java CLI；需要 Java 17+、Maven 和模型凭据 |
| 生产框架契约测试 | `eval.py release contracts`，使用本地 fixture/mock 的 Java 测试 |
| 跨 Agent 执行整套题目 | 已有 Harbor 适配器及运行基础组件；需另外提供 runner 和执行环境 |
| 业务题执行 | 还需隔离的官方 tau-bench/AppWorld 包、数据及评分器 |
| 已有结果离线对比 | `eval.py release compare`，不调用模型 |

**当前分支没有 `eval.py run`，也没有完整的本地套件编排入口。** 准备、导出题目不代表已经执行
评测。本目录不包含集群提交、云存储或部署运维能力。

## 有哪些评测题目

当前共 **968 个唯一题目 ID：153 道自有题，815 道外部题**。各套件复用这些 ID，不维护重复目录。
`full` 是选定的 63 道综合题，并不表示全部 968 道。

| 题库 | 数量 | 覆盖内容与维护方式 |
| --- | ---: | --- |
| [Harness](datasets/harness/) | 26 | 直接维护的框架行为回归题 |
| [Capabilities](datasets/capabilities/README.md) | 127 | 直接维护的能力题、fixture 和断言 |
| [Terminal Bench](external/terminal-bench/) | 8 | 终端任务选题，固定 commit 获取并应用本地适配 |
| [Business](external/business/) | 807 | 165 道 tau-bench、642 道 AppWorld；由固定题目声明和公共模板生成包装 |

26 道 Harness 题覆盖：

| 方向 | 数量 | 示例 |
| --- | ---: | --- |
| 基础任务完成 | 2 | 汇总账本、修复空输入处理 |
| 文件 | 4 | 空文件、局部修改、嵌套路径、文件尾部 |
| 工具 | 4 | 参数、调用依赖、干扰工具、失败恢复 |
| 记忆 | 4 | 持久化、临时状态、更新、覆盖 |
| 上下文压缩 | 4 | 保留约束、继续任务、写入记忆、待执行工具 |
| 技能与子代理 | 4 | 发现、权限、委派、失败处理 |
| 会话生命周期 | 4 | 取消、重复请求、多轮、重启 |

127 道能力题分为：工具使用 51、记忆 23、文件操作 21、检索 8、组件行为（`unit_test`）8、
对话 7、规划 7、摘要 2。这里的 `unit_test` 是可执行题目的分类，不是仓库的 Python/JUnit
测试套件。每道题的指令和断言与其 `scenario.json` 放在一起。

8 道终端题为 `tb-cancel-async-tasks`、`tb-fix-git`、`tb-large-scale-text-editing`、
`tb-log-summary-date-ranges`、`tb-multi-source-data-merger`、`tb-polyglot-c-py`、`tb-regex-log`
和 `tb-write-compressor`。业务题覆盖官方工具驱动环境中的 tau-bench 交互及 AppWorld
应用工作流，包含选定的 dev、test-normal、test-challenge 题目 ID。

**自有题库直接维护，外部题库固定版本获取，仓库只保留必要适配代码、补丁和资源。**
下载及展开后的外部题位于已忽略的 `artifacts/datasets/`，仓库不保存完整上游题库副本。
详见[题库维护说明](datasets/README_zh.md)和[外部题目准备说明](external/README.md)。

## 选择评测套件

配置位于 [`configs/suites/`](configs/suites/)。表中的题数尚未乘以 Agent 数与重复次数。
三 Agent 套件选择 AgentScope、Codex、Pi；发布套件只选择 AgentScope。各 Agent 的能力限制
必须在结果中明确保留。

| 配置名称 | 题数 | Agent 数 × 重复次数 | 用途 |
| --- | ---: | ---: | --- |
| `smoke` | 2 | 3 × 1 | 跨 Agent 基础集成检查 |
| `release-smoke` | 3 | 1 × 3 | 小规模发布流程检查 |
| `release` | 18 | 1 × 3 | 计划 54 次发布回归试验 |
| `comparison` | 17 | 3 × 3 | 计划 153 次跨 Agent 对比试验 |
| `full` | 63 | 3 × 1 | 26 道 Harness + 8 道终端 + 29 道业务题 |
| `capabilities-preflight` | 7 | 3 × 1 | 能力适配预检 |
| `capabilities` | 127 | 3 × 1 | 全部自有能力题 |
| `business-preflight` | 4 | 3 × 1 | 业务后端预检 |
| `business-tau` | 165 | 3 × 1 | 选定 tau-bench 题目 |
| `business-appworld` | 642 | 3 × 1 | 选定 AppWorld 题目 |
| `business` | 807 | 3 × 1 | 全部业务选题 |

配置还声明 Agent 版本、模型/API、超时、轮数上限、并发度和重试策略，执行前应阅读对应 JSON。
默认使用配置中的兼容接口与 `qwen3.8-max`；这个提供商别名**不是不可变的模型快照**。
题目、适配器、评分器、模型配置或环境契约发生变化后，需要建立新的对比基线。

## 快速开始：离线检查与题目准备

使用 Python 3.12+ 和虚拟环境。从仓库根目录开始：

```sh
cd benchmarks/harness
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-protocol.txt
python eval.py protocol-smoke
python eval.py release validate --definitions-only
```

`protocol-smoke` 只回放本地 fixture，不代表真实模型或 Agent 已经运行。
`--definitions-only` 不要求下载外部题目，只检查选题定义。可以先从不需要下载的自有题开始：

```sh
python eval.py release validate --config configs/suites/capabilities-preflight.json
python eval.py export-tasks configs/suites/capabilities-preflight.json --output artifacts/export-capabilities-preflight
```

如果套件包含外部题，先准备再校验和导出：

```sh
python eval.py prepare-tasks configs/suites/full.json
python eval.py release validate --config configs/suites/full.json
python eval.py export-tasks configs/suites/full.json --output artifacts/export-full
# 后续可以离线复用已校验的下载缓存：
python eval.py prepare-tasks configs/suites/full.json --offline
```

准备过程只获取固定 commit 上的选中文件，并核对 SHA256。导出目录包含题目和来源
`manifest.json`；输出目录已存在时拒绝覆盖。校验和导出本身不下载，输入缺失或损坏会明确报错。

业务题准备只生成包装并获取固定元数据，**不会安装官方环境、数据库或完整 AppWorld 数据**。
后端必须符合 [`sources.lock.json`](external/business/sources.lock.json)，包括 AppWorld
数据版本 `0.2.0`。执行时应记录实际使用的包、数据身份和镜像 digest。

## 本地运行原生 AgentScope 冒烟请求

这一步验证真实 Java Harness 能否调用模型，不是带评分的 benchmark trial，也不会自动安装
题目 fixture 或可信验证器。使用专门的工作目录，Agent 可以修改其中的文件。

在仓库根目录使用 Java 17+ 和 Maven 构建；从候选 POM 读取实际版本：

```sh
CANDIDATE_VERSION=$(mvn -q -T1 help:evaluate -Dexpression=project.version -DforceStdout)
mvn -B -T1 -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true
mvn -B -T1 -f benchmarks/harness/adapters/agentscope-java/pom.xml package dependency:copy-dependencies spotless:check -DincludeScope=runtime -Dcandidate.version="$CANDIDATE_VERSION"
export HARNESS_EVAL_CLASSPATH="$PWD/benchmarks/harness/adapters/agentscope-java/target/classes:$PWD/benchmarks/harness/adapters/agentscope-java/target/dependency/*"
java -cp "$HARNESS_EVAL_CLASSPATH" io.agentscope.examples.eval.HarnessEvalCli --version
```

通过环境变量提供 `DASHSCOPE_API_KEY`。继续在仓库根目录执行：

```sh
export MODEL_ID=qwen3.8-max
export MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
mkdir -p benchmarks/harness/artifacts
EVAL_RUN_DIR=$(mktemp -d "$PWD/benchmarks/harness/artifacts/local-smoke.XXXXXX")
cat > "$EVAL_RUN_DIR/request.json" <<'JSON'
{"events":[{"type":"user.message","content":[{"type":"text","text":"Create hello.txt in the workspace containing exactly hello followed by a newline."}]}]}
JSON
java -cp "$HARNESS_EVAL_CLASSPATH" io.agentscope.examples.eval.HarnessEvalCli \
  "$EVAL_RUN_DIR/request.json" "$EVAL_RUN_DIR/native.jsonl" "$EVAL_RUN_DIR/workspace"
cat "$EVAL_RUN_DIR/workspace/hello.txt"
```

CLI 的三个参数为 `REQUEST_JSON NATIVE_JSONL WORKSPACE`。每次使用新的运行目录，以隔离
状态和追加写入的日志。CLI 输出的是**原生事件**；`eval.py protocol-check` 接收规范协议事件，
不能直接拿这份原生日志校验，需要先通过 `runtime/native_events.py` 转换。

## 如何执行带评分的套件

集成现有组件的 runner 需要完成以下步骤：

1. 选定、校验套件，准备并导出题目，冻结评测契约。
2. 按适用的 [`environments/`](environments/) 配方提供隔离环境，预装选定 Agent 版本，
   记录最终镜像 digest。
3. 把每道题的 scenario 传给 `runtime/harbor_agents.py` 中的适配器。安装 fixture 和技能，
   保持多轮与会话语义；业务题另外接入官方后端。
4. 先完成模型与工具预检，再执行矩阵。隔离每次试验的状态，遵循预算和重复次数，不自动重跑
   整个 trial。`runtime/schedule.py` 提供调度基础能力，但不创建执行环境。
5. 收集并转换原生事件，执行对应的结果、行为或官方评分器。可信工具回执和评分过程应位于
   候选 Agent 工作区之外，不能用候选自己写出的文件代替可信证据。所有计划试验都应归档，包括失败。
6. 生成不可变的运行证据，再使用下面的离线报告和发布判定入口。

对应组件包括 `runtime/scenarios.py`（场景执行）、`runtime/fixture_tools.py`（可信 fixture）、
`runtime/task_score.py` 与 `runtime/behavior_checks.py`（评分）、`runtime/ledger.py`（证据），
以及 `worker.py` / `runtime/business_server.py`（业务后端）。安装 `requirements-evals.txt`
只提供固定版本的 Harbor 等依赖，不会自动得到完整的套件执行服务。

## 评分、发布检查与报告

任务结果检查、行为断言和官方业务评分器使用不同契约。能力题使用
`capabilities-task-outcomes-v2`，Harness 发布配置使用 `task-outcome-v1`。
不支持、缺失和未评分的试验必须显式呈现，不能计为通过。

在 `benchmarks/harness` 中运行生产框架契约测试，或比较已有评测归档：

```sh
python eval.py release contracts --repo /path/to/candidate-repo --output artifacts/contracts-unique
python eval.py release compare --candidate /path/to/candidate-evidence \
  --baseline /path/to/baseline-evidence --output artifacts/review-unique
```

契约检查执行声明的 27 个生产测试类并核对 Surefire XML，使用 fixture/mock，是对真实模型
试验的补充。`--verify-only` 只检查已有 XML，不代表新执行了一次测试。
离线对比需要 `manifest.json`、`summary.json` 及 summary 指向的逐 trial `result.json`，会核对
归档一致性并生成 `release-gate.json`、`release-gate.html` 和 `report.html`。它不调用模型，
也不补造缺失结果。退出码分别为 0（`pass`）、3（`review`）、2（`fail` / `invalid`）。
未锁定的模型别名需要人工复核。详见[发布策略和证据要求](docs/release-regression_zh.md)。

另一个业务报告入口接受业务批次格式（`manifest.cases`、case 结果、轨迹和 ledger），
与统一发布归档的格式不同：

```sh
mkdir -p artifacts
python report.py --local /path/to/business-archive --output artifacts/business-report.html
```

[`reports/templates/business.html`](reports/templates/business.html) 是它的源码模板。
生成的 HTML 内嵌结果，可以离线查看，应保存到 `artifacts/`。`combine_reports.py` 和
`compare_reports.py` 用于业务归档合并与对比，参数见各自的 `--help`；它们与发布判定入口分开。

## 目录与开发验证

| 路径 | 职责 |
| --- | --- |
| `eval.py`、`release_cli.py` | 离线检查、准备/导出、发布判定入口 |
| `adapters/` | 原生 Agent 适配器，包括 Java CLI |
| `runtime/`、`schemas/` | 协议、场景、可信 fixture、评分与调度基础组件 |
| `configs/suites/`、`contracts/` | 套件配置与生产框架测试选择 |
| `datasets/`、`external/` | 自有题目与固定版本的外部适配 |
| `environments/` | 通用执行环境及镜像配方 |
| `reports/`、`report.py`、`*_reports.py` | 维护中的报告实现及模板 |
| `worker.py`、`prepare_appworld.py`、`snapshot.py` | 业务 worker、带校验的数据准备、源码快照 |
| `tests/`、`test_*.py` | 自动化测试源码，不是评测结果 |
| `docs/` | [协议](docs/agent-protocol_zh.md)与[发布回归](docs/release-regression_zh.md)指南 |
| `artifacts/`、`.venv/`、`__pycache__/` | 已忽略的本地产物、环境与缓存 |

在虚拟环境中，从仓库根目录执行：

```sh
python -m pip install -r benchmarks/harness/requirements-evals.txt
PYTHONPATH=benchmarks/harness python -m unittest discover -s benchmarks/harness/tests -p 'test_*.py'
PYTHONPATH=benchmarks/harness python -m unittest test_worker test_snapshot test_runner_integration test_prepare_appworld test_report test_compare_reports test_combine_reports
```

按 Java 构建一节设置 `HARNESS_EVAL_CLASSPATH`，可启用 Java 本地回环测试。
设置 `HARNESS_EVAL_MAVEN_TEST=1` 可启用源码快照 Maven 打包检查；未设置这些变量时，
相应检查会跳过。测试使用本地 fixture/mock，不调用真实模型服务。

下载、轨迹、生成报告、调研文档及批次进展统一存放到已忽略的 `artifacts/`。
不提交凭据、虚拟环境、构建产物或评测中间结果。

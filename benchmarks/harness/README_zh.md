# Harness 评测与 Benchmark

AgentScope Java Harness、Codex 和 Pi 的核心评测工具：原生适配器、固定题库、协议校验、
确定性判分与离线发布对比。
English: [English guide](README.md)。

本目录不包含集群提交、云存储与部署运维。独立 Java CLI 可以本地运行；Harbor 适配器需要外部
提供执行环境，当前分支尚无完整的本地套件编排入口。业务题还需要隔离的官方 tau-bench/AppWorld
环境及其数据，仅凭题目包装不能完成执行或判分。

## 目录

| 路径 | 职责 |
| --- | --- |
| `eval.py`、`release_cli.py` | 协议检查、题目导出、框架契约测试与离线发布判定 |
| `adapters/agentscope-java/` | 原生 Java CLI 与业务工具 HTTP runner |
| `runtime/`、`schemas/` | 原生事件转换、场景驱动、判分与调度基础能力 |
| `configs/suites/` | 题目选择、Agent/模型 profile、预算与评分策略 |
| `datasets/` | 直接维护的 Harness 自有题和 能力评测题 |
| `external/` | 外部来源锁定、选题清单、公共模板及适配覆盖文件 |
| `environments/` | 通用沙箱和题目镜像配方，不含部署凭据 |
| `contracts/` | 生产框架测试选择 |
| `reports/`、`report.py` | 离线报告与对比；`report.html` 是源码模板 |
| `worker.py`、`runtime/business_server.py` | 官方业务环境适配器及带认证的工具/判分边界 |
| `snapshot.py` | 候选源码快照 |

- [题库组织方式](datasets/README_zh.md)
- [发布回归与对比](docs/release-regression_zh.md)
- [事件协议](docs/agent-protocol_zh.md)

## 本地使用

使用 Python 3.12+ 和虚拟环境，在本目录执行：

```sh
python -m pip install -r requirements-protocol.txt
python eval.py protocol-smoke
python eval.py release validate --definitions-only
python eval.py prepare-tasks configs/suites/full.json
python eval.py release validate --config configs/suites/full.json
python eval.py export-tasks configs/suites/full.json --output artifacts/export-full
```

准备步骤只获取选中外部来源的固定 commit 文件并校验 SHA256；`prepare-tasks ... --offline`
复用已校验的下载缓存，自有题不需要下载。导出复制准备好的题目及来源清单，不启动 Agent、
不安装官方业务环境及完整数据、不创建执行环境。输出目录已存在时拒绝覆盖。配置保留明确的默认模型以便复现；修改模型应重新
建立对比基线。提供商的模型别名并非不可变快照。

Harbor 适配器测试需要安装 `requirements-evals.txt`。在仓库根目录执行：

```sh
PYTHONPATH=benchmarks/harness python -m unittest discover -s benchmarks/harness/tests -p 'test_*.py'
PYTHONPATH=benchmarks/harness python -m unittest test_worker test_snapshot test_runner_integration test_prepare_appworld test_report test_compare_reports test_combine_reports
mvn -B -T1 -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true
mvn -B -T1 -f benchmarks/harness/adapters/agentscope-java/pom.xml package dependency:copy-dependencies spotless:check -DincludeScope=runtime -Dcandidate.version=2.1.0-SNAPSHOT
```

将 Maven 版本替换为候选的实际版本。`HARNESS_EVAL_CLASSPATH` 应包含 adapter 的绝对
`target/classes` 和 `target/dependency/*` 路径，以启用 Java 本地回环集成测试。
`HARNESS_EVAL_MAVEN_TEST=1` 启用源码快照 Maven 打包检查。测试使用本地 fixture 与 mock。

下载、轨迹、生成报告与调研记录存入已忽略的 `artifacts/`。
不提交凭据、虚拟环境、构建产物或批次进展。

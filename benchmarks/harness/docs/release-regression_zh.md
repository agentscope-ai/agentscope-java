# Harness 发布回归与对比

核心发布工具负责固定输入校验、生产框架契约检查及本地归档证据对比。
执行与证据采集由外部 runner 提供。
English: [Release regression](release-regression.md)。

| 套件 | 范围 |
| --- | --- |
| framework-contract-v1 | 27 个生产测试类，覆盖会话、记忆、压缩、子代理、权限等契约 |
| release | 18 题 × AgentScope × 3 轮 = 54 次试验 |
| comparison | 17 道共同题 × 3 种 Agent × 3 轮 = 153 次试验 |
| capabilities | 127 道独立维护的 能力评测题，声明任务结果评分策略 |

release 配置从正式 Harness 题库选择 18 题。full 和 smoke 共享同一份题目文件，
使用已经澄清的输出键名、技能格式和会话指令。
4 道工具题要求每次试验的可信回执及随机生成的查询票据，候选自己写入的文件不能满足断言。
runner 必须在候选 workspace 之外托管 fixture。框架 mock 测试补充真实模型试验，不能互相替代证据。

## 固定契约

`runtime.release.freeze_contract` 对选中题目内容、本地 fixture、外部来源锁定与适配、
选中的环境配方、判分器、适配器、版本、模型/API、执行限制、
Harbor 和配置的基础镜像计算哈希；并发数和 run ID 不参与比较。候选源码允许变化，评测契约变化
必须重新建立基线。合并题目定义会改变评测指纹，不能将历史套件运行静默用作当前题库的基线。
执行证据应保留实际镜像 digest 与软件包版本；镜像配方并不保证逐位相同的重建结果。

## 本地命令

安装协议依赖后，在 `benchmarks/harness` 中执行：

```sh
python eval.py release validate --config configs/suites/release.json
python eval.py release contracts --repo /path/to/candidate-repo --output artifacts/contracts-unique
python eval.py release compare --candidate /path/to/candidate-evidence \
  --baseline /path/to/baseline-evidence --output artifacts/review-unique
```

契约检查执行声明的生产测试并严格检查 XML。必需测试缺失、跳过或失败均不能通过。
`--verify-only` 只检查已有 XML，并明确记录并非本次重新执行。不可变发布候选必须包含这些测试类。

离线对比需要 `manifest.json`、`summary.json` 以及由 `archive_path` 指定的各次试验 `result.json`。
工具核对摘要与归档试验记录一致，不调用模型，也不补造缺失结果。输出 `release-gate.json`、
`release-gate.html` 和试验报告；证据链接指向来源目录。

## 判定语义

| 状态 | 含义 |
| --- | --- |
| pass | 证据有效、关键题通过、基线可比且模型快照已锁定 |
| review | 首次基线准入、模型别名未锁定，或总阈值以内仍存在逐题回退 |
| fail | 关键题某轮失败，或整体通过率下降超过固定策略阈值 |
| invalid | 缺失、不支持、未评分或不可验证的试验；框架证据缺失；基线不兼容 |

所有预先声明的轮次均留在分母中，改进不能抵消逐题回退。
默认下降 5 个百分点的阈值是操作策略，不是显著性检验。
自动化必须要求 `release_passed=true`；采集结束本身不代表发布通过。
对比命令退出码：pass 为 0，review 为 3，fail/invalid 为 2。

集成 `runtime.schedule.run_schedule` 的 runner 必须保留预检屏障、试验状态隔离、不自动重试整次
试验、独立验证及可信证据采集。核心包不实现部署、恢复或云端发布。
生成证据存入已忽略的 `artifacts/`，仓库保留来源版本、许可证与固定契约。

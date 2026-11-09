# 自有题库与外部 Benchmark

English: [Dataset guide](README.md)。

| 来源 | 题数 | 维护方式 |
| --- | ---: | --- |
| `harness/` | 26 | 直接维护自有题 |
| `capabilities/` | 127 | 直接维护自有能力评测题 |
| Terminal Bench | 8 | `../external/terminal-bench/` 固定 commit 和适配覆盖文件 |
| Business | 807 | `../external/business/` 保留 165 个 tau-bench、642 个 AppWorld ID 及公共模板 |

套件共选择 968 个唯一 ID，各套件选题可以重叠：full 仍选 63 题，release 选 18 题，comparison
选 17 题。不维护每个套件的副本，也没有 v1 题库目录。12 个无法提供等价执行契约的 参考
条目已排除，ID、来源和原因保留在 [README.md](capabilities/README.md)。
保留题目中的 Agent 能力限制仍明确报告，不能计作通过。

## 准备与导出

在 `benchmarks/harness` 中执行：

```sh
python eval.py release validate --definitions-only
python eval.py prepare-tasks configs/suites/full.json
python eval.py release validate --config configs/suites/full.json
python eval.py export-tasks configs/suites/full.json --output artifacts/export-full
```

自有题不需要下载。外部题准备步骤获取选定文件、检查锁定哈希，并在已忽略的
`artifacts/datasets/` 下展开题目。`prepare-tasks ... --offline` 要求下载缓存已存在。
题目解析、导出和校验本身不下载。未知 ID、重复定义、未准备外部题以及缓存内容变化均明确失败。

导出产物包含题目目录及记录来源版本和文件哈希的 `manifest.json`，不安装官方业务环境、
不启动 Agent，详见[外部题库准备说明](../external/README.md)。

## 维护

直接修改自有题的指令、场景、fixture 和测试。能力评测题保留 MIT 归属与原始测试引用，
模拟工具由 `../runtime/fixtures/` 独立维护。转换已完成，不再需要捕获、生成或上游源码。
这些是自有能力评测，不能作为上游官方成绩。评分标识为 `capabilities-task-outcomes-v2`，任务 ID 使用 `cap-` 前缀。
命名和元数据变更需要新评测基线，历史归档继续保留原始标识。

每次运行固定仓库 commit 与评测契约。选中题目、本地工具、外部来源锁定及覆盖文件/模板、
判分代码和选中环境配方参与契约。runner 还必须记录实际镜像 digest 及官方包、数据身份。
契约变化需要新基线。缓存、报告和中间结果不提交。

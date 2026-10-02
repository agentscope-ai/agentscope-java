---
title: "浏览器动作：JEV 与文本模型对照"
---

2026-09-26 对同一组可见页面状态比较 OFF、JEV 和 Qwen。共享输入修正后，两个模型的 12 个动作标签均正确，JEV 的单步判断耗时更短。另用真实浏览器跑本地信息查询，验证实际导航与独立完成条件。固定样本和本地演示都不能代表生产网站成功率。

## 数据与协议

[12 个合成页面样本](/examples/jev/evidence/browser-cases.jsonl.txt)覆盖保修政策导航、已显示答案、向上/下滚动、等待加载、购买拒绝、页面注入、中文发票条件、登录拒绝、已完成时忽略无关目标、多个相似主题，以及没有相关链接的阻塞场景。

标注由本项目根据只读操作契约编写，参考 [jev-ultrafast model.py 与 questions.py](https://github.com/browser-use/jev-ultrafast/tree/1231850a0bf1a0c0341fe408ef1668dbbfdfac46/jev_ultrafast) 的决策机制，不是上游官方基准，也没有独立人工盲标。样本 SHA-256：

```text
78422981fcdd6b117791caa9ac2a2ae5c956216be8c8b967367ac8677228b2b7
```

每个样本只发起一次请求，同时询问 operation 与需要时的 click_target。共享 state 包括 goal、页面文本、所有已授权可见链接及滚动状态。只有操作及其必要目标均达到 .8 选项概率阈值才接纳；未使用的目标头不能执行。金标不发给模型。指标比较最终 Action 的操作与目标，不能只选对操作而点错链接。

- JEV 请求 jev-latest，实际返回 jev-1.13.0。
- Qwen 请求 qwen3.8-max，使用已有 HTTP 文本后端适配，thinking=false、temperature=0、JSON 响应。自报概率不保证与 JEV 同样校准。
- 固定样本运行 SHADOW：浏览器端是固定快照，不执行任何动作、导航或独立验证；测试端若收到派发会报错。
- 延迟为一次 navigate 调用的完整耗时，含判断请求、响应解析和本地包装；不是首 token、浏览器任务总时延或模型内部推理耗时。样本串行，P50/P95 使用 nearest-rank 并保留冷启动。
- OFF 只返回原始页面，不做语义动作选择，标为弃权以保持分母；这不是原流程用户任务失败率。

## 共享输入修正与首轮保留

首轮原始记录中，JEV 为正确 8、错误 2、弃权 2；Qwen 为正确 12。检查发现本实现把链接仅放进 click_target 的 criteria，公共 state 缺少可见候选。上游 model.py 明确将 elements 放在公共 state，每个判断头都应获得相同候选事实，不能依赖另一个问题的选项。

据此补入 visible_links 和滚动可用性，并增加共享候选回归测试。金标、阈值、操作描述和目标选项没有调整，两个后端均统一复跑。原始首轮结果完整保留：[OFF 首轮](/examples/jev/evidence/browser-baseline-initial-input-2026-09-26.json.txt)、[JEV 首轮](/examples/jev/evidence/browser-jev-initial-input-2026-09-26.json.txt)、[Qwen 首轮](/examples/jev/evidence/browser-qwen-initial-input-2026-09-26.json.txt)。不能用该输入缺口给模型能力下结论，也不能把修正后重跑称作未经观察的独立盲测。

首轮 JEV 的错误均为过早 BLOCKED；注入页面和相似主题选择弃权。修正后的报告标记 `stateFormat=visible-links-v2`。这次结果也说明，借鉴多头判断时必须复核公共输入结构，而不只是照搬问题名称。

## 固定动作结果

| 指标 | OFF | JEV | Qwen |
| --- | ---: | ---: | ---: |
| 正确 / 错误 / 弃权 / 调用错误 | 0 / 0 / 12 / 0 | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 |
| 正确数 / 全部标签 | 无语义判断 | 100%（12/12） | 100%（12/12） |
| 错误率 / 弃权率 | 不适用 | 0% / 0% | 0% / 0% |
| 判断 P50 | 不作模型性能比较 | 301.527 ms | 1,443.692 ms |
| 判断 P95 | 不作模型性能比较 | 1,052.115 ms | 1,874.039 ms |
| 请求数 / 已知用量响应数 | 0 / 0 | 12 / 12 | 12 / 12 |
| 输入 / 输出 token | 0 / 0 | 7,560 / 795 | 5,143 / 505 |

这 12 个小样本没有证明 .8 是生产合适阈值；其中购买、登录等情况由只读能力范围约束，不应把这部分收益全部归于模型。两模型本轮无协议错误，无自动重试。实际价格与账单未验证，货币成本保持未知。

原始记录：[OFF](/examples/jev/evidence/browser-baseline-2026-09-26.json.txt)、[JEV](/examples/jev/evidence/browser-jev-2026-09-26.json.txt)、[Qwen](/examples/jev/evidence/browser-qwen-2026-09-26.json.txt)。每条记录包含建议操作、目标、概率、选项置信度、模型和用量；SHADOW 中的建议不等于真实执行。

## 真实本地浏览器

使用同一份首页和保修政策页，在隔离 Chrome 上运行真实 ReActAgent / read_browser 工具。生成模型为脚本，只负责发起工具和读取严格 VERIFIED 状态；判断后端分别为离线脚本、真实 JEV、真实 Qwen。宿主检查实际政策页 URL 与可见保修文本，版本稳定且独立验证通过后才允许生成最终示例答案。

| 后端 | 独立验证 | 导航 / 判断 / 脚本生成次数 | 总耗时（单次） | 判断输入 / 输出 token |
| --- | --- | --- | ---: | ---: |
| 离线脚本 | 通过 | 1 / 2 / 2 | 2,889.972 ms | 0 / 0 |
| JEV jev-1.13.0 | 通过 | 1 / 2 / 2 | 3,841.238 ms | 1,220 / 118 |
| Qwen qwen3.8-max | 通过 | 1 / 2 / 2 | 5,387.995 ms | 858 / 72 |

以上为严格解析工具 JSON 状态后的最终 CLI 运行，三次均恰好关闭一个浏览器会话。端到端计时覆盖 Agent 构建/运行、浏览器启动、工具判断、实际导航和验证；不含先前 HTTP fixture 服务启动。每个后端只有一个样本，没有 P50/P95。环境：Chrome 153.0.8010.54、Playwright 1.45.0，本机 macOS。

记录：[离线](/examples/jev/evidence/browser-local-offline-strict-status-2026-09-26.txt)、[JEV](/examples/jev/evidence/browser-local-jev-strict-status-2026-09-26.txt)、[Qwen](/examples/jev/evidence/browser-local-qwen-strict-status-2026-09-26.txt)。首次本地运行也保留：[离线首次](/examples/jev/evidence/browser-local-offline-initial-status-2026-09-26.txt)、[JEV 首次](/examples/jev/evidence/browser-local-jev-initial-status-2026-09-26.txt)、[Qwen 首次](/examples/jev/evidence/browser-local-qwen-initial-status-2026-09-26.txt)。首次示例使用字符串包含检查，可能混淆 UNVERIFIED 与 VERIFIED；最终改为 JSON 字段严格相等，并增加独立验证不通过时不得回答的回归测试。这一修正影响示例断言，不改变 Navigator 的完成状态或固定动作判断。

该场景仅一个目标、一次链接导航；不是复杂网页基准。包含浏览器冷启动的端到端差距不能用固定动作 P50 直接外推。生产页面、业务 ACL、跨 frame、表单和任意网站任务成功率均未验证。

## 复现

先按[浏览器 API 的构建步骤](/v2/zh/jev/guides/browser-execution-api#可运行案例)准备 classpath。输出文件必须不存在：

```bash
module=agentscope-examples/jev
browser_cp="$module/target/classes:$(cat /tmp/jev-browser-cp.txt)"
browser_data="$module/src/test/resources/jev/browser-cases.jsonl"
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserBenchmark --baseline "$browser_data" /tmp/browser-baseline.json
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserBenchmark --live-jev "$browser_data" /tmp/browser-jev.json jev-latest
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserBenchmark --live-qwen "$browser_data" /tmp/browser-qwen.json qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

真实接口显式启用，分别读取 TYPESAFE_API_KEY 和 DASHSCOPE_API_KEY。默认离线 API 示例不读取密钥。固定动作对照不需要 Playwright、浏览器或外部网站；runner 源码见 [JevBrowserBenchmark](/examples/jev/source/JevBrowserBenchmark.java.txt)。

真实本地浏览器加真实判断后端：

```bash
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserLocalExample --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --live-jev jev-latest
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserLocalExample --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --live-qwen qwen3.8-max https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

本地页面由案例创建，访问仅限 loopback 上明确授权的 GET；有真实模型费用，生成模型仍为脚本。程序要求工具 JSON 中 status 严格等于 VERIFIED，不将 UNVERIFIED 的字符串部分匹配当成功。

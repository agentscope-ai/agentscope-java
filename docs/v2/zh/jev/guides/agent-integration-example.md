---
title: "运行案例与功能入口"
---

所有案例都位于 `agentscope-examples/jev`，包名为 `io.agentscope.examples.jev`。你可以先用合成判断跑通 Harness 链路，再替换为真实后端与业务证据。

## 构建一次，运行多个案例

在仓库根目录执行：

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -pl agentscope-examples/jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-examples-cp.txt
JEV_CP="agentscope-examples/jev/target/classes:$(cat /tmp/jev-examples-cp.txt)"
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

该组合案例查询退款条件、取回授权证据、生成遗漏条件的草稿、审核失败后仅修订文本，最后返回保留退款条件的回答。它使用内存检索、脚本生成模型和合成判断，不读取密钥或访问模型服务。

## 先确认这个例子发生了什么

预期最终回答保留“未使用、七天内”两项退款条件。生成模型共调用三次：提出检索工具、生成不完整草稿、仅重写文本；修订阶段不携带工具。控制台中的合成评审统计用于确认预设分支，不是线上准确率。

如果只想理解一个功能，按下面的目录运行独立场景，再进入对应 API 页替换业务输入和后端。

## 按能力运行

将上一条 java 命令末尾的类名换成表中入口即可。除单独说明的本地浏览器案例外，默认均可离线运行。

| 能力 | 入口及完整源码 |
|---|---|
| 工具选择与模型路由的独立场景 | [JevHarnessScenarios](/examples/jev/source/JevHarnessScenarios.java.txt)，参数 `selection` 或 `routing` |
| 工具选择、防护与调用级路由 | [JevHarnessExample](/examples/jev/source/JevHarnessExample.java.txt) |
| 客服 Judge | [JevCustomerSupportExample](/examples/jev/source/JevCustomerSupportExample.java.txt) |
| 检索、护栏与回答修订组合 | [JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt) |
| 轨迹评估 | [JevTraceEvaluationExample](/examples/jev/source/JevTraceEvaluationExample.java.txt) |
| 上下文压缩与恢复 | [JevContextCompactionExample](/examples/jev/source/JevContextCompactionExample.java.txt) |
| 持续监督与验证证据 | [JevSupervisionExample](/examples/jev/source/JevSupervisionExample.java.txt) |
| 代码评审 | [JevCodeReviewExample](/examples/jev/source/JevCodeReviewExample.java.txt) |
| 检索证据与草稿修订 | [JevEvidenceExample](/examples/jev/source/JevEvidenceExample.java.txt) |
| 自动与显式阶段路由 | [JevPhaseRoutingExample](/examples/jev/source/JevPhaseRoutingExample.java.txt) |
| 只读浏览器工具 | [JevBrowserExample](/examples/jev/source/JevBrowserExample.java.txt) |
| 单独运行应用与评审场景 | [JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt)，不带参数运行全部；也可指定 `classification`、`context`、`memory`、`support`、`team`、`browser`、`judge`、`evaluation`、`task-review`、`rag` 或 `draft` |
| 分类、记忆、团队与应用组件 | [JevApplicationExample](/examples/jev/source/JevApplicationExample.java.txt) |

脚本概率用于演示状态与执行分支，不能用其结果计算真实模型准确率。基准程序的固定输入与真实后端开关见[评测自己的 Harness](/v2/zh/jev/evaluation)。

## 使用本地浏览器

`JevBrowserLocalExample` 启动 loopback 页面并使用显式提供的 Chromium 可执行文件。默认仍使用合成判断，不访问外部网站或模型接口：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevBrowserLocalExample \
  --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

浏览器路径按你的系统替换。会话导航到政策页，由独立验证器检查目标内容，再关闭页面和本地服务。[浏览器 API](/v2/zh/jev/guides/browser-execution-api)

## 接入真实调用

客服案例追加 `--live` 才调用客户端。其他普通案例没有统一 live 开关，应按相应 API 页替换脚本调用器和模型。浏览器与基准程序使用各自显式 `--live-jev` / `--live-qwen` 参数；仅设置环境变量不会将离线案例切换为真实请求。

---
title: "客户端、批量与故障处理"
---

# 客户端、批量与故障处理

## 先运行已有能力

前置条件：当前仓库、JDK 17+、Maven、有效的`TYPESAFE_API_KEY`。客户端也支持`JEV_API_KEY`作为备用；优先使用前者。模块坐标为`io.agentscope:agentscope-extensions-jev`，版本随仓库revision；本次基线为`2.0.4-SNAPSHOT`，不据此保证公共仓库已发布。

[完整示例源文件](../examples/JevTicketDecision.java.txt)只调用当前API：无Agent、无Spring、无业务副作用。它把工单主诉和条件性退款两个问题放入同一请求，并打印结构化答案。状态是合成数据；打印内容不作为生产日志模板。

从项目根目录：

```bash
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev -am install -DskipTests
mkdir -p /tmp/agentscope-jev-demo
cp docs/jev/examples/JevTicketDecision.java.txt /tmp/agentscope-jev-demo/JevTicketDecision.java
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev dependency:build-classpath -Dmdep.outputFile=/tmp/agentscope-jev-demo/classpath.txt
JEV_CP="agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/target/classes:$(cat /tmp/agentscope-jev-demo/classpath.txt)"
javac --release 17 -cp "$JEV_CP" -d /tmp/agentscope-jev-demo /tmp/agentscope-jev-demo/JevTicketDecision.java
java -cp "/tmp/agentscope-jev-demo:$JEV_CP" JevTicketDecision
```

最后一条命令是计费请求，需自行预先设置环境变量；不要把密钥写入源码或提交文件。编译成功只证明API匹配，不证明模型预测正确。期望主诉为账户恢复，退款条件存在；实际结果以返回值为准。

## 当前默认与限制

| 项 | 当前实现 |
| --- | --- |
| 端点 | `https://api.typesafe.ai/v1/systemone` |
| 默认模型 | `jev-latest`；实验显式固定版本 |
| 返回 | `Mono<SystemOneResult>`；阻塞便捷方法为systemOneBlocking |
| 超时 | 每次尝试5秒；timeout位于retry之前，不是整个决策总预算 |
| 重试 | 默认2次，500ms起指数退避、上限为初值8倍、jitter 0.2；可重试状态包括429、529及5xx |
| 传输 | 阻塞HttpTransport工作通过boundedElastic调度；不能宣传为底层全异步HTTP |
| 响应校验 | 题型、候选键、有限数值、概率和；不能把业务正确性等同于结构合法 |

## 调用方式

应用入口可使用阻塞方法；Reactor链路应组合`systemOne(...).flatMap(...)`，不要在Middleware内block。示例明确关闭重试以便看清一次请求；生产重试需统一计算所有尝试、退避、候选重排和回退调用的总预算。

一次state上的多个问题优先合批。N个不同state则采用有界并发队列，限制队列等待和总预算，返回每个item的成功/失败状态，不用一个失败取消所有已成功结果。当前SDK没有本专题提出的批量调度器，实施时新增而非引用不存在的`batch()` API。

## 第一批客户端增强

拟补充：总deadline、Retry-After处理、响应request-id与安全错误摘要、取消传播、受限并发、按用途的usage指标。明确记录逻辑请求与attempt两层耗时；不要把重试后的成功延迟记成最后一次尝试延迟。工具业务执行不会因决策调用重试而再次执行。

基线离线测试命令：

```bash
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev -am test
```

禁止仅因开发机设置了密钥就自动触发计费集成测试；后续live测试需要独立开关。

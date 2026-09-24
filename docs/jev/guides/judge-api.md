---
title: "JevJudge API与客服影子评审案例"
---

# JevJudge API与客服影子评审案例

状态：第一块已实现，位于现有`agentscope-extensions-jev`模块；无需新增依赖。提供独立应用评审API与离线示例。Service持久化、配置页面、最终输出拦截和自动修订尚未实现。

## 基本API

```java
import io.agentscope.extensions.judge.jev.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

JevClient client = JevClient.builder().build();
JevJudge judge = new JevJudge(client, Duration.ofSeconds(8));
var definition = new JevJudge.Definition("support-v1", List.of(
    new JevJudge.Criterion("unsupported_commitment",
        new NoulQuestion("Does the draft claim a completed action without a successful business record?", null),
        false, 0.2, 0.8)));
var state = Map.of("draft", "退款已经完成", "business_record", "未执行退款");
judge.judge(state, definition).subscribe(result -> {
    // 影子模式只记录评审；不得据此直接执行退款。
    System.out.println(result.status());
});
```

真实调用需要`TYPESAFE_API_KEY`或客户端显式配置。示例阈值仅用于演示，生产需根据场景金标校准。应用应返回或组合Mono；不要在HTTP请求处理中为了记录结果而额外订阅。同一Mono每次订阅都会发起一次新的评审。

`Criterion`包含ID、Noul问题、期望布尔值及失败/通过阈值。概率先转换成期望结果的概率：期望true取p，期望false取1-p；小于等于失败阈值为FAIL，大于等于通过阈值为PASS，中间为INCONCLUSIVE。阈值必须满足`0 <= fail < pass <= 1`。阈值按浮点值直接比较，没有额外容差。

`Definition`保存非空版本与不可变条件列表，拒绝空列表和重复ID。全部条件都是必需项：任何FAIL使总结果FAIL；无FAIL但有不确定项为INCONCLUSIVE；全部通过才PASS。无权重平均，不支持Choice/Score聚合。

`Result`包含定义版本、总状态、逐项概率与状态、错误码、模型、usage及本次订阅耗时。JEV返回概率不等于返回文字理由，因此此API不编造解释或证据。业务证据由调用方放入state并在自己的记录中关联；API不自动生成run/session或证据引用。

## 错误、预算和取消

- TIMEOUT：评审总预算耗尽，包含客户端的重试和退避。
- INVALID_RESPONSE：Judge收到空结果、缺项/多项、非Noul答案或非法概率。
- BACKEND：客户端或适配器发出的其他异常，包括现有客户端先行检测出的协议错误；当前SDK异常类型不足以进一步稳定分类。

三种情况总状态均为ERROR，findings为空，不把错误解释成概率0。错误结果不保存原始响应体或异常文本。无独立Judge重试，避免与客户端重试相乘；模型与重试策略通过注入的JevClient设置。

调用方取消会取消上游订阅，不转成ERROR结果。Reactor超时能停止等待，不保证底层阻塞HTTP立即中止。注入的Function必须及时返回非阻塞Mono；同步阻塞的自定义适配器不在预算保证范围内。state对象由调用方负责，在订阅完成前不能修改。

## 客服案例：识别未完成退款承诺

源码：[客服示例](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevCustomerSupportExample.java)。

案例把客户的条件性申请、回复草稿和真实业务记录一起送入Judge，分别检查需求覆盖与无依据承诺。离线fixture设置覆盖概率0.9、无依据承诺概率0.95，因此结果FAIL；它验证业务组合，不代表真实模型准确率。示例只保留草稿供复核，不派发退款或其他工具。

从仓库根目录构建并运行：

```bash
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev -am install -DskipTests
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-judge-classpath.txt
java -cp "agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/target/classes:$(cat /tmp/jev-judge-classpath.txt)" io.agentscope.extensions.judge.jev.example.JevCustomerSupportExample
```

默认离线，即使环境存在key也不会调用API。显式在最后命令追加`--live`才调用真实后端，可能产生费用；真实结果以实际响应为准。普通模块测试使用fake和虚拟时间，不需要key。

## 下一块接入

Service可在应用完成草稿后组合`judge(...)`，将结果与已有run/session关联保存，先影子运行。这是下一步适配建议，当前未接入Service端点或事件存储。自动修订应仅重生成草稿，不能重新执行整个包含写工具的Agent流程。

[Judge源码](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/JevJudge.java) · [测试](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/test/java/io/agentscope/extensions/judge/jev/JevJudgeTest.java) · [后续质量闭环设计](judge.md)

## 本次验证记录

2026-09-24，在`~/agentscope-3/agentscope-java`的`main`工作区完成：Core 2443项测试（9项跳过、无失败）；JEV模块最终58项测试通过，其中新增Judge测试9项。目标模块及依赖的`clean verify`通过（该次限定Jev测试，Core完整测试在前一轮执行）。离线客服main实际运行返回FAIL，符合fixture预期。未运行计费live案例，未构建整个Service或部署服务。

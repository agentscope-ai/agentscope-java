---
title: "AgentScope Service配置与判断事件"
---


状态：已接入data-plane构建链和SessionTurnRunner。使用session的`agentOverrides`配置；本次没有新增专用前端页面。配置在`SessionAgentBuildSpec.overridesJson`中传递，参与现有SHA-256 materialization fingerprint；变更后下次构建使用新实例。

## 配置API

创建Session沿用已有API；下面是请求体中的新增配置示例。替换已有Agent和Environment标识，并沿用当前服务认证。

```json
{
  "agent": "agt_existing",
  "environmentId": "env_existing",
  "agentOverrides": {
    "jev": {
      "tools": {"mode": "SHADOW", "budgetMillis": 2000, "version": "tools-v1", "threshold": 0.8, "rejectionThreshold": 0.2, "maxTools": 3},
      "guard": {"mode": "SHADOW", "budgetMillis": 2000, "version": "guard-v1", "threshold": 0.8, "guardedTools": ["refund"]},
      "routing": {"mode": "OFF"}
    }
  }
}
```

提交到`POST /api/sessions`；已有Session通过`PATCH /api/sessions/{id}`更新`agentOverrides.jev`。jev子树整体替换，更新时应带上需要保留的用途配置；设为null删除该子树。关闭时将用途mode设为OFF。变更对下一次Agent构建生效，不中断正在运行的调用。

数据面从部署环境读取JEV凭据，配置不接受apiKey、baseUrl或任意额外字段。全部OFF时不构造客户端、不要求key。预算为1至30000毫秒；每用途独立设置版本和模式。非法配置在构建时拒绝，因此应先在测试环境验证配置。

模型路由可配置`models`字符串数组；服务端还必须设置`agentscope.jev.allowed-models`逗号列表。只有运营方允许的模型标识才能解析。首版Service对带工具的请求保守回退原模型，完整工具能力目录仍需后续对接；底层Harness API可显式提供compatible判断。

## 结果查询与案例

发送普通user.message后，通过已有鉴权接口读取判断：

```text
GET /api/sessions/{id}/events?types=jev.decision
GET /api/sessions/{id}/events/stream
```

事件payload包含run_id、purpose、version、mode、status、reason、elapsed_ms和recommendation。普通会话沿用原事件日志；托管执行保留当次execution scope镜像，避免使用后来变化的执行上下文。不同Session使用各自TraceSink。

观察写入使用256项有界队列，队列满或存储故障仅记录无敏感内容警告；不会阻断Agent。该记录是尽力而为的评估观测，不是可靠交易审计；事件可能在运行结束后到达，不保证全局顺序。现有API按Session所有者鉴权。

离线验收包括配置解析、关闭时不解析凭据、未知配置与非允许模型拒绝、配置影响缓存标识；构建链定向测试同步运行。尚未在运行中的Service做真实key与控制面端到端部署验证。

[公共执行API](/v2/zh/jev/guides/harness-runtime) · [实施进度](/v2/zh/jev/implementation-plan)

源码：[JevServiceSupport](/examples/jev/source/agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/catalog/JevServiceSupport.java.txt)、[Service装配](/examples/jev/source/agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/catalog/HarnessAgentBuildService.java.txt)、[运行事件](/examples/jev/source/agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/managed/SessionTurnRunner.java.txt)。

## 内容护栏与最终回答评审

新增 `jev.content`、`jev.quality`，与现有 tools/guard/routing 独立关闭、观察或接管。两者合并装配为一个响应中间件，避免安全检查与修订的先后关系依赖注册顺序。判断记录仍走 `jev.decision`，purpose 为 content 或 quality。

```json
{
  "jev": {
    "content": {"mode": "SHADOW", "budgetMillis": 2000, "version": "safety-v1", "blockOnReview": false},
    "quality": {
      "mode": "SHADOW", "budgetMillis": 10000, "version": "answer-v1", "maxRevisions": 1,
      "criteria": [{"id": "grounded", "instructions": "Is answer supported by evidence in prompt?", "expected": true, "failThreshold": 0.2, "passThreshold": 0.8}]
    }
  }
}
```

quality 最多 64 个条件，maxRevisions 为 0..10；不提供 criteria 的启用配置会拒绝。Service 默认每轮最多 64000 文本字符、8192 个事件、120 秒，输入输出审核均启用默认内容策略。content 的预算按每次审核计算，quality 的预算包含全部草稿修订。未通过时结束本轮，不发送被拒文本；应用可通过决策事件提供复核或支持入口。

配置参与现有 Agent 构建缓存。Service 已完成装配及定向测试，未部署到运行环境；自定义内容策略、检索后端与 ACL 仍通过应用 API 注入，不允许会话 JSON 注入任意数据源或凭据。

## 参考项目深化后的接入

检索证据分类/重排的只读工具见[检索与审核 Service 配置](/v2/zh/jev/guides/evidence-pipeline-api#agentscope-service)。候选目录、显式阶段和派发用量见[阶段路由 Service 配置](/v2/zh/jev/guides/phase-routing-api#agentscope-service)。两项都要求宿主按运行注入真实数据源，尚未部署生产服务。

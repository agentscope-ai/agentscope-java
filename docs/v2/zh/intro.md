---
title: AgentScope Java v2
description: 使用 AgentScope Harness SDK 开发自己的 Agent 应用，或直接通过 AgentScope Service 使用托管 Agent 服务，由平台统一管理运行时。
mode: custom
toc: false
en_link: /v2/en/intro
---

<div className="agentscope-landing agentscope-home">

<section className="hs-home-hero" aria-labelledby="home-title">
<div className="hs-home-copy">
<p className="hs-home-eyebrow">AGENTSCOPE 2.0 <span>JAVA</span></p>
<h1 id="home-title">让智能体持续工作，<br /><span>让能力成为服务。</span></h1>
<p className="hs-home-lead">使用 Harness SDK 开发自己的 Agent 应用，或直接使用 Service 平台的托管 Agent 服务。由平台统一管理运行时，让业务应用通过 API 获取专业能力与交付物。</p>
<div className="hs-hero__actions">
<a href="/v2/zh/docs/quickstart" className="hs-btn hs-btn--primary">开始构建 Harness <span aria-hidden="true">→</span></a>
<a href="/v2/zh/service/index" className="hs-btn hs-btn--secondary">探索 Service <span aria-hidden="true">→</span></a>
</div>
<a className="hs-home-source" href="https://github.com/agentscope-ai/agentscope-java">开源代码 <span aria-hidden="true">↗</span></a>
</div>

<div className="hs-home-platforms">
<a className="hs-home-platform hs-home-platform--harness" href="/v2/zh/docs/harness/architecture">
<span className="hs-home-label">SDK · 自主开发</span>
<h2>AgentScope Harness <span aria-hidden="true">↗</span></h2>
<p>将 SDK 嵌入自己的应用，组合上下文、工作区与工具执行，自主定制 Agent 逻辑并管理应用部署。</p>
<div className="hs-home-tags"><span>上下文管理</span><span>工具与沙箱</span><span>持久会话</span></div>
</a>
<div className="hs-home-connector">两种使用方式，按需选择与组合</div>
<a className="hs-home-platform hs-home-platform--service" href="/v2/zh/service/index">
<span className="hs-home-label">平台 · 托管服务</span>
<h2>AgentScope Service <span aria-hidden="true">↗</span></h2>
<p>推荐先在自己的基础设施部署 Service，再配置基于 HarnessAgent 内核的托管 Agent，通过 API 接入业务。平台统一管理运行时，无需为每个 Agent 应用分别开发和维护运行服务。</p>
<div className="hs-home-tags"><span>Agent as a Service</span><span>任务 · 交互 · 交付</span></div>
</a>
</div>
</section>

<section className="hs-home-section" aria-labelledby="capabilities-title">
<div className="hs-home-section-heading">
<h2 id="capabilities-title">自主开发，还是使用托管服务？</h2>
<p>需要在应用代码中深度定制 Agent，选择 Harness SDK；希望专注业务接入、由平台统一运行 Agent，选择 Service。Service 上的托管 Agent（Managed Agent）基于 AgentScope HarnessAgent 内核，两种方式共享同一执行内核。</p>
</div>
<div className="hs-home-capabilities">
<div className="hs-home-product">
<span className="hs-home-label">HARNESS</span>
<h2>AgentScope Harness</h2>
<p>适合需要嵌入 Java 或 Spring Boot 应用、自定义运行逻辑的团队。HarnessAgent 提供推理与工具循环、工作区、上下文、技能和会话管理；你用代码组合这些能力，并负责应用的部署与运维。</p>
<div className="hs-home-features"><a className="hs-home-feature" href="/v2/zh/docs/harness/context"><h3>上下文与工作区<span aria-hidden="true">↗</span></h3><p>按推理步骤构建上下文，压缩长任务历史，按需加载记忆、Skill 与工作文件。</p></a>
<a className="hs-home-feature" href="/v2/zh/docs/harness/architecture"><h3>工具、沙箱与协作<span aria-hidden="true">↗</span></h3><p>接入 Java 工具和 MCP，在选定的执行环境中完成操作，并委派子 Agent 处理独立任务。</p></a>
<a className="hs-home-feature" href="/v2/zh/docs/harness/session-log"><h3>事件、交互与恢复<span aria-hidden="true">↗</span></h3><p>保存消息和工具过程，处理用户补充与人工确认，通过持久会话和 checkpoint 继续工作。</p></a></div>
</div>
<div className="hs-home-product">
<span className="hs-home-label">SERVICE</span>
<h2>AgentScope Service</h2>
<p>适合希望通过 API 使用 Agent 能力、减少重复运行时建设的团队。配置指令、模型、工具和资源，直接通过 Session API 使用托管 Agent；平台承接运行、会话与任务管理，应用聚焦业务数据、用户体验和结果验收。</p>
<div className="hs-home-features"><a className="hs-home-feature" href="/v2/zh/service/usecases"><h3>从业务场景开始<span aria-hidden="true">↗</span></h3><p>把 Agent 接入 SaaS 页面、业务流程或定时任务，围绕实际输入与交付设计服务。</p></a>
<a className="hs-home-feature" href="/v2/zh/service/service-api"><h3>从调用到交付<span aria-hidden="true">↗</span></h3><p>提交后台任务或会话，通过快照与事件跟踪进度，处理人工交互并读取结果和文件。</p></a>
<a className="hs-home-feature" href="/v2/zh/service/orchestration"><h3>选择合适的执行方式<span aria-hidden="true">↗</span></h3><p>托管 HarnessAgent、接入已有应用或复用 Coding Agent；按任务需要组合 Team 与 Workflow。</p></a></div>
</div>
</div>
</section>

<section className="hs-home-section hs-home-start" aria-labelledby="start-title">
<div className="hs-home-start-copy">
<h2 id="start-title">从你需要的入口开始</h2>
<p>将 Agent 嵌入已有 Java 应用，或通过 HTTP 使用平台上的托管 Agent。</p>
<div className="hs-home-start-links">
<a href="/v2/zh/docs/quickstart">Java 快速开始 <span aria-hidden="true">→</span></a>
<a href="/v2/zh/service/service-api">通过 Session API 使用 Agent <span aria-hidden="true">→</span></a>
</div>
<p className="hs-home-footnote">Harness 示例共享 Builder，每次请求构建新实例。Service 示例先创建 Session 选择 Agent，再提交一轮任务。</p>
</div>
<div className="hs-window">
<div className="hs-window__bar">
<div className="hs-window__dots" aria-hidden="true"><div className="hs-window__dot hs-window__dot--r"></div><div className="hs-window__dot hs-window__dot--y"></div><div className="hs-window__dot hs-window__dot--g"></div></div>
<div className="hs-window__tabs">
<button type="button" aria-pressed={true} aria-controls="zh-harness" className="hs-tab active" data-panel="zh-harness">Harness · Java</button>
<button type="button" aria-pressed={false} aria-controls="zh-service" className="hs-tab" data-panel="zh-service">Service · HTTP</button>
</div>
</div>
<div className="hs-code-panel" id="zh-harness">

```java
// 共享配置；每次请求创建实例
var builder = HarnessAgent.builder()
    .name("notes-assistant")
    .agentId("notes-assistant")
    .model("dashscope:qwen-plus")
    .workspace(Path.of(".agentscope/workspace"));

try (var agent = builder.build()) {
    var context = RuntimeContext.builder()
        .userId("alice").sessionId("notes").build();
    agent.streamEvents(
        new UserMessage("整理材料中的任务与待确认事项"), context)
        .doOnNext(System.out::println)
        .blockLast();
}
```

</div>
<div className="hs-code-panel" id="zh-service" style={{"display": "none"}}>

```bash
SESSION_ID=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')" \
  | jq -er '.id')
curl "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-request-001' \
  --data '{"message":"Summarize the tasks and open questions."}'
```

</div>
</div>
</section>

<div className="hs-adoption">

<div className="hs-adoption__eyebrow">
 <span className="hs-adoption__eyebrow-dot"></span>生产环境验证 · Battle-tested in Production
</div>

<div className="hs-adoption__stats">

<div className="hs-stat">
 <span className="hs-stat__val">阿里巴巴集团</span> <span className="hs-stat__label">集团内使用最广泛的智能体框架（Java &amp; Python），十余条核心业务线生产环境深度使用</span>
</div>

<div className="hs-stat">
 <span className="hs-stat__val">开源社区</span> <span className="hs-stat__label">开源与阿里云公有云用户覆盖 10+ 行业的头部企业</span>
</div>

</div>

<div className="hs-adoption__row">
 <span className="hs-adoption__row-label">阿里巴巴集团</span>
<div className="hs-marquee">

<div className="hs-marquee__track">
 <span className="hs-marquee__group"> <span className="hs-tag">飞猪</span><span className="hs-tag">淘宝闪购</span><span className="hs-tag">虎鲸文娱</span><span className="hs-tag">AIDC</span><span className="hs-tag">阿里控股</span><span className="hs-tag">淘天交易</span><span className="hs-tag">淘天手淘</span><span className="hs-tag">1688</span><span className="hs-tag">千问 APP</span><span className="hs-tag">高德</span><span className="hs-tag">阿里云</span><span className="hs-tag">蚂蚁国际</span><span className="hs-tag">蚂蚁全球支付</span><span className="hs-tag hs-tag--more">…</span> </span> <span className="hs-marquee__group" aria-hidden="true"> <span className="hs-tag">飞猪</span><span className="hs-tag">淘宝闪购</span><span className="hs-tag">虎鲸文娱</span><span className="hs-tag">AIDC</span><span className="hs-tag">阿里控股</span><span className="hs-tag">淘天交易</span><span className="hs-tag">淘天手淘</span><span className="hs-tag">1688</span><span className="hs-tag">千问 APP</span><span className="hs-tag">高德</span><span className="hs-tag">阿里云</span><span className="hs-tag">蚂蚁国际</span><span className="hs-tag">蚂蚁全球支付</span><span className="hs-tag hs-tag--more">…</span> </span>
</div>

</div>

</div>

<div className="hs-adoption__row">
 <span className="hs-adoption__row-label">开源 · 公有云</span>
<div className="hs-marquee hs-marquee--reverse">

<div className="hs-marquee__track">
 <span className="hs-marquee__group"> <span className="hs-tag">金融</span><span className="hs-tag">交通物流</span><span className="hs-tag">消费零售</span><span className="hs-tag">制造</span><span className="hs-tag">能源</span><span className="hs-tag">医疗</span><span className="hs-tag">教育政媒</span><span className="hs-tag">互联网</span><span className="hs-tag">SaaS</span><span className="hs-tag">咨询</span><span className="hs-tag hs-tag--more">等行业头部企业</span> </span> <span className="hs-marquee__group" aria-hidden="true"> <span className="hs-tag">金融</span><span className="hs-tag">交通物流</span><span className="hs-tag">消费零售</span><span className="hs-tag">制造</span><span className="hs-tag">能源</span><span className="hs-tag">医疗</span><span className="hs-tag">教育政媒</span><span className="hs-tag">互联网</span><span className="hs-tag">SaaS</span><span className="hs-tag">咨询</span><span className="hs-tag hs-tag--more">等行业头部企业</span> </span>
</div>

</div>

</div>

</div>



<section className="hs-home-faq" aria-labelledby="faq-title">
<h2 id="faq-title">开始前，你可能想了解</h2>
<div><details className="hs-faq-item"><summary>HarnessAgent 与 ReActAgent 是什么关系？</summary><p>ReActAgent 提供推理、工具执行、消息、权限和中间件等基础能力。HarnessAgent 基于同一套 ReAct 循环，内置工作区、记忆、技能、子 Agent 与会话管理。推荐从 HarnessAgent 开始；需要自行组合运行层时，也可以直接使用 ReActAgent。</p></details>
<details className="hs-faq-item"><summary>Harness 和 Service 必须一起使用吗？</summary><p>可以独立选择：用 Harness SDK 开发并运行自己的 Agent 应用，或直接在 Service 中配置 Managed Agent，无需先开发一个 SDK 应用。Service 的托管 Agent 基于 AgentScope HarnessAgent 内核，由平台统一管理 Agent 运行时。已有的 Harness 应用也可以作为 External Agent 接入 Service，保留自己的运行进程，同时使用平台的发布与调用能力。</p></details>
<details className="hs-faq-item"><summary>刷新页面后，任务会重新执行吗？</summary><p>页面恢复通过快照和事件游标补齐已有消息、工具结果和进度。它与继续执行是两件事：继续中断的任务需要相应的会话恢复操作，checkpoint 也不会撤销已经发生的外部操作。</p></details>
<details className="hs-faq-item"><summary>需要哪些运行环境？</summary><p>Java Harness 需要 JDK 17 及以上，并配置所选模型的凭据。工具可在本地或配置的沙箱中执行。Service 当前主要推荐自托管部署；先启动平台、配置模型和工具环境，再运行第一个托管 Agent。</p></details></div>
</section>

<section className="hs-home-cta">
<h2>让下一个 Agent，接入真实业务</h2>
<p>使用 Harness SDK 开发自己的应用，或部署 Service，配置并调用由平台管理运行时的托管 Agent。</p>
<div className="hs-hero__actions">
<a href="/v2/zh/docs/quickstart" className="hs-btn hs-btn--primary">开始构建 Harness <span aria-hidden="true">→</span></a>
<a href="/v2/zh/service/quickstart" className="hs-btn hs-btn--secondary">部署并开始使用 Service <span aria-hidden="true">→</span></a>
</div>
</section>

</div>

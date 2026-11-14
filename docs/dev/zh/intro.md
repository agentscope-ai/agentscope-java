---
title: AgentScope Java dev
description: 使用 AgentScope Harness SDK 开发自己的 Agent 应用，组合上下文、工具、工作区与持久会话。
mode: custom
toc: false
en_link: /dev/en/intro
---

<div className="agentscope-landing agentscope-home">

<section className="hs-home-hero" aria-labelledby="home-title">
<div className="hs-home-copy">
<p className="hs-version-note">当前为开发版本，已发布的 SDK 请使用 <a href="/v2/zh/intro">2.0 文档</a>。</p>
<p className="hs-home-eyebrow">AGENTSCOPE DEV <span>JAVA</span></p>
<h1 id="home-title">让智能体持续工作，<br /><span>将能力嵌入应用。</span></h1>
<p className="hs-home-lead">使用 Harness SDK 开发自己的 Agent 应用，组合上下文、工作区、工具与持久会话，自主管理应用部署。</p>
<div className="hs-hero__actions">
<a href="/dev/zh/docs/quickstart" className="hs-btn hs-btn--primary">开始构建 Harness <span aria-hidden="true">→</span></a>
</div>
<a className="hs-home-source" href="https://github.com/agentscope-ai/agentscope-java">开源代码 <span aria-hidden="true">↗</span></a>
</div>

<div className="hs-home-platforms">
<a className="hs-home-platform hs-home-platform--harness" href="/dev/zh/docs/harness/architecture">
<span className="hs-home-label">SDK · 自主开发</span>
<h2>AgentScope Harness <span aria-hidden="true">↗</span></h2>
<p>将 SDK 嵌入自己的应用，组合上下文、工作区与工具执行，自主定制 Agent 逻辑并管理应用部署。</p>
<div className="hs-home-tags"><span>上下文管理</span><span>工具与沙箱</span><span>持久会话</span></div>
</a>
</div>
</section>

<section className="hs-home-section" aria-labelledby="capabilities-title">
<div className="hs-home-section-heading">
<h2 id="capabilities-title">为自己的应用构建 Agent</h2>
<p>在应用代码中组合 Harness SDK 能力，按业务需要定制 Agent 行为。</p>
</div>
<div className="hs-home-capabilities">
<div className="hs-home-product">
<span className="hs-home-label">HARNESS</span>
<h2>AgentScope Harness</h2>
<p>适合需要嵌入 Java 或 Spring Boot 应用、自定义运行逻辑的团队。HarnessAgent 提供推理与工具循环、工作区、上下文、技能和会话管理；你用代码组合这些能力，并负责应用的部署与运维。</p>
<div className="hs-home-features"><a className="hs-home-feature" href="/dev/zh/docs/harness/context"><h3>上下文与工作区<span aria-hidden="true">↗</span></h3><p>按推理步骤构建上下文，压缩长任务历史，按需加载记忆、Skill 与工作文件。</p></a>
<a className="hs-home-feature" href="/dev/zh/docs/harness/architecture"><h3>工具、沙箱与协作<span aria-hidden="true">↗</span></h3><p>接入 Java 工具和 MCP，在选定的执行环境中完成操作，并委派子 Agent 处理独立任务。</p></a>
<a className="hs-home-feature" href="/dev/zh/docs/harness/session-log"><h3>事件、交互与恢复<span aria-hidden="true">↗</span></h3><p>保存消息和工具过程，处理用户补充与人工确认，通过持久会话和 checkpoint 继续工作。</p></a></div>
</div>
</div>
</section>

<section className="hs-home-section hs-home-start" aria-labelledby="start-title">
<div className="hs-home-start-copy">
<h2 id="start-title">从你需要的入口开始</h2>
<p>将 Agent 嵌入已有 Java 应用。</p>
<div className="hs-home-start-links">
<a href="/dev/zh/docs/quickstart">Java 快速开始 <span aria-hidden="true">→</span></a>
</div>
<p className="hs-home-footnote">Harness 示例共享 Builder，每次请求构建新实例。</p>
</div>
<div className="hs-window">
<div className="hs-window__bar">
<div className="hs-window__dots" aria-hidden="true"><div className="hs-window__dot hs-window__dot--r"></div><div className="hs-window__dot hs-window__dot--y"></div><div className="hs-window__dot hs-window__dot--g"></div></div>
<div className="hs-window__tabs">
<span className="hs-tab active">Harness · Java</span>
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
<details className="hs-faq-item"><summary>如何继续中断的任务？</summary><p>原生 Thread 日志保存消息、工具事实与 checkpoint。后台任务排队、交互和恢复使用 Durable；checkpoint 不会撤销已经发生的外部操作。</p></details>
<details className="hs-faq-item"><summary>需要哪些运行环境？</summary><p>Java Harness 需要 JDK 17 及以上，并配置所选模型的凭据。工具可在本地或配置的沙箱中执行。</p></details></div>
</section>

<section className="hs-home-cta">
<h2>让下一个 Agent，接入真实业务</h2>
<p>使用 Harness SDK 开发自己的应用，定制上下文、工具和执行环境。</p>
<div className="hs-hero__actions">
<a href="/dev/zh/docs/quickstart" className="hs-btn hs-btn--primary">开始构建 Harness <span aria-hidden="true">→</span></a>
</div>
</section>

</div>

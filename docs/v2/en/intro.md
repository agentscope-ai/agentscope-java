---
title: AgentScope Java v2
description: Build your own Agent application with the AgentScope Harness SDK, or use managed Agent services through AgentScope Service with platform-managed runtimes.
mode: custom
toc: false
zh_link: /v2/zh/intro
---

<div className="agentscope-landing agentscope-home">

<section className="hs-home-hero" aria-labelledby="home-title">
<div className="hs-home-copy">
<p className="hs-home-eyebrow">AGENTSCOPE 2.0 <span>JAVA</span></p>
<h1 id="home-title">Build agents that work.<br /><span>Make them a service.</span></h1>
<p className="hs-home-lead">Build your own Agent application with the Harness SDK, or use managed Agents through the Service platform. Let the platform manage runtimes while your application accesses specialist capabilities and deliverables through APIs.</p>
<div className="hs-hero__actions">
<a href="/v2/en/docs/quickstart" className="hs-btn hs-btn--primary">Build with Harness <span aria-hidden="true">→</span></a>
<a href="/v2/en/service/index" className="hs-btn hs-btn--secondary">Explore Service <span aria-hidden="true">→</span></a>
</div>
<a className="hs-home-source" href="https://github.com/agentscope-ai/agentscope-java">Source on GitHub <span aria-hidden="true">↗</span></a>
</div>

<div className="hs-home-platforms">
<a className="hs-home-platform hs-home-platform--harness" href="/v2/en/docs/harness/architecture">
<span className="hs-home-label">SDK · BUILD YOUR OWN</span>
<h2>AgentScope Harness <span aria-hidden="true">↗</span></h2>
<p>Embed the SDK in your application, combine context, workspaces, and tools, and customize Agent behavior while managing your own deployment.</p>
<div className="hs-home-tags"><span>Context</span><span>Tools & sandboxes</span><span>Persistent sessions</span></div>
</a>
<div className="hs-home-connector">Two approaches to choose or combine</div>
<a className="hs-home-platform hs-home-platform--service" href="/v2/en/service/index">
<span className="hs-home-label">PLATFORM · MANAGED SERVICE</span>
<h2>AgentScope Service <span aria-hidden="true">↗</span></h2>
<p>Start with the recommended self-hosted deployment, configure Managed Agents built on HarnessAgent, and integrate through APIs. Service manages runtimes centrally, so each Agent application needs no separate runtime service to build and maintain.</p>
<div className="hs-home-tags"><span>Agent as a Service</span><span>Tasks · Interaction · Delivery</span></div>
</a>
</div>
</section>

<section className="hs-home-section" aria-labelledby="capabilities-title">
<div className="hs-home-section-heading">
<h2 id="capabilities-title">Build your own or use a managed service?</h2>
<p>Choose the Harness SDK for deep customization in application code. Choose Service to focus on business integration while the platform runs your Agents. Managed Agents in Service are built on the AgentScope HarnessAgent core, so both approaches share the same execution core.</p>
</div>
<div className="hs-home-capabilities">
<div className="hs-home-product">
<span className="hs-home-label">HARNESS</span>
<h2>AgentScope Harness</h2>
<p>For teams embedding Agents in Java or Spring Boot and customizing runtime behavior. HarnessAgent provides reasoning and tool loops, workspaces, context, skills, and sessions. Compose these capabilities in code and own your application's deployment and operations.</p>
<div className="hs-home-features"><a className="hs-home-feature" href="/v2/en/docs/harness/context"><h3>Context & workspaces<span aria-hidden="true">↗</span></h3><p>Build context at each reasoning step, compact long task histories, and load memory, skills, and working files as needed.</p></a>
<a className="hs-home-feature" href="/v2/en/docs/harness/architecture"><h3>Tools, sandboxes & collaboration<span aria-hidden="true">↗</span></h3><p>Connect Java tools and MCP, choose an execution environment, and delegate independent work to subagents.</p></a>
<a className="hs-home-feature" href="/v2/en/docs/harness/session-log"><h3>Events, interaction & recovery<span aria-hidden="true">↗</span></h3><p>Record messages and tool activity, handle user input and approvals, and continue work with persistent sessions and checkpoints.</p></a></div>
</div>
<div className="hs-home-product">
<span className="hs-home-label">SERVICE</span>
<h2>AgentScope Service</h2>
<p>For teams using Agent capabilities through APIs and reducing repeated runtime work. Configure instructions, models, tools, and resources to publish a managed Agent service. The platform handles execution, sessions, and tasks; your application focuses on business data, user experience, and acceptance.</p>
<div className="hs-home-features"><a className="hs-home-feature" href="/v2/en/service/usecases"><h3>Start with a business task<span aria-hidden="true">↗</span></h3><p>Bring Agents into SaaS pages, business processes, or scheduled work. Design the service around its inputs and deliverables.</p></a>
<a className="hs-home-feature" href="/v2/en/service/service-api"><h3>From invocation to delivery<span aria-hidden="true">↗</span></h3><p>Submit tasks or conversations, follow snapshots and events, handle human interaction, and retrieve results and files.</p></a>
<a className="hs-home-feature" href="/v2/en/service/orchestration"><h3>Choose how work executes<span aria-hidden="true">↗</span></h3><p>Run a managed HarnessAgent, connect an existing application, or reuse a Coding Agent. Add Teams and Workflows as the task requires.</p></a></div>
</div>
</div>
</section>

<section className="hs-home-section hs-home-start" aria-labelledby="start-title">
<div className="hs-home-start-copy">
<h2 id="start-title">Start where your application needs you</h2>
<p>Embed an Agent in Java, or call a managed Agent service over HTTP.</p>
<div className="hs-home-start-links">
<a href="/v2/en/docs/quickstart">Java quickstart <span aria-hidden="true">→</span></a>
<a href="/v2/en/service/service-api">Publish and invoke an Agent <span aria-hidden="true">→</span></a>
</div>
<p className="hs-home-footnote">The Harness example shares a Builder and creates an instance per request. The Service example creates a Session selecting an Agent, then submits a Turn.</p>
</div>
<div className="hs-window">
<div className="hs-window__bar">
<div className="hs-window__dots" aria-hidden="true"><div className="hs-window__dot hs-window__dot--r"></div><div className="hs-window__dot hs-window__dot--y"></div><div className="hs-window__dot hs-window__dot--g"></div></div>
<div className="hs-window__tabs">
<button type="button" aria-pressed={true} aria-controls="en-harness" className="hs-tab active" data-panel="en-harness">Harness · Java</button>
<button type="button" aria-pressed={false} aria-controls="en-service" className="hs-tab" data-panel="en-service">Service · HTTP</button>
</div>
</div>
<div className="hs-code-panel" id="en-harness">

```java
// Share configuration; build per request
var builder = HarnessAgent.builder()
    .name("notes-assistant")
    .agentId("notes-assistant")
    .model("dashscope:qwen-plus")
    .workspace(Path.of(".agentscope/workspace"));

try (var agent = builder.build()) {
    var context = RuntimeContext.builder()
        .userId("alice").sessionId("notes").build();
    agent.streamEvents(
        new UserMessage("Extract tasks and open questions."), context)
        .doOnNext(System.out::println)
        .blockLast();
}
```

</div>
<div className="hs-code-panel" id="en-service" style={{"display": "none"}}>

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
 <span className="hs-adoption__eyebrow-dot"></span>Battle-tested in Production
</div>

<div className="hs-adoption__stats">

<div className="hs-stat">
 <span className="hs-stat__val">Alibaba Group</span> <span className="hs-stat__label">the most widely used agent framework in-house (Java &amp; Python), in production across 13+ business units</span>
</div>

<div className="hs-stat">
 <span className="hs-stat__val">Open Source Community</span> <span className="hs-stat__label">adopted by industry leaders across 10+ sectors via open source &amp; Alibaba Cloud</span>
</div>

</div>

<div className="hs-adoption__row">
 <span className="hs-adoption__row-label">Alibaba Group</span>
<div className="hs-marquee">

<div className="hs-marquee__track">
 <span className="hs-marquee__group"> <span className="hs-tag">Fliggy</span><span className="hs-tag">Taobao Instant Commerce</span><span className="hs-tag">Whale Entertainment</span><span className="hs-tag">AIDC</span><span className="hs-tag">Alibaba Holding</span><span className="hs-tag">Taotian Trade</span><span className="hs-tag">Taobao App</span><span className="hs-tag">1688</span><span className="hs-tag">Qwen App</span><span className="hs-tag">Amap</span><span className="hs-tag">Alibaba Cloud</span><span className="hs-tag">Ant International</span><span className="hs-tag">Ant Global Payments</span><span className="hs-tag hs-tag--more">…</span> </span> <span className="hs-marquee__group" aria-hidden="true"> <span className="hs-tag">Fliggy</span><span className="hs-tag">Taobao Instant Commerce</span><span className="hs-tag">Whale Entertainment</span><span className="hs-tag">AIDC</span><span className="hs-tag">Alibaba Holding</span><span className="hs-tag">Taotian Trade</span><span className="hs-tag">Taobao App</span><span className="hs-tag">1688</span><span className="hs-tag">Qwen App</span><span className="hs-tag">Amap</span><span className="hs-tag">Alibaba Cloud</span><span className="hs-tag">Ant International</span><span className="hs-tag">Ant Global Payments</span><span className="hs-tag hs-tag--more">…</span> </span>
</div>

</div>

</div>

<div className="hs-adoption__row">
 <span className="hs-adoption__row-label">Open Source · Cloud</span>
<div className="hs-marquee hs-marquee--reverse">

<div className="hs-marquee__track">
 <span className="hs-marquee__group"> <span className="hs-tag">Finance</span><span className="hs-tag">Transportation &amp; Logistics</span><span className="hs-tag">Retail</span><span className="hs-tag">Manufacturing</span><span className="hs-tag">Energy</span><span className="hs-tag">Healthcare</span><span className="hs-tag">Education &amp; Gov Media</span><span className="hs-tag">Internet</span><span className="hs-tag">SaaS</span><span className="hs-tag">Consulting</span><span className="hs-tag hs-tag--more">and more industry leaders</span> </span> <span className="hs-marquee__group" aria-hidden="true"> <span className="hs-tag">Finance</span><span className="hs-tag">Transportation &amp; Logistics</span><span className="hs-tag">Retail</span><span className="hs-tag">Manufacturing</span><span className="hs-tag">Energy</span><span className="hs-tag">Healthcare</span><span className="hs-tag">Education &amp; Gov Media</span><span className="hs-tag">Internet</span><span className="hs-tag">SaaS</span><span className="hs-tag">Consulting</span><span className="hs-tag hs-tag--more">and more industry leaders</span> </span>
</div>

</div>

</div>

</div>



<section className="hs-home-faq" aria-labelledby="faq-title">
<h2 id="faq-title">Before you start</h2>
<div><details className="hs-faq-item"><summary>How do HarnessAgent and ReActAgent relate?</summary><p>ReActAgent provides reasoning, tools, messages, permissions, and middleware. HarnessAgent uses the same ReAct loop and adds workspaces, memory, skills, subagents, and session management. Start with HarnessAgent, or use ReActAgent directly to assemble your own runtime.</p></details>
<details className="hs-faq-item"><summary>Must Harness and Service be used together?</summary><p>You can choose either: develop and run your own Agent application with the Harness SDK, or configure a Managed Agent directly in Service without first building an SDK application. Managed Agents in Service are built on the AgentScope HarnessAgent core, with the platform managing Agent runtimes centrally. Existing Harness applications can also connect as External Agents, keeping their own processes while using the platform's publishing and invocation capabilities.</p></details>
<details className="hs-faq-item"><summary>Does refreshing a page restart the task?</summary><p>Snapshots and event cursors restore recorded messages, tool results, and progress. Resuming execution is a separate operation. Continuing interrupted work requires the relevant session operation, and checkpoints do not undo external side effects.</p></details>
<details className="hs-faq-item"><summary>What runtime do I need?</summary><p>The Java Harness requires JDK 17 or later and credentials for your chosen model. Tools can run locally or in a configured sandbox. Self-hosting is currently recommended for Service. Deploy the platform, configure models and tools, then run your first Managed Agent.</p></details></div>
</section>

<section className="hs-home-cta">
<h2>Bring your next Agent into your business</h2>
<p>Develop your application with the Harness SDK, or deploy Service and call Managed Agents with platform-managed runtimes.</p>
<div className="hs-hero__actions">
<a href="/v2/en/docs/quickstart" className="hs-btn hs-btn--primary">Build with Harness <span aria-hidden="true">→</span></a>
<a href="/v2/en/service/quickstart" className="hs-btn hs-btn--secondary">Deploy and start using Service <span aria-hidden="true">→</span></a>
</div>
</section>

</div>

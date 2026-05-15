---
hide-toc: true
---

```{raw} html
<script>document.body.classList.add('agentscope-home');</script>

<div class="agentscope-landing">

<!-- Hero -->
<div class="hs-hero">
  <div>
    <h1 class="hs-hero__headline">Build production agents, <br><span class="hs-hero__accent">not prototypes.</span></h1>
    <p class="hs-hero__desc">AgentScope Java is the open-source agent framework for the JVM. ReAct reasoning, Harness engineering infrastructure, multi-agent orchestration, and MCP/A2A protocol support — from local prototype to enterprise-scale deployment.</p>
    <div class="hs-hero__actions">
      <a href="quickstart/installation.html" class="hs-btn hs-btn--primary">Get started →</a>
      <a href="https://github.com/agentscope-ai/agentscope-java" class="hs-btn hs-btn--secondary">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 6.477 2 12c0 4.42 2.865 8.167 6.839 9.49.5.09.682-.217.682-.48 0-.237-.008-.866-.013-1.7-2.782.603-3.369-1.342-3.369-1.342-.454-1.155-1.11-1.462-1.11-1.462-.908-.62.069-.608.069-.608 1.003.07 1.531 1.03 1.531 1.03.892 1.529 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.11-4.555-4.943 0-1.091.39-1.984 1.029-2.683-.103-.253-.446-1.27.098-2.647 0 0 .84-.268 2.75 1.026A9.578 9.578 0 0112 6.836c.85.004 1.705.114 2.504.336 1.909-1.294 2.747-1.026 2.747-1.026.546 1.377.203 2.394.1 2.647.64.699 1.028 1.592 1.028 2.683 0 3.842-2.339 4.687-4.566 4.935.359.309.678.919.678 1.852 0 1.336-.012 2.415-.012 2.741 0 .267.18.577.688.48C19.138 20.163 22 16.418 22 12c0-5.523-4.477-10-10-10z"/></svg>
        GitHub
      </a>
    </div>
    <div class="hs-hero__badges">
      <span class="hs-badge">JDK 17+</span>
      <span class="hs-badge">Maven Central</span>
      <span class="hs-badge">Apache 2.0</span>
    </div>
  </div>
  <div>
    <div class="hs-window">
      <div class="hs-window__bar">
        <div class="hs-window__dots">
          <div class="hs-window__dot hs-window__dot--r"></div>
          <div class="hs-window__dot hs-window__dot--y"></div>
          <div class="hs-window__dot hs-window__dot--g"></div>
        </div>
        <div class="hs-window__tabs">
          <div class="hs-tab active" data-panel="en-react">Core Agent</div>
          <div class="hs-tab" data-panel="en-harness">Harness Agent</div>
        </div>
      </div>
      <div class="hs-code-panel" id="en-react"><pre><span class="kw">import</span> io.agentscope.core.*;
<span class="kw">var</span> agent = <span class="ty">ReActAgent</span>.builder()
    .name(<span class="str">"assistant"</span>)
    .model(<span class="kw">new</span> <span class="ty">QwenConfig</span>(<span class="str">"qwen-plus"</span>))
    .tools(<span class="ty">List</span>.of(searchTool, codeTool))
    .build();
<span class="cm">// Autonomous reasoning + tool calling</span>
agent.call(messages).block();</pre></div>
      <div class="hs-code-panel" id="en-harness" style="display:none"><pre><span class="kw">import</span> io.agentscope.harness.*;
<span class="kw">var</span> agent = <span class="ty">HarnessAgent</span>.builder()
    .name(<span class="str">"coder"</span>)
    .model(<span class="kw">new</span> <span class="ty">QwenConfig</span>(<span class="str">"qwen-plus"</span>))
    .filesystem(<span class="kw">new</span> <span class="ty">LocalFilesystemSpec</span>(<span class="str">"./workspace"</span>))
    .session(<span class="kw">new</span> <span class="ty">RedisSession</span>(jedisPool))
    .build();
<span class="cm">// Stateful · resumable · memory-persistent</span>
agent.call(messages, <span class="ty">RuntimeContext</span>.builder()
    .sessionId(<span class="str">"user-123"</span>).build()).block();</pre></div>
      <div class="hs-install">
        <code>&lt;artifactId&gt;agentscope&lt;/artifactId&gt;</code>
        <button class="hs-copy-btn" data-copy="&lt;dependency&gt;&#10;  &lt;groupId&gt;io.agentscope&lt;/groupId&gt;&#10;  &lt;artifactId&gt;agentscope&lt;/artifactId&gt;&#10;  &lt;version&gt;1.1.0&lt;/version&gt;&#10;&lt;/dependency&gt;">Copy Maven</button>
      </div>
    </div>
  </div>
</div>

<!-- Stats strip -->
<div class="hs-stats">
  <div class="hs-stat">
    <span class="hs-stat__val">JDK 17+</span>
    <span class="hs-stat__label">minimum Java version</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">Reactive</span>
    <span class="hs-stat__label">built on Project Reactor</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">MCP · A2A</span>
    <span class="hs-stat__label">open protocol support</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">Apache 2.0</span>
    <span class="hs-stat__label">open-source license</span>
  </div>
</div>

<!-- Feature 1: Harness -->
<div class="hs-section">
  <div class="hs-split">
    <div class="hs-split__text">
      <div class="hs-chip">Harness Engineering</div>
      <h2>Enterprise patterns, zero boilerplate.</h2>
      <p>The Harness module injects workspace management, memory persistence, and context compression into the reasoning loop via hooks — without modifying core inference logic.</p>
      <ul>
        <li>Structured workspace as the single source of truth</li>
        <li>Cross-session long-term memory with automatic consolidation</li>
        <li>Context overflow protection with auto-compaction</li>
        <li>Local disk → Docker sandbox → object storage, one line to switch</li>
      </ul>
      <a href="harness/overview.html" class="hs-btn hs-btn--secondary" style="margin-top:4px">Learn about Harness →</a>
    </div>
    <div class="hs-split__visual">
      <div class="hs-visual">
        <div class="hs-visual__bar">
          <div class="hs-visual__bar-dots">
            <div class="hs-window__dot hs-window__dot--r"></div>
            <div class="hs-window__dot hs-window__dot--y"></div>
            <div class="hs-window__dot hs-window__dot--g"></div>
          </div>
          <span class="hs-visual__bar-title">workspace/</span>
        </div>
        <div class="hs-terminal"><pre><span class="t-dim">workspace/</span>
<span class="t-dim">├──</span> <span class="t-ok">AGENTS.md</span>          <span class="t-dim"># persona + system instructions</span>
<span class="t-dim">├──</span> <span class="t-ok">MEMORY.md</span>         <span class="t-dim"># long-term memory (auto-managed)</span>
<span class="t-dim">├──</span> <span class="t-info">knowledge/</span>        <span class="t-dim"># domain knowledge + RAG index</span>
<span class="t-dim">├──</span> <span class="t-info">skills/</span>           <span class="t-dim"># reusable skill files</span>
<span class="t-dim">├──</span> <span class="t-info">subagents/</span>        <span class="t-dim"># sub-agent specifications</span>
<span class="t-dim">└──</span> <span class="t-warn">agents/coder/session-user-123/</span>
<span class="t-dim">    ├──</span> chat-history.jsonl
<span class="t-dim">    └──</span> scratchpad/
<span class="t-prompt">$</span> <span class="t-dim">each call → load → reason → write back memory</span></pre></div>
      </div>
    </div>
  </div>
</div>

<!-- Feature 2: Multi-Agent -->
<div class="hs-section">
  <div class="hs-split hs-split--rev">
    <div class="hs-split__visual">
      <div class="hs-visual">
        <div class="hs-visual__bar">
          <div class="hs-visual__bar-dots">
            <div class="hs-window__dot hs-window__dot--r"></div>
            <div class="hs-window__dot hs-window__dot--y"></div>
            <div class="hs-window__dot hs-window__dot--g"></div>
          </div>
          <span class="hs-visual__bar-title">supervisor → subagents</span>
        </div>
        <div class="hs-code-panel" style="display:block"><pre><span class="cm">// workspace/subagents/researcher.md</span>
<span class="cm">## researcher</span>
<span class="cm">- model: qwen-plus</span>
<span class="cm">- tools: [web_search, read_file]</span>
<span class="cm">// workspace/subagents/coder.md</span>
<span class="cm">## coder</span>
<span class="cm">- model: qwen-coder-plus</span>
<span class="cm">- filesystem: sandbox</span>
<span class="cm">// Delegate synchronously or asynchronously</span>
<span class="ty">String</span> report = agent
    .delegate(<span class="str">"researcher"</span>, <span class="str">"analyze competitors"</span>)
    .block();
agent.delegateAsync(<span class="str">"coder"</span>, <span class="str">"implement feature"</span>)
     .subscribe(result -> log.info(result));</pre></div>
      </div>
    </div>
    <div class="hs-split__text">
      <div class="hs-chip">Multi-Agent</div>
      <h2>Orchestrate agents like microservices.</h2>
      <p>Declare sub-agent specs in Markdown files. The supervisor spawns sub-agents on demand at runtime, supporting both blocking and non-blocking delegation modes.</p>
      <ul>
        <li>Declarative sub-agent definitions — no code changes needed</li>
        <li>Synchronous blocking and async non-blocking delegation</li>
        <li>A2A protocol for cross-process and cross-machine agent calls</li>
        <li>Sub-agents can independently inherit or override Harness config</li>
      </ul>
      <a href="task/multi-agent.html" class="hs-btn hs-btn--secondary" style="margin-top:4px">Learn about multi-agent →</a>
    </div>
  </div>
</div>

<!-- Feature Cards -->
<div class="hs-section">
  <div class="hs-section-hd">
    <h2>Full-stack capabilities, production-hardened</h2>
    <p>From reasoning core to enterprise deployment, AgentScope Java covers the full agent development lifecycle.</p>
  </div>
  <div class="hs-cards">
    <a class="hs-card" href="quickstart/agent.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456z"/></svg>
      <h3>ReAct Reasoning</h3>
      <p>Autonomous planning, tool calling, and result integration. Built-in safe interruption, graceful cancellation, and hook-based human-in-the-loop oversight.</p>
      <span class="hs-card__link">Quick start →</span>
    </a>
    <a class="hs-card" href="harness/memory.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.75 12h16.5M3.75 19.5h16.5M3.75 4.5h16.5M7.5 7.5a3 3 0 110-6 3 3 0 010 6zm9 0a3 3 0 110-6 3 3 0 010 6zm-9 13.5a3 3 0 110-6 3 3 0 010 6zm9 0a3 3 0 110-6 3 3 0 010 6z"/></svg>
      <h3>Memory &amp; RAG</h3>
      <p>Persistent cross-session memory with semantic search. Automatic background consolidation prevents context bloat. Multi-tenant isolation out of the box.</p>
      <span class="hs-card__link">Learn about memory →</span>
    </a>
    <a class="hs-card" href="task/mcp.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244"/></svg>
      <h3>MCP Protocol</h3>
      <p>Connect to any MCP-compatible server — file systems, databases, browsers, code interpreters — instantly extending agent tool capabilities without custom integration code.</p>
      <span class="hs-card__link">View MCP →</span>
    </a>
    <a class="hs-card" href="task/a2a.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M7.5 21L3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5"/></svg>
      <h3>A2A Protocol</h3>
      <p>Register agents with Nacos or similar service registries. Other agents can discover and delegate tasks as naturally as calling a microservice.</p>
      <span class="hs-card__link">View A2A →</span>
    </a>
    <a class="hs-card" href="harness/sandbox/index.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z"/></svg>
      <h3>Sandbox Isolation</h3>
      <p>Tool execution in isolated environments (local Unix, Docker, E2B). Sandbox state is fully preserved across multi-turn conversations with cross-session snapshot restore.</p>
      <span class="hs-card__link">Learn about sandboxes →</span>
    </a>
    <a class="hs-card" href="task/observability.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"/></svg>
      <h3>Observability</h3>
      <p>Pluggable Tracer SPI with native OpenTelemetry support for distributed tracing. AgentScope Studio provides visual debugging and real-time monitoring dashboards.</p>
      <span class="hs-card__link">View Studio →</span>
    </a>
  </div>
</div>

<!-- CTA -->
<div class="hs-cta">
  <h2>Ready to build?</h2>
  <p>Run your first agent in minutes. Start with a basic ReActAgent, then layer in Harness engineering, multi-agent orchestration, and enterprise capabilities as you need them — the same API scales with you.</p>
  <a href="quickstart/installation.html" class="hs-btn hs-btn--primary">Start building →</a>
</div>

<!-- FAQ -->
<div class="hs-faq">
  <div class="hs-faq__hd">
    <h2>Frequently Asked Questions</h2>
    <p>Still have questions? Ask on <a href="https://github.com/agentscope-ai/agentscope-java/discussions" style="color:var(--hs-accent)">GitHub Discussions</a>.</p>
  </div>
  <details class="hs-faq-item">
    <summary>What Java version is required?</summary>
    <p>AgentScope Java requires <code>JDK 17</code> or higher. The framework uses modern Java features such as Records and Sealed Classes, and is built on Project Reactor for a non-blocking reactive execution model. For ultra-low cold-start latency, native image compilation via Quarkus and GraalVM is supported.</p>
  </details>
  <details class="hs-faq-item">
    <summary>Which LLM providers are supported?</summary>
    <p>Any model compatible with the OpenAI Chat Completions API is supported out of the box, including Alibaba Cloud Qwen, OpenAI GPT series, Anthropic Claude, and local Ollama instances. The framework provides a standard <code>ModelConfig</code> SPI for adding custom model providers via plugins.</p>
  </details>
  <details class="hs-faq-item">
    <summary>What is the difference between Harness and a plain ReActAgent?</summary>
    <p>A <code>ReActAgent</code> is the core reasoning–tool–response loop. <code>HarnessAgent</code> injects workspace loading, memory persistence, context compaction, and sandbox lifecycle management on top of the same reasoning core via the Hook system. You can start with <code>ReActAgent</code> and migrate seamlessly to <code>HarnessAgent</code> when you need production engineering capabilities — no business logic changes required.</p>
  </details>
  <details class="hs-faq-item">
    <summary>Can I use AgentScope Java with Spring Boot or Quarkus?</summary>
    <p>Yes. AgentScope Java's core modules are framework-agnostic Java libraries that can be added as dependencies to any JVM application — Spring Boot, Quarkus, Micronaut, or plain Java. Quarkus further supports GraalVM native image compilation for sub-100ms cold starts, making it ideal for serverless environments.</p>
  </details>
  <details class="hs-faq-item">
    <summary>How do I scale horizontally in production?</summary>
    <p>AgentScope Java is designed for stateless horizontal scaling. Move session state to <code>RedisSession</code> and workspace files to object storage (<code>OssFilesystemSpec</code>); any replica can then fully restore a user's context. Combined with Kubernetes HPA, this enables elastic scaling with no sticky sessions required.</p>
  </details>
</div>

</div><!-- .agentscope-landing -->

<script>
(function () {
  document.addEventListener('click', function (e) {
    var tab = e.target.closest('.hs-tab');
    if (!tab) return;
    var win = tab.closest('.hs-window');
    if (!win) return;
    var panelId = tab.getAttribute('data-panel');
    win.querySelectorAll('.hs-tab').forEach(function (t) { t.classList.remove('active'); });
    win.querySelectorAll('.hs-code-panel').forEach(function (p) { p.style.display = 'none'; });
    tab.classList.add('active');
    var panel = document.getElementById(panelId);
    if (panel) panel.style.display = 'block';
  });
  document.querySelectorAll('.hs-copy-btn').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var text = btn.getAttribute('data-copy');
      if (!text || !navigator.clipboard) return;
      navigator.clipboard.writeText(text).then(function () {
        var orig = btn.textContent;
        btn.textContent = '✓ Copied';
        setTimeout(function () { btn.textContent = orig; }, 1800);
      });
    });
  });
})();
</script>
```

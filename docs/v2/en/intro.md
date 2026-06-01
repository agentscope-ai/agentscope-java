---
hide-toc: true
---

```{raw} html
<script>document.body.classList.add('agentscope-home');</script>

<div class="agentscope-landing">

<!-- Hero -->
<div class="hs-hero">
  <div>
    <h1 class="hs-hero__headline">A <span class="hs-hero__accent">production-grade</span> agent framework, redesigned for JVM 2.0.</h1>
    <p class="hs-hero__desc">AgentScope Java 2.0 redesigns the agent abstraction around a typed event system, fine-grained permission control, and a composable middleware stack — all built on Project Reactor for non-blocking execution.</p>
    <div class="hs-hero__actions">
      <a href="docs/quickstart.html" class="hs-btn hs-btn--primary">Get started →</a>
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
          <div class="hs-tab active" data-panel="en-react">ReActAgent</div>
        </div>
      </div>
      <div class="hs-code-panel" id="en-react"><pre><span class="kw">import</span> io.agentscope.core.agent.ReActAgent;
<span class="kw">import</span> io.agentscope.core.model.DashScopeChatModel;
<span class="kw">var</span> agent = <span class="ty">ReActAgent</span>.builder()
    .name(<span class="str">"assistant"</span>)
    .model(<span class="ty">DashScopeChatModel</span>.builder()
        .apiKey(System.getenv(<span class="str">"DASHSCOPE_API_KEY"</span>))
        .modelName(<span class="str">"qwen-plus"</span>)
        .build())
    .toolkit(toolkit)
    .build();
<span class="cm">// streamEvents emits per-step events for UI streaming + HITL</span>
agent.streamEvents(messages).blockLast();</pre></div>
      <div class="hs-install">
        <code>io.agentscope:agentscope-core</code>
        <button class="hs-copy-btn" data-copy="io.agentscope:agentscope-core">Copy Maven</button>
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
    <span class="hs-stat__val">Event-first</span>
    <span class="hs-stat__label">typed AgentEvent streams</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">Apache 2.0</span>
    <span class="hs-stat__label">open-source license</span>
  </div>
</div>

<!-- Feature Cards -->
<div class="hs-section">
  <div class="hs-section-hd">
    <h2>Core building blocks</h2>
    <p>2.0 ships a focused set of composable pieces. Use only what you need; opt into more as you scale.</p>
  </div>
  <div class="hs-cards">
    <a class="hs-card" href="docs/building-blocks/agent.html">
      <h3>Agent</h3>
      <p>A unified ReAct loop with streaming events, structured output, configurable iteration bounds, and a clean interface for human-in-the-loop pauses.</p>
      <span class="hs-card__link">View Agent →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/message-and-event.html">
      <h3>Message &amp; Event</h3>
      <p>Typed Msg with content blocks plus AgentEvent streams. Per-step events stream straight into your UI and HITL flows; no manual diffing.</p>
      <span class="hs-card__link">View Events →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/permission-system.html">
      <h3>Permission</h3>
      <p>Gate dangerous tool calls before they touch the host. Suggest allow-rules at call time; persist user-accepted rules for future auto-approval.</p>
      <span class="hs-card__link">View Permission →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/middleware.html">
      <h3>Middleware</h3>
      <p>Onion hooks at onAgent / onReasoning / onActing / onModelCall plus an onSystemPrompt transformer. Compose logging, tracing, retries on any agent.</p>
      <span class="hs-card__link">View Middleware →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/model.html">
      <h3>Model</h3>
      <p>Credential + ChatModel two-tier abstraction. OpenAI, Anthropic, DashScope (Qwen), Gemini, Ollama supported out of the box.</p>
      <span class="hs-card__link">View Model →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/tool.html">
      <h3>Tool</h3>
      <p>Annotation-driven tool registration, MCP adapter, tool groups, and auto-batched concurrent or sequential execution based on each tool's properties.</p>
      <span class="hs-card__link">View Tool →</span>
    </a>
  </div>
</div>

<!-- CTA -->
<div class="hs-cta">
  <h2>Ready to build?</h2>
  <p>Walk through the quickstart and you'll have a working ReActAgent in minutes. Layer in the permission system, middleware, and structured output as your scenario demands.</p>
  <a href="docs/quickstart.html" class="hs-btn hs-btn--primary">Start building →</a>
</div>

<!-- FAQ -->
<div class="hs-faq">
  <div class="hs-faq__hd">
    <h2>Frequently Asked Questions</h2>
    <p>Full list in the <a href="docs/others/faq.html" style="color:var(--hs-accent)">FAQ</a>, or ask on <a href="https://github.com/agentscope-ai/agentscope-java/discussions" style="color:var(--hs-accent)">GitHub Discussions</a>.</p>
  </div>
  <details class="hs-faq-item">
    <summary>What Java version is required?</summary>
    <p>AgentScope Java requires <code>JDK 17</code> or higher. The framework uses modern Java features such as Records and Sealed Classes, and is built on Project Reactor for a non-blocking reactive execution model.</p>
  </details>
  <details class="hs-faq-item">
    <summary>Which LLM providers are supported?</summary>
    <p>Out of the box: OpenAI (and OpenAI-compatible endpoints — vLLM, DeepSeek, Kimi), Anthropic Claude, Alibaba Cloud Qwen via DashScope, Google Gemini, and locally hosted Ollama. Each is a separate <code>ChatModel</code> implementation behind a unified builder.</p>
  </details>
  <details class="hs-faq-item">
    <summary>Is 2.0 compatible with 1.0?</summary>
    <p>No. 2.0 is a breaking release that redesigns the agent abstraction and introduces a new event system, permission system, and middleware stack. There is no automatic migration path; for new projects we recommend starting on 2.0 directly, while the 1.0 docs remain available for existing users.</p>
  </details>
  <details class="hs-faq-item">
    <summary>Can I use AgentScope Java with Spring Boot or Quarkus?</summary>
    <p>Yes. The core modules are framework-agnostic Java libraries that drop into any JVM application — Spring Boot, Quarkus, Micronaut, or plain Java. Quarkus additionally supports GraalVM native image compilation for sub-100ms cold starts.</p>
  </details>
</div>

</div><!-- .agentscope-landing -->

<script>
(function () {
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

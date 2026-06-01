---
hide-toc: true
---

```{raw} html
<script>document.body.classList.add('agentscope-home');</script>

<div class="agentscope-landing">

<!-- Hero -->
<div class="hs-hero">
  <div>
    <h1 class="hs-hero__headline">为 <span class="hs-hero__accent">生产级</span> JVM 应用重做的 2.0 智能体框架</h1>
    <p class="hs-hero__desc">AgentScope Java 2.0 围绕类型化事件系统、细粒度权限控制与可组合的 middleware 重新设计了 agent 抽象，全部建立在 Project Reactor 之上，提供非阻塞执行模型。</p>
    <div class="hs-hero__actions">
      <a href="docs/quickstart.html" class="hs-btn hs-btn--primary">快速开始 →</a>
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
          <div class="hs-tab active" data-panel="zh-react">ReActAgent</div>
        </div>
      </div>
      <div class="hs-code-panel" id="zh-react"><pre><span class="kw">import</span> io.agentscope.core.agent.ReActAgent;
<span class="kw">import</span> io.agentscope.core.model.DashScopeChatModel;
<span class="kw">var</span> agent = <span class="ty">ReActAgent</span>.builder()
    .name(<span class="str">"assistant"</span>)
    .model(<span class="ty">DashScopeChatModel</span>.builder()
        .apiKey(System.getenv(<span class="str">"DASHSCOPE_API_KEY"</span>))
        .modelName(<span class="str">"qwen-plus"</span>)
        .build())
    .toolkit(toolkit)
    .build();
<span class="cm">// streamEvents 逐步发出事件，直接驱动 UI 与 HITL</span>
agent.streamEvents(messages).blockLast();</pre></div>
      <div class="hs-install">
        <code>io.agentscope:agentscope-core</code>
        <button class="hs-copy-btn" data-copy="io.agentscope:agentscope-core">复制 Maven 坐标</button>
      </div>
    </div>
  </div>
</div>

<!-- Stats strip -->
<div class="hs-stats">
  <div class="hs-stat">
    <span class="hs-stat__val">JDK 17+</span>
    <span class="hs-stat__label">最低 Java 版本</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">Reactive</span>
    <span class="hs-stat__label">基于 Project Reactor</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">事件优先</span>
    <span class="hs-stat__label">类型化 AgentEvent 流</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">Apache 2.0</span>
    <span class="hs-stat__label">开源协议</span>
  </div>
</div>

<!-- Feature Cards -->
<div class="hs-section">
  <div class="hs-section-hd">
    <h2>核心组件</h2>
    <p>2.0 提供一组聚焦、可组合的核心组件，按需使用，随场景扩展。</p>
  </div>
  <div class="hs-cards">
    <a class="hs-card" href="docs/building-blocks/agent.html">
      <h3>Agent</h3>
      <p>统一的 ReAct 主循环：流式事件、结构化输出、可配置的迭代上限，以及干净的人审暂停接口。</p>
      <span class="hs-card__link">了解 Agent →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/message-and-event.html">
      <h3>消息与事件</h3>
      <p>带 ContentBlock 的类型化 Msg + AgentEvent 流。逐步事件直接驱动 UI 与 HITL，不需要手工 diff。</p>
      <span class="hs-card__link">了解事件 →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/permission-system.html">
      <h3>权限</h3>
      <p>危险工具调用执行前先要审批。运行时给出建议规则；用户接受的规则被持久化，下次同类调用自动放行。</p>
      <span class="hs-card__link">了解权限 →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/middleware.html">
      <h3>Middleware</h3>
      <p>onAgent / onReasoning / onActing / onModelCall 洋葱式 hook + onSystemPrompt 变换式 hook。日志、追踪、重试都能挂上去。</p>
      <span class="hs-card__link">了解 Middleware →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/model.html">
      <h3>模型</h3>
      <p>Credential + ChatModel 两层抽象。OpenAI、Anthropic、DashScope（Qwen）、Gemini、Ollama 开箱即用。</p>
      <span class="hs-card__link">了解模型 →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/tool.html">
      <h3>工具</h3>
      <p>注解驱动的工具注册、MCP 适配、Tool Group，按各工具属性自动批处理串行或并发执行。</p>
      <span class="hs-card__link">了解工具 →</span>
    </a>
  </div>
</div>

<!-- CTA -->
<div class="hs-cta">
  <h2>准备好开始构建了吗？</h2>
  <p>跟着 quickstart 走，几分钟就能跑起来一个 ReActAgent。之后按场景加权限、middleware 和结构化输出。</p>
  <a href="docs/quickstart.html" class="hs-btn hs-btn--primary">开始构建 →</a>
</div>

<!-- FAQ -->
<div class="hs-faq">
  <div class="hs-faq__hd">
    <h2>常见问题</h2>
    <p>完整问答见 <a href="docs/others/faq.html" style="color:var(--hs-accent)">FAQ</a>，或到 <a href="https://github.com/agentscope-ai/agentscope-java/discussions" style="color:var(--hs-accent)">GitHub Discussions</a> 提问。</p>
  </div>
  <details class="hs-faq-item">
    <summary>需要哪个 Java 版本？</summary>
    <p>需要 <code>JDK 17</code> 及以上。框架用了 Records、Sealed Classes 等现代特性，并基于 Project Reactor 提供非阻塞响应式执行模型。</p>
  </details>
  <details class="hs-faq-item">
    <summary>支持哪些 LLM 提供商？</summary>
    <p>开箱支持：OpenAI（以及 OpenAI 兼容端点，含 vLLM、DeepSeek、Kimi）、Anthropic Claude、阿里云通义千问（DashScope）、Google Gemini、本地 Ollama。每个都是统一 builder 后面一份独立的 <code>ChatModel</code> 实现。</p>
  </details>
  <details class="hs-faq-item">
    <summary>2.0 兼容 1.0 吗？</summary>
    <p>不兼容。2.0 是一次破坏性发版，重做了 agent 抽象，新增事件系统、权限系统与 middleware 栈，没有自动迁移路径。新项目建议直接从 2.0 起步；存量项目可继续使用 1.0 文档。</p>
  </details>
  <details class="hs-faq-item">
    <summary>能搭配 Spring Boot 或 Quarkus 使用吗？</summary>
    <p>可以。核心模块是与框架无关的 Java 库，可作为依赖加入任何 JVM 应用 —— Spring Boot、Quarkus、Micronaut 或纯 Java 都行。Quarkus 还能配合 GraalVM 编译原生镜像，做到 100 ms 内冷启动。</p>
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
        btn.textContent = '✓ 已复制';
        setTimeout(function () { btn.textContent = orig; }, 1800);
      });
    });
  });
})();
</script>
```

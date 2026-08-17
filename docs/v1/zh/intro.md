---
hide-toc: true
---

```{raw} html
<script>document.body.classList.add('agentscope-home');</script>

<div class="agentscope-landing">

<!-- ============================================================
     Hero
     ============================================================ -->
<div class="hs-hero">
  <div>
    <h1 class="hs-hero__headline">专为<span class="hs-hero__accent">分布式、企业级</span>智能体<br/>打造的 Harness 框架。</h1>
    <p class="hs-hero__desc">AgentScope Java 是面向 JVM 的开源 Agent 框架。提供 ReAct 推理、Harness 工程化基础设施、多智能体编排与 MCP/A2A 协议支持，覆盖从本地原型到企业级分布式部署全链路。</p>
    <div class="hs-hero__actions">
      <a href="docs/harness/overview.html" class="hs-btn hs-btn--primary">快速开始 →</a>
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
          <div class="hs-tab active" data-panel="zh-harness">HarnessAgent</div>
          <div class="hs-tab" data-panel="zh-react">ReActAgent</div>
        </div>
      </div>
      <div class="hs-code-panel" id="zh-react" style="display:none"><pre><span class="kw">import</span> io.agentscope.core.*;
<span class="kw">var</span> agent = <span class="ty">ReActAgent</span>.builder()
    .name(<span class="str">"assistant"</span>)
    .model(<span class="kw">new</span> <span class="ty">QwenConfig</span>(<span class="str">"qwen-plus"</span>))
    .tools(<span class="ty">List</span>.of(searchTool, codeTool))
    .build();
<span class="cm">// 自主推理 + 工具调用 + 人机协作</span>
agent.call(messages).block();</pre></div>
      <div class="hs-code-panel" id="zh-harness"><pre><span class="kw">import</span> io.agentscope.harness.*;
<span class="kw">var</span> agent = <span class="ty">HarnessAgent</span>.builder()
    .name(<span class="str">"coder"</span>)
    .model(<span class="kw">new</span> <span class="ty">QwenConfig</span>(<span class="str">"qwen-plus"</span>))
    .filesystem(<span class="kw">new</span> <span class="ty">LocalFilesystemSpec</span>(<span class="str">"./workspace"</span>))
    .session(<span class="kw">new</span> <span class="ty">RedisSession</span>(jedisPool))
    .build();
<span class="cm">// 有状态 · 可恢复 · 记忆持久化</span>
agent.call(messages, <span class="ty">RuntimeContext</span>.builder()
    .sessionId(<span class="str">"user-123"</span>).build()).block();</pre></div>
      <div class="hs-install">
        <code>io.agentscope:agentscope-harness</code>
        <button class="hs-copy-btn" data-copy="io.agentscope:agentscope-harness">复制 Maven</button>
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
    <span class="hs-stat__val">MCP · A2A</span>
    <span class="hs-stat__label">开放协议支持</span>
  </div>
  <div class="hs-stat">
    <span class="hs-stat__val">Apache 2.0</span>
    <span class="hs-stat__label">开源协议</span>
  </div>
</div>

<!-- Feature 1: Harness -->
<div class="hs-section">
  <div class="hs-split">
    <div class="hs-split__text">
      <div class="hs-chip">Harness 工程化</div>
      <h2>企业级工程范式，开箱即用。</h2>
      <p>Harness 模块通过 Hook 机制在推理循环之外注入工作区管理、记忆持久化、对话压缩等工程能力，无需修改核心推理逻辑。</p>
      <ul>
        <li>结构化工作区作为 Agent 唯一状态来源</li>
        <li>跨会话长期记忆沉淀与自动整理</li>
        <li>上下文超长自动压缩，防止 Token 溢出</li>
        <li>本机磁盘 → Docker 沙箱 → 对象存储，一行切换</li>
      </ul>
      <a href="docs/harness/overview.html" class="hs-btn hs-btn--secondary" style="margin-top:4px">了解 Harness →</a>
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
<span class="t-dim">├──</span> <span class="t-ok">AGENTS.md</span>          <span class="t-dim"># 人格 + 系统指令</span>
<span class="t-dim">├──</span> <span class="t-ok">MEMORY.md</span>         <span class="t-dim"># 长期记忆（自动维护）</span>
<span class="t-dim">├──</span> <span class="t-info">knowledge/</span>        <span class="t-dim"># 领域知识与 RAG 索引</span>
<span class="t-dim">├──</span> <span class="t-info">skills/</span>           <span class="t-dim"># 可复用技能文件</span>
<span class="t-dim">├──</span> <span class="t-info">subagents/</span>        <span class="t-dim"># 子 Agent 配置</span>
<span class="t-dim">└──</span> <span class="t-warn">agents/coder/session-user-123/</span>
<span class="t-dim">    ├──</span> chat-history.jsonl
<span class="t-dim">    └──</span> scratchpad/
<span class="t-prompt">$</span> <span class="t-dim">每次 call → 自动加载 → 推理 → 回写记忆</span></pre></div>
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
          <span class="hs-visual__bar-title">multi-agent runtime architecture</span>
        </div>
        <div class="hs-code-panel" style="display:block"><pre><span class="cm">+------------------------------------------------------------+</span>
<span class="cm">|                 Supervisor (HarnessAgent)                  |</span>
<span class="cm">|  - 规划任务 / 调度子 Agent / 聚合结果                      |</span>
<span class="cm">+---------------------------+--------------------------------+</span>
<span class="cm">                            |</span>
<span class="cm">          +-----------------+------------------+</span>
<span class="cm">          |                                    |</span>
<span class="cm">+---------v---------+                +---------v---------+</span>
<span class="cm">| researcher Agent  |                |   coder Agent     |</span>
<span class="cm">| - web_search      |                | - filesystem/tool |</span>
<span class="cm">| - read_file       |                | - code execution  |</span>
<span class="cm">+---------+---------+                +---------+---------+</span>
<span class="cm">          |                                    |</span>
<span class="cm">          +-----------------+------------------+</span>
<span class="cm">                            |</span>
<span class="cm">+---------------------------v--------------------------------+</span>
<span class="cm">|           Shared Runtime Infrastructure                    |</span>
<span class="cm">|  Session (Redis) · Workspace · Memory · MCP · A2A         |</span>
<span class="cm">+------------------------------------------------------------+</span></pre></div>
      </div>
    </div>
    <div class="hs-split__text">
      <div class="hs-chip">多智能体</div>
      <h2>像微服务一样编排 Agent。</h2>
      <p>在 Markdown 文件中声明子 Agent 规格，主 Agent 在运行时按需 spawn 子 Agent，支持同步阻塞与异步非阻塞两种委派模式。</p>
      <ul>
        <li>声明式子 Agent 定义，无需修改代码</li>
        <li>同步阻塞 / 异步非阻塞两种委派模式</li>
        <li>A2A 协议支持跨进程、跨机器调用</li>
        <li>子 Agent 可独立继承或覆盖 Harness 配置</li>
      </ul>
      <a href="docs/harness/subagent.html" class="hs-btn hs-btn--secondary" style="margin-top:4px">了解多智能体 →</a>
    </div>
  </div>
</div>

<!-- Feature Cards -->
<div class="hs-section">
  <div class="hs-section-hd">
    <h2>全栈能力，专为生产打磨</h2>
    <p>从推理核心到企业部署，AgentScope Java 覆盖智能体开发全链路。</p>
  </div>
  <div class="hs-cards">
    <a class="hs-card" href="docs/quickstart/agent.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456z"/></svg>
      <h3>ReAct 推理</h3>
      <p>自主规划、工具调用与结果整合。内置安全中断、优雅取消与 Hook 人机协作，自主性与可控性兼得。</p>
      <span class="hs-card__link">快速开始 →</span>
    </a>
    <a class="hs-card" href="docs/harness/memory.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.75 12h16.5M3.75 19.5h16.5M3.75 4.5h16.5M7.5 7.5a3 3 0 110-6 3 3 0 010 6zm9 0a3 3 0 110-6 3 3 0 010 6zm-9 13.5a3 3 0 110-6 3 3 0 010 6zm9 0a3 3 0 110-6 3 3 0 010 6z"/></svg>
      <h3>记忆 &amp; RAG</h3>
      <p>跨会话持久化记忆，支持语义检索。后台自动整理长期记忆，防止上下文无限膨胀，多租户隔离开箱即用。</p>
      <span class="hs-card__link">了解记忆 →</span>
    </a>
    <a class="hs-card" href="docs/task/mcp.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244"/></svg>
      <h3>MCP 协议</h3>
      <p>接入任意 MCP 兼容服务器——文件系统、数据库、浏览器、代码解释器，扩展 Agent 工具能力无需自定义集成代码。</p>
      <span class="hs-card__link">查看 MCP →</span>
    </a>
    <a class="hs-card" href="docs/task/a2a.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M7.5 21L3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5"/></svg>
      <h3>A2A 协议</h3>
      <p>将 Agent 注册到 Nacos 等服务发现中心，其他 Agent 可像调用微服务一样动态发现和委派任务。</p>
      <span class="hs-card__link">查看 A2A →</span>
    </a>
    <a class="hs-card" href="docs/harness/sandbox/index.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z"/></svg>
      <h3>沙箱隔离</h3>
      <p>工具执行在隔离环境（本地 Unix / Docker / E2B）内完成。多轮对话间沙箱状态完整保留，支持跨会话快照恢复。</p>
      <span class="hs-card__link">了解沙箱 →</span>
    </a>
    <a class="hs-card" href="docs/task/observability.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"/></svg>
      <h3>可观测性</h3>
      <p>可插拔 Tracer SPI，原生支持 OpenTelemetry 分布式追踪。AgentScope Studio 提供可视化调试与实时监控面板。</p>
      <span class="hs-card__link">查看 Studio →</span>
    </a>
  </div>
</div>

<!-- CTA -->
<div class="hs-cta">
  <h2>准备好构建了吗？</h2>
  <p>几分钟内运行你的第一个 Agent。从基础 ReActAgent 起步，按需叠加 Harness 工程化、多智能体与企业级能力——同一套 API，线性增长复杂度。</p>
  <a href="docs/quickstart/installation.html" class="hs-btn hs-btn--primary">开始构建 →</a>
</div>

<!-- FAQ -->
<div class="hs-faq">
  <div class="hs-faq__hd">
    <h2>常见问题</h2>
    <p>还有疑问？欢迎在 <a href="https://github.com/agentscope-ai/agentscope-java/discussions" style="color:var(--hs-accent)">GitHub Discussions</a> 提问。</p>
  </div>
  <details class="hs-faq-item">
    <summary>AgentScope Java 需要什么 Java 版本？</summary>
    <p>需要 <code>JDK 17</code> 或更高版本。框架使用了 Records、Sealed Classes 等现代 Java 特性，并基于 Project Reactor 构建响应式非阻塞执行模型。如需极低冷启动延迟，可通过 Quarkus 进行 GraalVM 原生镜像编译。</p>
  </details>
  <details class="hs-faq-item">
    <summary>支持哪些 LLM 提供商？</summary>
    <p>支持所有兼容 OpenAI Chat Completions API 的模型，包括阿里云通义千问、OpenAI GPT 系列、Anthropic Claude、本地 Ollama 等。框架提供标准 <code>ModelConfig</code> SPI，可通过插件扩展接入任意模型服务商。</p>
  </details>
  <details class="hs-faq-item">
    <summary>Harness 和普通 ReActAgent 有什么区别？</summary>
    <p><code>ReActAgent</code> 是推理—工具—回复的核心循环；<code>HarnessAgent</code> 在此之上通过 Hook 系统注入工作区加载、记忆持久化、对话压缩、沙箱生命周期等工程能力，二者使用同一套推理核心。你可以从 <code>ReActAgent</code> 起步，需要工程化能力时无缝迁移到 <code>HarnessAgent</code>，无需改动业务逻辑。</p>
  </details>
  <details class="hs-faq-item">
    <summary>可以和 Spring Boot / Quarkus 一起使用吗？</summary>
    <p>可以。AgentScope Java 的核心模块是框架无关的 Java 库，可以直接在 Spring Boot、Quarkus、Micronaut 或任意 JVM 应用中作为依赖引入，无任何侵入性。Quarkus 可进一步利用 GraalVM 原生镜像编译实现超低延迟冷启动，适合 Serverless 场景。</p>
  </details>
  <details class="hs-faq-item">
    <summary>如何在生产环境中水平扩展？</summary>
    <p>AgentScope Java 天然支持无状态水平扩展：将会话状态托管到 <code>RedisSession</code>，文件工作区迁移到对象存储（<code>OssFilesystemSpec</code>），任意副本均可恢复同一用户的完整上下文。结合 Kubernetes 与 HPA，可实现毫秒级弹性扩缩容。</p>
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
        btn.textContent = '✓ 已复制';
        setTimeout(function () { btn.textContent = orig; }, 1800);
      });
    });
  });
})();
</script>
```

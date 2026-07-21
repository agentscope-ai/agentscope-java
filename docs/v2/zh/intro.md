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
    <h1 class="hs-hero__headline">构建<span class="hs-hero__accent">分布式、企业级</span>智能体！</h1>
    <p class="hs-hero__desc">AgentScope Java 2.0 是面向企业级、分布式、生产环境的智能体框架，提供与模型能力相匹配的核心 Harness 抽象，可支持长期、稳定、安全可控的智能体任务执行。</p>
    <div class="hs-hero__actions">
      <a href="docs/quickstart.html" class="hs-btn hs-btn--primary">快速开始 →</a>
      <a href="https://github.com/agentscope-ai/agentscope-java" class="hs-btn hs-btn--secondary">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 6.477 2 12c0 4.42 2.865 8.167 6.839 9.49.5.09.682-.217.682-.48 0-.237-.008-.866-.013-1.7-2.782.603-3.369-1.342-3.369-1.342-.454-1.155-1.11-1.462-1.11-1.462-.908-.62.069-.608.069-.608 1.003.07 1.531 1.03 1.531 1.03.892 1.529 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.11-4.555-4.943 0-1.091.39-1.984 1.029-2.683-.103-.253-.446-1.27.098-2.647 0 0 .84-.268 2.75 1.026A9.578 9.578 0 0112 6.836c.85.004 1.705.114 2.504.336 1.909-1.294 2.747-1.026 2.747-1.026.546 1.377.203 2.394.1 2.647.64.699 1.028 1.592 1.028 2.683 0 3.842-2.339 4.687-4.566 4.935.359.309.678.919.678 1.852 0 1.336-.012 2.415-.012 2.741 0 .267.18.577.688.48C19.138 20.163 22 16.418 22 12c0-5.523-4.477-10-10-10z"/></svg>
        GitHub
      </a>
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
        </div>
      </div>
      <div class="hs-code-panel" id="zh-harness"><pre><span class="kw">var</span> agent = <span class="ty">HarnessAgent</span>.builder()
    .name(<span class="str">"coder"</span>)
    .model(<span class="str">"dashscope:qwen-max"</span>)                                <span class="cm">// ModelRegistry 解析，读取 DASHSCOPE_API_KEY</span>
    .workspace(<span class="ty">Paths</span>.get(<span class="str">".agentscope/workspace"</span>))   <span class="cm">// AGENTS.md · MEMORY.md · skills · subagents</span>
    .filesystem(<span class="kw">new</span> <span class="ty">DockerFilesystemSpec</span>()           <span class="cm">// 沙箱执行：本地 · Docker · 远端 KV 一行切换</span>
        .isolationScope(<span class="ty">IsolationScope</span>.USER))           <span class="cm">// 同一 user 跨 session 共享</span>
    .build();
agent.call(msg, <span class="ty">RuntimeContext</span>.builder()
    .sessionId(<span class="str">"demo"</span>).userId(<span class="str">"alice"</span>).build()).block();</pre></div>
      <div class="hs-install">
        <code>io.agentscope:agentscope-harness:${agentscope.version}</code>
        <button class="hs-copy-btn"
                data-copy="&lt;dependency&gt;&#10;    &lt;groupId&gt;io.agentscope&lt;/groupId&gt;&#10;    &lt;artifactId&gt;agentscope-harness&lt;/artifactId&gt;&#10;    &lt;version&gt;${agentscope.version}&lt;/version&gt;&#10;&lt;/dependency&gt;">复制 Maven XML</button>
      </div>
    </div>
  </div>
</div>

<!-- Stats strip: 典型用户案例 -->
<div class="hs-adoption">
  <div class="hs-adoption__eyebrow">
    <span class="hs-adoption__eyebrow-dot"></span>生产环境验证 · Battle-tested in Production
  </div>
  <div class="hs-adoption__stats">
    <div class="hs-stat">
      <span class="hs-stat__val">阿里巴巴集团</span>
      <span class="hs-stat__label">集团内使用最广泛的智能体框架（Java &amp; Python），十余条核心业务线生产环境深度使用</span>
    </div>
    <div class="hs-stat">
      <span class="hs-stat__val">开源社区</span>
      <span class="hs-stat__label">开源与阿里云公有云用户覆盖 10+ 行业的头部企业</span>
    </div>
  </div>
  <div class="hs-adoption__row">
    <span class="hs-adoption__row-label">阿里巴巴集团</span>
    <div class="hs-marquee">
      <div class="hs-marquee__track">
        <span class="hs-marquee__group">
          <span class="hs-tag">飞猪</span><span class="hs-tag">淘宝闪购</span><span class="hs-tag">虎鲸文娱</span><span class="hs-tag">AIDC</span><span class="hs-tag">阿里控股</span><span class="hs-tag">淘天交易</span><span class="hs-tag">淘天手淘</span><span class="hs-tag">1688</span><span class="hs-tag">千问 APP</span><span class="hs-tag">高德</span><span class="hs-tag">阿里云</span><span class="hs-tag">蚂蚁国际</span><span class="hs-tag">蚂蚁全球支付</span><span class="hs-tag hs-tag--more">…</span>
        </span>
        <span class="hs-marquee__group" aria-hidden="true">
          <span class="hs-tag">飞猪</span><span class="hs-tag">淘宝闪购</span><span class="hs-tag">虎鲸文娱</span><span class="hs-tag">AIDC</span><span class="hs-tag">阿里控股</span><span class="hs-tag">淘天交易</span><span class="hs-tag">淘天手淘</span><span class="hs-tag">1688</span><span class="hs-tag">千问 APP</span><span class="hs-tag">高德</span><span class="hs-tag">阿里云</span><span class="hs-tag">蚂蚁国际</span><span class="hs-tag">蚂蚁全球支付</span><span class="hs-tag hs-tag--more">…</span>
        </span>
      </div>
    </div>
  </div>
  <div class="hs-adoption__row">
    <span class="hs-adoption__row-label">开源 · 公有云</span>
    <div class="hs-marquee hs-marquee--reverse">
      <div class="hs-marquee__track">
        <span class="hs-marquee__group">
          <span class="hs-tag">金融</span><span class="hs-tag">交通物流</span><span class="hs-tag">消费零售</span><span class="hs-tag">制造</span><span class="hs-tag">能源</span><span class="hs-tag">医疗</span><span class="hs-tag">教育政媒</span><span class="hs-tag">互联网</span><span class="hs-tag">SaaS</span><span class="hs-tag">咨询</span><span class="hs-tag hs-tag--more">等行业头部企业</span>
        </span>
        <span class="hs-marquee__group" aria-hidden="true">
          <span class="hs-tag">金融</span><span class="hs-tag">交通物流</span><span class="hs-tag">消费零售</span><span class="hs-tag">制造</span><span class="hs-tag">能源</span><span class="hs-tag">医疗</span><span class="hs-tag">教育政媒</span><span class="hs-tag">互联网</span><span class="hs-tag">SaaS</span><span class="hs-tag">咨询</span><span class="hs-tag hs-tag--more">等行业头部企业</span>
        </span>
      </div>
    </div>
  </div>
</div>

<!-- Feature 1: Harness（工程化底座） -->
<div class="hs-section">
  <div class="hs-split">
    <div class="hs-split__text">
      <div class="hs-chip">Harness 工程化</div>
      <h2>长期稳定运行的工程底座。</h2>
      <p>裸的 ReActAgent 只解决"一次推理"，<code>HarnessAgent</code> 通过 Middleware 与 Toolkit 两个扩展通道，把工作区、记忆、沙箱、子 agent、技能与计划模式打包成一套面向长期稳定运行的工程基础设施——核心推理循环原样保留，只叠加不替换。</p>
      <ul>
        <li><strong>身份持续</strong>：工作区即 agent 的人格 + 长期记忆 + 领域知识，每轮自动注入</li>
        <li><strong>上下文可控</strong>：自动压缩、大工具结果落盘、ContextOverflow 兜底重试</li>
        <li><strong>状态可恢复</strong>：同 sessionId 跨进程恢复完整对话；沙箱状态可快照</li>
        <li><strong>能力可沉淀</strong>：四层 Skill 合成 + 自学习闸门；声明式子 agent 编排</li>
      </ul>
      <a href="docs/harness/architecture.html" class="hs-btn hs-btn--secondary" style="margin-top:4px">了解 Harness →</a>
    </div>
    <div class="hs-split__visual">
      <div class="hs-visual">
        <div class="hs-visual__bar">
          <div class="hs-visual__bar-dots">
            <div class="hs-window__dot hs-window__dot--r"></div>
            <div class="hs-window__dot hs-window__dot--y"></div>
            <div class="hs-window__dot hs-window__dot--g"></div>
          </div>
          <span class="hs-visual__bar-title">智能体运行核心 · 模块全景</span>
        </div>
        <img src="../../imgs/v2/as2-release-01.png" alt="AgentScope 2.0 智能体运行核心：Agent Service · Workspace · Middleware · 权限系统 · 上下文管理 · 模型接入 · 消息与事件" style="display:block;width:100%;height:auto;border:0"/>
      </div>
    </div>
  </div>
</div>

<!-- Feature 2: 事件驱动 + 权限边界 -->
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
          <span class="hs-visual__bar-title">统一消息结构 → 事件流 → UI 实时跟随</span>
        </div>
        <img src="../../imgs/v2/as2-release-03.jpg" alt="统一消息结构（文本/文件/工具结果/模型思考）通过事件流（文本增量/工具执行/用户确认）实时驱动 UI" style="display:block;width:100%;height:auto;border:0"/>
      </div>
    </div>
    <div class="hs-split__text">
      <div class="hs-chip">事件 · 权限</div>
      <h2>让执行过程可展示、可干预。</h2>
      <p>消息通过统一 <code>ContentBlock</code> 承载文本、文件、图片、思考与工具结果；一次 <code>call()</code> 不再只返回最终文本，而是流式产生模型调用、文本增量、工具调用、工具结果、用户确认等类型化事件。HITL 与权限审批是框架内生能力。</p>
      <ul>
        <li><strong>类型化事件</strong>：<code>streamEvents()</code> 逐步发出，前端无需手动 diff</li>
        <li><strong>多模态消息</strong>：<code>DataBlock</code> 同时兼容 base64 与 URL 两种数据源</li>
        <li><strong>权限三态决策</strong>：静态规则 + 工具类型 + 输入分析 → 允许 / 用户审批 / 拒绝</li>
        <li><strong>外部执行回环</strong>：工具可在外部环境完成后回写结果，任务继续推进</li>
      </ul>
      <a href="docs/building-blocks/message-and-event.html" class="hs-btn hs-btn--secondary" style="margin-top:4px">了解事件与权限 →</a>
    </div>
  </div>
</div>

<!-- Feature Cards -->
<div class="hs-section">
  <div class="hs-section-hd">
    <h2>组成可靠智能体系统的核心组件</h2>
    <p>从模型容错到沙箱执行，AgentScope Java 2.0 把"让 Agent 稳下来"所需的每一块工程拼图都给齐。</p>
  </div>
  <div class="hs-cards">
    <a class="hs-card" href="docs/building-blocks/model.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99"/></svg>
      <h3>模型容错</h3>
      <p>通过模型扩展模块提供统一的 Credential + ChatModel 抽象，覆盖 Qwen / OpenAI / Anthropic / Gemini / DeepSeek / Ollama；可配置最大重试与备用模型，主模型不可用时自动切换。</p>
      <span class="hs-card__link">了解模型 →</span>
    </a>
    <a class="hs-card" href="docs/harness/memory.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.75 12h16.5M3.75 19.5h16.5M3.75 4.5h16.5M7.5 7.5a3 3 0 110-6 3 3 0 010 6zm9 0a3 3 0 110-6 3 3 0 010 6zm-9 13.5a3 3 0 110-6 3 3 0 010 6zm9 0a3 3 0 110-6 3 3 0 010 6z"/></svg>
      <h3>上下文工程</h3>
      <p>结构化压缩保留目标 / 状态 / 关键发现 / 下一步；超大工具结果自动落盘、上下文只留占位符；文件读写强制"先读后改"，减少重复 IO。</p>
      <span class="hs-card__link">了解记忆 →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/middleware.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6.878V6a2.25 2.25 0 012.25-2.25h7.5A2.25 2.25 0 0118 6v.878m-12 0c.235-.083.487-.128.75-.128h10.5c.263 0 .515.045.75.128m-12 0A2.25 2.25 0 004.5 9v.878m13.5-3A2.25 2.25 0 0119.5 9v.878m0 0a2.246 2.246 0 00-.75-.128H5.25c-.263 0-.515.045-.75.128m15 0A2.25 2.25 0 0121 12v6a2.25 2.25 0 01-2.25 2.25H5.25A2.25 2.25 0 013 18v-6c0-.98.626-1.813 1.5-2.122"/></svg>
      <h3>Middleware</h3>
      <p><code>onAgent / onReasoning / onActing / onModelCall</code> 四个洋葱钩子 + <code>onSystemPrompt</code> 变换钩子。日志、追踪、权限、上下文注入、业务策略都能挂上去，框架核心保持稳定。</p>
      <span class="hs-card__link">了解 Middleware →</span>
    </a>
    <a class="hs-card" href="docs/harness/workspace.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 7.5l-9-5.25L3 7.5m18 0l-9 5.25m9-5.25v9l-9 5.25M3 7.5l9 5.25M3 7.5v9l9 5.25m0-9v9"/></svg>
      <h3>Workspace 抽象</h3>
      <p>把"agent 做什么"与"在哪里执行"分离。WorkspaceBase 统一身份、生命周期、资源发现与上下文卸载；本地磁盘、Docker、E2B 云沙箱一行切换；内置预热池适配 RL rollout。</p>
      <span class="hs-card__link">了解 Workspace →</span>
    </a>
    <a class="hs-card" href="docs/harness/subagent.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 18.72a9.094 9.094 0 003.741-.479 3 3 0 00-4.682-2.72m.94 3.198l.001.031c0 .225-.012.447-.037.666A11.944 11.944 0 0112 21c-2.17 0-4.207-.576-5.963-1.584A6.062 6.062 0 016 18.719m12 0a5.971 5.971 0 00-.941-3.197m0 0A5.995 5.995 0 0012 12.75a5.995 5.995 0 00-5.058 2.772m0 0a3 3 0 00-4.681 2.72 8.986 8.986 0 003.74.477m.94-3.197a5.971 5.971 0 00-.94 3.197M15 6.75a3 3 0 11-6 0 3 3 0 016 0zm6 3a2.25 2.25 0 11-4.5 0 2.25 2.25 0 014.5 0zm-13.5 0a2.25 2.25 0 11-4.5 0 2.25 2.25 0 014.5 0z"/></svg>
      <h3>多智能体</h3>
      <p>在 Markdown 里声明子 agent 规格，主 agent 在运行时按需 <code>agent_spawn</code> / <code>agent_send</code>，同步阻塞与后台委派两种模式。后台任务终态会通过 system-reminder 反向推送，不再要求轮询。</p>
      <span class="hs-card__link">了解多智能体 →</span>
    </a>
    <a class="hs-card" href="docs/building-blocks/tool.html">
      <svg class="hs-card__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M11.42 15.17L17.25 21A2.652 2.652 0 0021 17.25l-5.877-5.877M11.42 15.17l2.496-3.03c.317-.384.74-.626 1.208-.766M11.42 15.17l-4.655 5.653a2.548 2.548 0 11-3.586-3.586l6.837-5.63m5.108-.233c.55-.164 1.163-.188 1.743-.14a4.5 4.5 0 004.486-6.336l-3.276 3.277a3.004 3.004 0 01-2.25-2.25l3.276-3.276a4.5 4.5 0 00-6.336 4.486c.091 1.076-.071 2.264-.904 2.95l-.102.085m-1.745 1.437L5.909 7.5H4.5L2.25 3.75l1.5-1.5L7.5 4.5v1.409l4.26 4.26m-1.745 1.437l1.745-1.437m6.615 8.206L15.75 15.75M4.867 19.125h.008v.008h-.008v-.008z"/></svg>
      <h3>工具与 MCP</h3>
      <p>注解驱动的工具注册、按属性自动批处理串行或并发；统一接入任意 MCP 兼容服务器（文件系统、数据库、浏览器、代码解释器），<code>workspace/tools.json</code> 集中管理白名单。</p>
      <span class="hs-card__link">了解工具 →</span>
    </a>
  </div>
</div>

<!-- CTA -->
<div class="hs-cta">
  <h2>准备好开始构建了吗？</h2>
  <p>跟着 quickstart 几分钟跑通一个 ReActAgent；需要工程化能力时换成 <code>HarnessAgent</code>，业务代码无须改动——同一套推理核心，按需叠加能力。</p>
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
    <p>需要 <code>JDK 17</code> 及以上。框架用了 Records、Sealed Classes 等现代特性，并基于 Project Reactor 提供非阻塞响应式执行模型。若需极低冷启动延迟，可通过 Quarkus 进行 GraalVM 原生镜像编译。</p>
  </details>
  <details class="hs-faq-item">
    <summary>支持哪些 LLM 提供商？</summary>
    <p>通过模型扩展模块支持：OpenAI（含兼容端点 vLLM、DeepSeek、Kimi、Moonshot）、Anthropic Claude、阿里云通义千问（DashScope）、Google Gemini、xAI Grok、本地 Ollama。每个都是统一 builder 后面一份独立的 <code>ChatModel</code> 实现，可在模型层配置重试与备用模型实现容错切换。</p>
  </details>
  <details class="hs-faq-item">
    <summary>Harness 和裸的 ReActAgent 有什么区别？</summary>
    <p><code>ReActAgent</code> 是"推理 → 工具 → 回复"的核心循环；<code>HarnessAgent</code> 在此之上通过 Middleware 与 Toolkit 注入工作区、记忆、压缩、子 agent、沙箱、Plan Mode 与技能等工程能力，二者共享同一套推理核心。你可以从 <code>ReActAgent</code> 起步，需要长期稳定运行时无缝迁移到 <code>HarnessAgent</code>，业务逻辑不动。</p>
  </details>
  <details class="hs-faq-item">
    <summary>2.0 兼容 1.0 吗？</summary>
    <p>AgentScope Java 2.0 版本尽量保持了对 1.x 版本的兼容，确保大部分用户的平滑升级；但同时 2.0 也带来了 API 层面的不兼容变更（新增类型化事件、权限系统、Middleware 栈与 Workspace 抽象等），详情可参考 <a href="docs/change-log.html">V1 迁移指南</a>。</p>
  </details>
  <details class="hs-faq-item">
    <summary>能搭配 Spring Boot 或 Quarkus 使用吗？</summary>
    <p>可以。核心模块是与框架无关的 Java 库，可作为依赖加入任何 JVM 应用——Spring Boot、Quarkus、Micronaut 或纯 Java 都行。Quarkus 还能配合 GraalVM 编译原生镜像，做到 100 ms 内冷启动。</p>
  </details>
  <details class="hs-faq-item">
    <summary>如何在生产环境中水平扩展？</summary>
    <p>AgentScope Java 天然支持无状态水平扩展：Agent 状态由 <code>AgentStateStore</code> 自动持久化（默认本地 <code>JsonFileAgentStateStore</code>，多副本换成 <code>RedisAgentStateStore</code>），按 <code>(userId, sessionId)</code> 寻址，工作区可挂到远端 KV / 对象存储，沙箱模式下连可执行环境本身都能跨调用 resume。配合 Kubernetes 与 HPA，任意副本均可恢复同一用户的完整上下文。</p>
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

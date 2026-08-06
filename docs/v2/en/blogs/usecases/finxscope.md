---
hide-toc: true
---

# Building a Financial-Grade Agent Foundation with AgentScope: The FinXScope Practice

> Authors: Meng Chen (Bantang), Wen Jun (Siyue), Ling Lezhen (Lezhen), Xu Lei (Chongshu), Lin Yuan (Niren)

## 01 Product Positioning and Core Vision

### 1.1 What Is FinXScope

FinXScope is a **financial-grade, AI-native agent foundation built on AgentScope Java** by the Alibaba Cloud New Finance Technical Services team. As a core part of the overall Agent Harness system, FinXScope fully reuses AgentScope Java's mature capabilities in agent orchestration, tool integration, multi-model access, and extensible runtime, and on top of that performs deep customization and enhancement to meet the financial industry's compliance, security, and high-availability requirements. The complete Agent Harness system consists of the FinXScope runtime foundation, the AI Service Asset Platform, the full-link evaluation platform, and the operations management platform, with FinXScope taking on the most critical responsibility of agent execution and management.

![FinXScope's position in the Agent Harness system](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j0kkybNzlZ4kkdLlAs3LtUY6vicvYk6pDnUKicDfxf2kmL3xc3pk4GHYNTAROGEpWrdAqibbEMW8vlU5udgHibJcP9DWMzFrQnc7Wk/640?wx_fmt=png&from=appmsg)

To understand FinXScope's core positioning, four key dimensions need to be grasped:

![The four key dimensions of FinXScope's core positioning](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2NbU5bRwZayf7zvQfYQOwQb9FX3cqXDM14At9Q33B7LoRnAp8k2LkHFDFv0pBMYyDyOpL3qDV6R5DNzYspLv7wy1hIEBkoofk/640?wx_fmt=png&from=appmsg)

**In one sentence**: FinXScope is the core engine of a financial-grade, AI-native agent foundation. Expert service teams co-create deeply with financial institutions, building an AI-native application construction, execution, and operations system that meets financial-grade requirements around real enterprise scenarios. Agent applications built on it allow users to describe their goal in a single sentence, and the system autonomously plans the path and dynamically orchestrates hundreds of skills to complete the task — truly realizing the new interaction paradigm of "conversation as service".

### 1.2 Customer Progress and Market Validation

Since its launch, FinXScope has attracted strong attention from multiple financial institutions and achieved substantive collaboration progress. It has gone into production with 10 leading financial customers, is being delivered to more than 20 additional customers, and covers a financial customer base of more than 60, spanning multiple financial business formats including state-owned major banks, joint-stock banks, insurance companies, and securities firms.

The actual deployment scenarios cover core financial businesses such as wealth management, customer manager empowerment, intelligent underwriting, compliance review, and investment research analysis. Among the projects already in production, AI-native apps have reached user bases in the tens of millions, validating FinXScope's stability and business value in real production environments.

These practices validate one core judgment: what financial institutions need is not yet another chatbot framework, but a runtime foundation that can truly support enterprise-grade agents from proof of concept to production at scale.

## 02 Industry Background and Challenges

### 2.1 The Paradigm Leap from "Feature Navigation" to "Conversation as Service"

Financial AI is undergoing a fundamental paradigm shift. Since 2024, autonomous agent products represented by Manus, Claude Code, and Hermes Agent have ignited global market attention, and OpenClaw gained over 230,000 GitHub Stars within 3 months, becoming a phenomenon-level project. These landmark events prove that large models have evolved from "chat assistants" into "working partners" capable of autonomously planning and executing complex tasks.

![Landmark events in agent products](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j31Nbiagg96SjGyRY33Lv0pO0hYAbuuCEVcQiccvH4HhESAULoxjRaKhCVUFKDicWt8v6rNAoJVxLicjaFDic5xeJXm4gMk02NfOb74/640?wx_fmt=png&from=appmsg)

For the financial industry, the impact of this change is profound. The traditional path of intelligent service development is to "stack" AI capabilities onto existing systems: AI is positioned more like an auxiliary tool for traditional workflows and does not break the original service paths. Users still need to navigate manually between hierarchical menus, buttons, and forms — the only difference is an added "AI Q&A" entry point. This leads to an awkward situation: customers use so-called "intelligent services", but the usage patterns and experience are essentially no different from traditional ones.

But as large model capabilities cross the critical threshold, AI has gained the ability to participate in and even drive business processes. This means financial institutions' expectations of AI have fundamentally shifted: no longer simply automating fixed workflows, but hoping that AI can autonomously understand user intent, dynamically orchestrate enterprise-grade financial service assets, and solve customers' complex problems.

![The fundamental shift in financial institutions' expectations of AI](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2HL1oMgRlGCK7LY8xuE4IOHpxavn3FuOibq4DseicvvcSuNHbLTflbKKmRaIibXWxn13dpYMQiaAlU5CRuPiauDjrNKMibOY9wpXx28/640?wx_fmt=png&from=appmsg)

The essence of this leap is: shifting from "enterprise-feature-centric" to "user-problem-centric" — users no longer need to know what features the system has or which flow to follow; they simply describe their goal directly, and AI autonomously completes the entire process from understanding to execution. This is the new paradigm of "conversation as service".

### 2.2 Structural Challenges Financial Institutions Face When Building Agents

According to the analysis in Ernst & Young's "AI Banking White Paper", the scaled deployment of AI technology in banking already carries strategic urgency. PwC's 2026 financial AI survey also points out that banks and insurers care most about traceability and explainability in AI governance. However, as financial institutions move from POC validation toward scaled production, they face a series of deep structural challenges — precisely the ones FinXScope is designed to address:

![Structural challenges financial institutions face when building agents](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j38qaZ1xD0GDb7vPOm8WdyuiaQxeCmpqIMC54TLxyaQSqObiclrkLPNgS2kTBic4np1rjmibe0SUnx1plpnUrqHeUlWicMHukszb2wY/640?wx_fmt=png&from=appmsg)

The essence of the challenges above is a systemic problem: what financial institutions need is not the enhancement of any single point capability, but a complete agent runtime foundation.

An easily overlooked fact is: the ultimate business outcome of an agent does not depend entirely on the capability of the large model itself — it equally depends on the quality of the foundation (Agent Harness) that carries its execution. Whether intent is precisely understood, whether tools are correctly orchestrated, whether context is effectively managed, whether the execution process is reliable and controllable — these "beyond-the-model" engineering capabilities are what determine whether an agent can stably deliver value in real scenarios. The same large model, running on foundations of different quality, can produce wildly different business outcomes. This is the fundamental reason financial institutions need a professional-grade Agent Harness.

## 03 The AI-Native Six-Layer Architecture

### 3.1 Six-Layer Architecture Design

FinXScope is the core engine of the entire Agent Harness system, and also the first enterprise-grade agent runtime foundation targeting the financial industry. It provides a systematic, complete capability combination rather than scattered technical components: based on the AgentScope framework — proven at large scale at Alibaba — it adds ten fully self-developed core capabilities and financial-grade enhancements, forming an agent runtime platform with both a solid technical foundation and rapid adaptability to financial scenarios. Around FinXScope, the Agent Harness system further extends into the AI Service Asset Platform (FinXSkillHub), the full-link evaluation platform (FinXVantage), and the operations management platform, together covering the complete lifecycle of agents from development and execution to continuous optimization. FinXScope's six-layer architecture is not only the technical implementation skeleton of this system, but also a brand-new definition and specification of enterprise application architecture for the AI-native era.

![FinXScope's six-layer architecture](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2Oqs21qftQibzJXYkH4MTwxau2HRrTJYUZGGbor1ceehlhicr57K0UZoCfXBRQibkXWkUY4k3JjoWszKciah8nAXq9mia88wLRb478/640?wx_fmt=png&from=appmsg)

Why is it necessary to redefine enterprise architecture? Traditional enterprise architectures (SOA, microservices) were designed around the paradigm of "humans operating systems": humans initiate operations through interfaces, systems execute according to preset flows, and deterministic results are returned. But under the AI-native paradigm, "AI autonomously planning and executing tasks" becomes the core driving force. This requires all service capabilities, data assets, and functional components to be reorganized around "how to be better understood and invoked by AI". This is a shift in the underlying design paradigm, not a patch on top of the old architecture.

### 3.2 Detailed Architecture Design

![Detailed FinXScope architecture design](https://mmbiz.qpic.cn/sz_mmbiz_jpg/bvDbzNRia8j2JTueRDIcTYUyXKMN8zCxQ6rXcCuLstTQTp0ibYEARIGq21Ngjjjlo7kTstvzuZz7Aia6zJarIcmMHqVQOiarNpTD5FTwFm86Gww/640?wx_fmt=jpeg&from=appmsg)

### 3.3 Strategic Significance of the AI-Native Architecture

The significance of the six-layer architecture goes far beyond technical implementation. It answers a fundamental question financial institutions face in their intelligent transformation: when AI upgrades from "auxiliary tool" to "core driving force", how should an enterprise's technical architecture be reorganized?

![Strategic significance of the AI-native architecture](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3mticZER8oKeY6gOnV6U0RjtrqbmnRcvuK5JJUxiasWAABmOAPmVKP5Up3D3U18Hqs1Enyl2VfIsNvNiablJObnuK5o9TicrTUTqA/640?wx_fmt=png&from=appmsg)

## 04 Deep Dive into Core Capabilities

The ten core capabilities below are FinXScope's technical moat. They include both fully self-developed original capabilities and deep enhancements built on AgentScope. These capabilities originate from the rigid requirements of real financial customer scenarios and have been polished and validated in multiple production environments.

### 4.1 Intent Engine: Precise Understanding of Financial Semantics

**Scenario pain points**: In financial conversation scenarios, users' expressions are often colloquial, vague, and heavily context-dependent. "How has Moutai been doing lately?" needs to be understood as "the recent stock price trend of Kweichow Moutai (600519)"; "Buy 50,000 of that product we talked about yesterday" requires restoring the complete instruction from conversation history. Traditional keyword matching or shallow classification models show obvious inadequacy in such complex financial conversations.

![Semantic understanding challenges in financial conversation scenarios](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1PBJxG0U8nZXIhPwwLQQagkMiarjKL8VzEAcGC7ILibGITGkF6xlWY4Niamfkn078nbDBWhD8cUo2r0c8Dw2MiaQmQlAibUPSuTicicE/640?wx_fmt=png&from=appmsg)

**Solution**: FinXScope's intent engine implements a complete four-stage processing pipeline. Each stage can be independently switched on/off and flexibly configured, forming a "modular, configurable financial intent engine":

**Stage 1**: NER entity recognition — based on financial domain dictionaries and a rule engine, it automatically recognizes financial entities such as stock codes, enterprise names, amounts, times, and percentages. Asynchronous processing and small-model inference ensure high performance (millisecond-level response), while a configurable dictionary mechanism supports enterprise-defined entity type extensions.

**Stage 2**: Query rewriting — transforms colloquial input into structured query requests. The rewriting pipeline chains four processors: context enhancement (extracting context from historical messages and user profiles to achieve coreference resolution), terminology standardization ("annual gain" → "annual return rate"), semantic correction (fixing ambiguities and errors), and LLM rewriting (invoking the large model when necessary to generate clearer structured expressions). Rewriting rules support online configuration from the admin console.

**Stage 3**: Intent classification — performs multi-level intent determination based on a dynamic intent tree. Supports automatic switching between LLM intelligent classification (high precision) and rule-based fallback (high performance). The intent tree supports online configuration and version management from the admin console, and supports runtime dynamic overrides.

**Stage 4**: Skill mapping — precisely routes to the corresponding skill based on the recognized intent. Supports multi-skill composition and priority configuration, with mapping relationships configurable online from the admin console.

Beyond intent-tree routing, the intent engine also supports an LLM autonomous planning mode based on Skill documentation. When a user's question cannot be precisely matched by the intent tree, the system automatically switches to LLM autonomous planning: the large model, based on the documentation (SKILL.md) of all available skills in the SkillPool, autonomously decides which skills to invoke and in what order. The two modes coexist within the same agent, with the engine routing automatically based on the characteristics of the question.

**New in 2.0**: supports callers overriding the preset intent tree via request parameters, enabling differentiated intent strategies in multi-tenant scenarios; supports intent switch tracking; the intent tree management API supports full CRUD and version rollback.

### 4.2 Three-Layer Memory System: The Evolution from Prompt Engineering to Context Engineering

**Scenario pain points**: Traditional AI systems either have no memory (every conversation starts from scratch), or only accumulate raw messages (unable to extract high-value information), and memory implementations are deeply coupled with business logic, lacking unified read/write interfaces and lifecycle management.

![Three-layer memory system](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2jUjCICaLeYspfgU4pOkxiaF02s39Pzb19TAWuMWJ4FV7XVHZs4iad6eiaUWSj7cQacq0hYiaZ2FDQmHLZiaiaUYNWoopFncD6Tk6nc/640?wx_fmt=png&from=appmsg)

**Solution**: Prompt engineering focuses on "how to write a good prompt", while context engineering focuses on "how to systematically manage all the context information received by the LLM": the quality and organization of context directly determine the agent's decision quality. The three layers of memory are not three independent "storage bins", but a complete "store → compress → recall → inject" execution cycle:

**Short-Term Memory (STM)**: window management and intelligent compression of conversation-level context. STM manages the conversation turns of the current session using a sliding window mechanism. As the conversation continues and context gradually inflates beyond the token threshold, STM triggers intelligent compression: LLM summarization is preferred (the large model extracts key information and decision points from the conversation and compresses them into a concise summary), with rule-based summarization as a fallback strategy (discarding older, low-importance messages based on time decay). The compressed summary replaces the original messages and continues to participate in the conversation, ensuring that the context injected into the model each time retains key semantics without hurting reasoning quality due to excessive length. Multi-dimensional capacity controls (turn limit, token limit, time limit) work in concert.

**Upgrade in 2.0**: STM supports Redis persistence, enabling cross-instance session recovery.

**Long-Term Memory (LTM)**: structured storage and semantic recall of cross-session knowledge. LTM implements persistent vector storage based on PostgreSQL + pgvector. After each conversation ends, the system automatically evaluates which information from the interaction has long-term value (through LLM-driven importance scoring, filtering redundant and low-value content), and vectorizes the valuable information into LTM. When a new conversation begins, the system vectorizes the current user input and retrieves the most relevant historical fragments via semantic similarity (e.g., "the fund product the user consulted about last week", "the investment preferences the user expressed"). Keyword search (with time-decay scoring) is also supported as a complementary channel, automatically degrading when vector search fails. Recalled memory fragments are injected into the current conversation's context, allowing the agent to "remember" key information across sessions.

**Upgrade in 2.0**: LTM has fully migrated to PostgreSQL and supports clustered deployment.

**UserProfile**: a structured financial attribute layer built on top of LTM. UserProfile is not a third independent type of "memory", but a structured user cognition automatically extracted and updated from long-term interactions. It distills scattered interaction information in LTM into standardized financial attributes: static attributes (age, occupation, asset scale), dynamic attributes (risk-level changes, investment preference evolution), behavioral preferences (interaction style, decision habits), and a tag system (high-net-worth, conservative, tech-sector-focused, etc.). It is automatically updated with every interaction, and the overall profile is injected into the system prompt to guide conversation strategy. Runtime injection of UserProfile gives different users completely different service strategies — for the same question "recommend a fund", conservative and aggressive users receive entirely different product combinations and wording.

**Asynchronous and imperceptible**: read/write operations of the three memory layers are fully asynchronous and do not affect the response speed of the main flow. What users perceive is instant response, while the system quietly completes memory evaluation, compression, storage, and profile updates in the background.

### 4.3 Dual-Mode Intelligent Execution Architecture: Unifying Flexibility and Controllability

Scenario pain points: the complexity span of financial business scenarios is enormous — from simple balance queries to complex investment portfolio analysis. A single execution mode cannot cover all scenarios: pure autonomous planning mode is inefficient and unpredictable in simple scenarios, and pure workflow mode is too rigid for open-ended questions.

![Dual-mode intelligent execution architecture](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3KDYmZys4TiaJn3Vq3GkFzUcHKfG6q1vQzUz7zicly0xycxiazE458jWUpepIFS91Xet4EBbVfuwy21n9qpQq7nKLISeEaLomCDQ/640?wx_fmt=png&from=appmsg)

**Solution**: first support two execution modes, then fuse the two through a shared execution foundation.

**OneAgent mode**: autonomous intelligent execution. Based on the ReAct (Reasoning-Acting) loop, it lets a single agent autonomously plan and execute tasks in a closed loop. At each step, AI runs through a "think → act → observe" cycle, fully unleashing the creativity and reasoning ability of the large model — suitable for open-ended, exploratory tasks.

**Multi-Agent mode**: collaborative orchestrated execution. A unified workflow execution engine newly built in 2.0, supporting eight orchestration strategies: parallel execution (Parallel), sequential execution (Sequential), loop execution (Loop), message center (MsgHub), debate mode (Debate), graph orchestration (Graph, DAG-based), dynamic routing (Routing), and supervisor mode (Supervisor). Each strategy implements a standardized interface and supports streaming output.

**Core innovation**: the shared execution foundation enables deep fusion of the two modes. Implementing the two execution modes themselves is not the hardest part. The real technical highlight is: OneAgent and Multi-Agent share the same unified execution engine (AgentProcessEngine) as their foundation. This means the two modes stay consistent on cross-cutting concerns such as permission verification, audit trails, and execution management — switching execution modes never sacrifices financial-grade guarantees.

Precisely because of the shared execution foundation, FinXScope can go further with another innovation — mounting workflows onto OneAgent. Through the MountableResource mechanism, Multi-Agent workflows are encapsulated as Tools for OneAgent to invoke on demand during autonomous reasoning. AI autonomously decides when to launch a deterministic workflow (e.g., a compliance review subprocess), and after the workflow completes, the result returns to OneAgent to continue reasoning. This fusion of "autonomous intelligence + deterministic flow" is possible precisely because both modes run on the same execution engine — context can be passed seamlessly, events can propagate across modes, and auditing can be recorded uniformly. It supports workflow suspension/resumption and subprocess event passthrough (avoiding information decay and added latency caused by the main Agent relaying information).

### 4.4 Unified Execution Engine: The "Operating System Kernel" of Agents

**Scenario pain points**: each agent implementing its own execution logic leads to code duplication, inconsistent behavior, and incomplete auditing — an unacceptable compliance risk in financial-grade scenarios. Different Agents have uneven context assembly approaches, inconsistent memory read/write timing, and ad-hoc exception handling chains, making full-link logs impossible to trace through, and tool invocations lacking unified interception. This fails to meet the audit requirement of "every decision step being traceable", and greatly increases maintenance and evolution costs under multiple orchestration modes.

**Solution**: AgentProcessEngine serves as the unified execution entry point. The entire engine contains a pre-common layer, a strategy routing layer, and a post-common layer, forming a closed-loop architecture of "unified entry → unified scheduling → unified wrap-up":

**Pre-common layer**:

- Permission verification: three-level verification of User-Agent-Skill.
- Input preprocessing: unified completion of file IDs and run identifiers, ensuring every subsequent layer can obtain complete, traceable input.
- Context loading: assembles short-term memory (STM) from historical messages, recalls relevant long-term memory (LTM) from the MemoryManager, reads static attributes such as risk preference from the user profile, and injects them uniformly into the execution context.
- Audit start: opens full-link structured logs, records the intent decision chain and latency metrics, providing a unified context anchor for subsequent interception points.

**Strategy routing layer**:

- Flexible strategy selection: automatically selects the execution strategy (OneAgent ReAct / the eight Multi-Agent orchestrations) based on patternType.
- MountableResource lifecycle: "mountable resources" such as memory managers, Toolkit, Skill packages, and precompiled graphs are attached on demand when the strategy starts and released when it ends, all managed uniformly by the execution context.

**Post-common layer**:

- Result formatting: outputs of all strategies uniformly converge into the standard AG-UI event stream — one protocol for the frontend.
- Memory writing: the user's original text + the assistant's full reply + metadata (session, rewritten text, intent type, matched Skill, tool invocation details) are asynchronously written back to STM / LTM / UserProfile, with the user profile continuously refined with each interaction.
- Audit close: closes unfinished streaming message segments, emits terminal-state events, prints structured logs (instance, mode, latency, text length), completing the closed loop with the pre-layer's traceId.
- Metrics collection: Prometheus metrics endpoints are connected, collecting metrics such as total engine latency, per-phase latency, and LLM call counts.

Fully supports three execution modes: synchronous, streaming, and AG-UI. The complete interceptor chain (12 interception points) supports injection of custom logic. Throughout execution, the decision chain, tool invocations, and Skill logs are automatically recorded, meeting financial audit and compliance requirements.

**New in 2.0**: runtime model overrides, Skill-level timeout configuration and retry strategies, and transparent cross-instance migration of execution context.

- Runtime model overrides: system prompts, model types, skills, etc. support hot updates; the same Agent configuration can dynamically transform per user/scenario.
- Skill-level timeout and retry: independent timeout thresholds and retry strategies configured at Skill granularity, preventing long-tail tools from dragging down the entire conversation chain.
- Transparent cross-instance migration of execution context: in multi-replica deployments, requests routed to any instance can restore state, providing a foundation for canary releases and failover.

### 4.5 AG-UI Full-Link Streaming Interaction: A Consistent Real-Time Service Experience Across Channels

**Scenario pain points**: the "ask one question, wait half a minute" interaction mode of traditional AI applications leaves users unable to perceive what AI is doing. Different channels each customizing their interaction protocols leads to inconsistent experiences and high management costs. Meanwhile, some financial data is structured, and pure text output cannot effectively convey trends, comparisons, proportions, and other information.

**Solution**: FinXScope fully adapts to the AG-UI standard protocol. Based on the Spring WebFlux reactive streaming architecture, it achieves full-link asynchronous streaming via SSE (text/event-stream). The AG-UI protocol defines 15 fine-grained event types, covering the complete interaction chain of the reasoning process, tool invocations, intermediate results, and final output. AG-UI's standardized interaction brings four core values:

**Cross-channel reuse**: when a new channel is connected, the frontend only needs to consume the AG-UI event stream according to the unified standard, without customizing an interaction protocol for each channel. The same event stream can be directly reused across channel frontends, significantly reducing management and development costs and ensuring a consistent intelligent service experience across terminals.

**Frontend-backend decoupling**: the frontend only consumes the standard event stream; when the backend switches models, adds/removes tools, or adjusts strategies, the frontend requires no changes. This allows the backend to iterate independently and quickly, while the frontend only needs to maintain rendering capabilities for the standard event types.

**Unified observability**: all Agents are monitored, alerted, and performance-analyzed based on the same set of event types. The event stream naturally provides full-link execution tracing capability.

**Extensible tool ecosystem**: a new tool only needs to emit standard events according to the protocol to be automatically recognized and consumed by the frontend, requiring no cross-team coordinated changes.

At the rendering layer, multiple standard rendering tools are built in (line charts, bar charts, pie charts, metric cards, data tables, volume charts, selectable lists, confirmation action cards, etc.), supporting placeholder-style incremental rendering and personalized component extensions.

**New in 2.0**: workflow_command_agent converts natural language into control-operation commands; WorkflowToolRegistry lets the frontend dynamically declare controls and register them at runtime as tools invocable by the agent.

### 4.6 Three-Layer Skill Definition System: Progressive Complexity Adaptation from Configuration to Code

**Scenario pain points**: the complexity span of financial business skills is large — from simple exchange rate queries to complex credit approvals. A single definition approach cannot balance conciseness and expressiveness.

**Solution**: the three-layer skill definition system progresses by complexity:

**YAML configuration files (lightweight query-type)**: declare skill name, description, associated tools, and content in a configuration file. The framework automatically reads and batch-registers them at startup, with no code required at all. Suitable for skills with fixed content and simple logic, such as exchange rate queries and balance queries.

**SKILL.md + scripts (medium complexity, "documentation as contract")**: create an independent folder for each skill under the skills directory. The core is a SKILL.md document, optionally accompanied by Python/Shell scripts. OneAgent relies entirely on this Markdown document to decide when to invoke the skill and how to pass parameters, making behavior predictable and auditable. Suitable for medium-complexity scenarios such as customer insights, product recommendations, and risk assessment.

**Java @Bean registration (complex business logic)**: the standard Spring Bean approach, with full type checking and IDE support, also serving as the registration method for low-level tools (such as multi-step transaction wrappers and business tools with strong consistency guarantees). Suitable for strong-consistency business scenarios such as multi-step transaction wrapping, compliance review, and credit approval.

**New in 2.0**:

- SkillsHub remote loading: versions are uniformly governed by the management platform. At service startup, all published skills are pulled in full; when the management platform publishes changes during runtime, the entire process has zero downtime, supporting version management and canary releases.
- Automatic skill permission filtering: supports three modes — NONE, FILTER, REJECT — to govern users' permission to use skills.

### 4.7 Tool and Knowledge Integration: Standardized Connection to Enterprise Legacy Assets

**Scenario pain points**: after years of informatization, financial institutions have accumulated a large number of business systems and knowledge assets, but their interface standards vary and they are scattered across different platforms, making per-system adapter code costly to write. Meanwhile, large amounts of unstructured knowledge are hard for agents to effectively utilize.

**Solution**: through standardized protocols and configuration-based access mechanisms, tool integration, knowledge retrieval, and permission propagation are unified as platform-level foundational capabilities, minimizing adaptation effort:

**Tool access**: provides two standard configuration approaches — MCP (Model Context Protocol) and API Schema. Enterprises only need to declare the service address and authentication information to complete tool registration. McpClientRegistry uniformly manages the configuration, health checks, and lifecycle of all MCP connections to ensure tool availability; it also supports configuration-based access to any customer-defined Bean compliant with the MCP protocol, seamlessly connecting to customers' legacy system capabilities with no additional development.

**Knowledge access**: a unified Knowledge interface supports configuration-based routing across multiple knowledge sources — Bailian general RAG, Dianjin financial-domain knowledge bases, and customer-built knowledge bases can all be connected via configuration. Agents invoke knowledge retrieval through the unified Knowledge tool; the underlying layer automatically hides implementation differences between knowledge bases and sets unified quality standards for core capabilities such as tag retrieval, recall reranking, and query rewriting.

**Permission propagation**: seamlessly connects to enterprise security systems. It provides standardized data propagation channels for tool invocation and knowledge recall interfaces, supporting end-to-end transmission of context such as identity information and channel identifiers, so that every external call made by the agent automatically inherits the user's permission boundary, with no need to repeatedly rebuild authentication logic.

**New in 2.0**: McpClientRegistry supports dynamic management: adding, removing, and modifying tool connections can be done at runtime, taking effect within seconds with no service restart required; newly registered tools are automatically injected into the Agent's available list, allowing operations teams to independently take tools online and offline.

### 4.8 Access Gateway Engine: Unified Multi-Channel Entry and Security Control

**Scenario pain points**: multi-channel input formats vary and need unified handling, and security threats facing AI systems such as Prompt injection need unified protection at the entry layer.

- **Protocol fragmentation**: different clients (Web/mobile/third-party systems) submit requests in different formats, requiring the backend to adapt to each one.
- **Security threats**: Prompt injection, sensitive content input, and policy-violating operations — if not uniformly intercepted at the entry layer — will spread to the intent engine and execution engine, causing uncontrollable risk.
- **Lack of multimodal preprocessing**: if unstructured content such as images/videos/audio/documents is passed directly to the LLM, it cannot be effectively understood; content extraction must be completed at the gateway layer to enhance the Prompt.
- **Inconsistent streaming output**: different processing branches (pure text/multimodal/TodoList) each have their own SSE event formats and lifecycle management, resulting in high frontend integration costs.
- **Loose session state management**: message persistence, session title generation, and context restoration are scattered across modules, lacking a unified orchestration point.

**Solution**: implements six core responsibilities: protocol adaptation, multimodal processing, security control, request routing, streaming output orchestration, and session persistence. Integrates a complete interceptor chain and permission checks. Security policies support dynamic configuration from the admin console, with changes taking effect within seconds.

- **Protocol adaptation**: compatible with 4 input formats, uniformly parsed into a standard internal representation.
- **Multimodal processing**: image understanding, video understanding, audio ASR, document parsing — extracted in parallel to enhance the Prompt.
- **Security control**: policy matching + risk scoring + action execution (pass/warn/block), supporting whitelists and a 5-minute policy cache.
- **Request routing**: three-way dispatch: pure text → OneAgent / multimodal → extraction + OneAgent / TodoList → direct execution.
- **Streaming output orchestration**: unified AG-UI Protocol event format, filtering duplicate control events, with the gateway layer uniformly emitting lifecycle events.
- **Session persistence**: user messages/assistant replies are automatically saved, session titles are auto-generated, and multimodal content has fallback storage.

**New in 2.0**: DocumentParserService multi-format document parsing (PDF/DOCX/DOC/MD/TXT), supporting large-file chunking, format fidelity, and metadata extraction — allowing users to chat directly with the agent based on uploaded documents; plus a permission control system, TodoList intelligent routing, and observability instrumentation.

### 4.9 TodoList Task Management (Newly Built in 2.0): Visualized Progress Tracking for Long-Process Tasks

**Scenario pain points**: targeting long-process financial tasks such as cross-border remittances and loan approvals (taking 30 seconds to several minutes), it solves the problem of the Agent's execution process being "completely invisible" to users — not knowing how far it has progressed, not knowing whether the breakdown is correct, and being unable to continue after interruption.

- **Progress visibility**: real-time synchronization of execution status (e.g., "2/4 complete, executing step 3"), eliminating waiting anxiety and reducing unnecessary follow-up questions.
- **Verifiable planning**: makes the Agent's task decomposition logic transparent, allowing users to identify omissions or deviations before or during execution and intervene to correct course — turning passive waiting into active review.
- **Resumable interruption**: persistent storage of task state, supporting automatic resumption across sessions/days, guaranteeing the continuity of complex business processes.

**Solution**: based on a two-level step model + Hook auto-injection + Pipeline state synchronization, it builds an execution closed loop from planning to recovery.

**Two-level step management**: Level-1 has the LLM autonomously decompose the top-level step sequence (e.g., "query → verify → confirm → transfer"), managing the full lifecycle of 5 states through the 7 standard tools provided by TodoListToolkit; Level-2 is automatically populated by the execution engine at the Pipeline level, writing the names, tasks, and states of parallel-scheduled sub-Agents into the subSteps of the corresponding Level-1 step, visualizing the details of multi-agent collaboration. The framework has built-in consistency constraints: marking completion via finish_todo_step is mandatory, only one step is allowed to be IN_PROGRESS at a time to prevent concurrent chaos, and after a step completes, the engine automatically activates the next TODO step, achieving natural state transitions and significantly reducing Agent orchestration complexity.

**Hook automatic state injection (imperceptible memory)**: TodoListHintHook intercepts PreReasoningEvent and dynamically appends a system prompt before each round of LLM reasoning, containing fixed tool descriptions and real-time task state (such as progress statistics and current/next step details), enabling the Agent to achieve "imperceptible" awareness of overall progress without an independent memory module, and automatically guiding it into the summary stage after task completion.

**Multi-Agent state synchronization for the Pipeline strategy**: based on the ParallelAgentStrategy engine listening to sub-Agent assignment and completion events, it automatically calls updateTodoSubStepStatus to synchronize state to TodoList storage, so the main Agent can instantly perceive subtask progress via the Hint Hook, achieving efficient Multi-Agent orchestration state synchronization at zero communication cost.

### 4.10 Full-Link Configuration Hot Update and Operations Management Platform (Newly Built in 2.0): Continuous Optimization Taking Effect in Seconds at Runtime

Scenario pain points: configuration changes require going through a complete release process, resulting in low efficiency; the lack of a unified management interface makes it difficult for operations staff to adjust agent behavior.

![Configuration hot update and operations management platform](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2NSNJ2VVtueLXy1libKxbPYbjK9syRZghsBVWnA5qQ9ufWwghsQ2Pmu29R2ibJvcdOIlbp46JKkVnV3Q6XxwwuRElzuMWjfdXZg/640?wx_fmt=png&from=appmsg)

**Solution**:

- Configuration hot update: covers all core configurations of Agent/Skill/Tool/intent tree/model/gateway. Redis Pub/Sub synchronizes across multiple instances, taking effect within seconds. ConfigVersionRegistry version management supports precise rollback.
- Operations management platform: implements full lifecycle management of agents, skills, intents, tools, and gateways: visually editing intent trees, configuring mapping relationships, registering/enabling/disabling skills, managing MCP connections, and configuring security policies. Supports building mirror runtime environments for configuration dry-run verification, comparing and evaluating multiple versions before publishing the best one. In the future, it will connect with the full-link evaluation system to realize an automated operations closed loop of "configure → dry-run → evaluate → publish the best".

## 05 Why Build on AgentScope

### 5.1 AgentScope: A Development Framework Born for Agents

AgentScope is an AI-native application development framework open-sourced by Alibaba, designed specifically for building autonomously planning agents. Choosing it as the foundation framework is based on three key dimensions: dynamic skill governance (Tool Group on-demand activation, avoiding context overload), high-concurrency architecture (the AgentPool factory pattern is naturally stateless), and execution reliability (exponential backoff retries, and a complete Hook system implementing circuit breaking and rate limiting).

### 5.2 Comprehensive Comparison with Traditional Frameworks

![Comprehensive comparison between AgentScope and traditional frameworks](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2Xmp0PT9HKian68GGSoMNvUPblQG4cdJEBzsPjiaN4ZtPzaqIbIwU7VCHJZnAlFPib1sADSv4pInsCvQc0IJXyfrZ1SjAeMLgh2g/640?wx_fmt=png&from=appmsg)

### 5.3 Core Extensions on Top of AgentScope

AgentScope provides solid foundational framework capabilities, but there remains a significant capability gap between a general-purpose framework and a financial-grade product. FinXScope builds six major extensions on top of AgentScope:

![FinXScope's six major extensions on top of AgentScope](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1rEZgobbPyTIszNDMWRCU95IAhBlEia6pwLd7ZPXFjKadQLA82p0GO2MAqOxwZUgkDsRuKtuoPg5PbMib9LLFGsDicCVOmUXzTDc/640?wx_fmt=png&from=appmsg)

**Extensions new in 2.0**: multi-agent workflow engine (eight orchestration strategies), workflow mounting mechanism (MountableResource), configuration hot update engine (Redis Pub/Sub + ConfigVersionRegistry), three-level permission system, observability framework (BizLogger + Prometheus + ObservabilityHook), remote storage refactoring (fully stateless).

## 06 The Collaboration Between Pro-Code and Low-Code

### 6.1 Correctly Understanding the Positioning of Pro-Code and Low-Code

In the financial industry, "pro-code or low-code" is not an either/or question, but a question of "which tool to use at which stage and in which scenario". The two are essentially different roles in the same technical system, serving different stages of an agent's journey from "idea" to "production".

**Core advantages and applicable scenarios of low-code platforms**: visual orchestration lets business personnel participate intuitively in design; rapid prototyping lets ideas quickly become demos; what-you-see-is-what-you-get debugging makes problem identification intuitive. Low-code platforms are the best choice for the business validation stage, and are also suitable for scenarios with low change frequency, short process chains (within 5-10 steps), and relatively tolerant reliability requirements.

**Challenges faced by low-code**: when scenarios involve complex workflow orchestration, multi-turn interactions and cross-session state management, AI autonomous planning, and financial-grade production guarantees, the maintenance costs and construction complexity of low-code platforms skyrocket.

### 6.2 The Pro-Code Value of FinXScope

FinXScope chooses the pro-code route to solve scenarios that low-code struggles to cover, while making every effort to lower the usage barrier:

![The pro-code value of FinXScope](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0m7CdWayNibCPO6bea9InVOAVHJLDwQR557ic6d4Cicv6FB3NAFib1dLYlsws8420SSw79FtKFtpibw2RbQ8O3RufcLibdKxL8iaUiciac/640?wx_fmt=png&from=appmsg)

### 6.3 The Complete Closed Loop of "Incubate with Low-Code, Produce with Pro-Code"

![The complete closed loop of incubating with low-code and going to production with pro-code](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1ve3xXlc5w3HAMgHibcc0evRQ0elc8mK5UhpEP2PyLpKSibRqXib0hibyaHiaOP9ad0JTib8Ty2EeDJF4O2GHXohykqBKiaLknMich3uQ/640?wx_fmt=png&from=appmsg)

## 07 Financial-Grade Capability Guarantees

### 7.1 Architecture Design for High Availability

FinXScope has internalized high availability as a foundational architecture characteristic since its initial design. Ultimate high-availability guarantees depend on the customer's infrastructure capabilities, but FinXScope ensures that it itself never becomes the high-availability bottleneck.

![Architecture design for high availability](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3lZ5e3le3d4SGpzFBTO1Fft7Fca3V8bdpwzHz6CRrxPZwGWiakKeDkION9vC8xMLicQic54EDBJUCcfy8MxzMGiaLSsddn1oyclLk/640?wx_fmt=png&from=appmsg)

### 7.2 Security and Compliance System

**Three-level permission control**: User-Agent-Skill three-level permissions, FILTER/REJECT dual modes, cache optimization, and configurable fail-open/fail-close fault tolerance strategies.

**Input security**: Prompt injection protection, sensitive word filtering, content moderation; policies support dynamic configuration from the admin console.

**Full-link auditing**: complete trails of the decision process, tool invocations, and Skill logs. MDC context automatically injects traceId/spanId, making the full link traceable.

### 7.3 Observability System

FinXScope provides a full-link observability system covering access, intent, and agent execution. Core features include:

**Distributed tracing**: full-link tracing based on Micrometer Tracing, automatically generating a unique traceId for each request, spanning the entire lifecycle from gateway access to Agent execution. The traceId is automatically injected into all log output, supporting context propagation across asynchronous threads, making it easy to quickly locate request chains and troubleshoot issues in distributed environments.

**Business metric monitoring**: business instrumentation through the unified BizLogger API automatically generates standard Prometheus metrics. The metric system is divided into six layers according to system architecture (access layer, presentation layer, intent layer, execution layer, tool layer, knowledge layer), with each layer independently defining metric enumerations covering dimensions such as event counts, latency statistics, active request counts, and error classification. A separate management port exposes the `/actuator/prometheus` endpoint for Prometheus Server scraping.

**Agent lifecycle observability**: deeply integrated with the AgentScope Hook mechanism, using ObservabilityHook to track 5 lifecycle stages of Agent execution: invocation entry, LLM reasoning, tool execution, summary generation, and exception handling. Each stage automatically collects latency and token usage, supporting three-level switch control (global switch → event-type switch → Agent-level override), allowing observation data collection for specific Agents or specific stages to be turned on or off on demand in production.

**Structured logging**: BizLogger provides dual-mode log output: each business log is simultaneously output to the console in a human-readable format and written to a separate file in structured JSON format. Logs automatically carry context information such as traceId, sessionId, and userId, supporting machine parsing and integration with log platforms.

**SPI extension mechanism**: the framework provides three layers of SPI extension points, supporting user-customized observability behavior without modifying framework code.

- Log output extension: register a custom BizLoggerProvider to replace or supplement the default log processing logic.
- Hook registration extension: implement AgentHookProvider to inject custom lifecycle hooks into Agents.
- Data extraction extension: implement HookLogFormatter to customize data extraction and formatting logic for Hook events.

## 08 Implementation Practice Guide

### 8.1 Four-Step Integration Path

![Four-step integration path](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2z9A1HOykLlpEqwK7VQlB9wJQ2LTJnIzsVw8ibhibf6J8XDz23Taich1MmyINrO13KznKll7diaofcDBhulROGeZBLCFJkMaSZVicg/640?wx_fmt=png&from=appmsg)

### 8.2 Deployment Modes and Resource Estimates

**1. FinXScope supports two usage modes:**

- **Standalone application**: packaged as an executable Fat JAR / Docker image, running independently and providing APIs externally.
- **Dependency import**: imported by other applications via Maven as a Spring Boot Starter, with Agent capabilities automatically wired.

**2. FinXScope's runtime always attaches to a concrete scenario application:**

**As a standalone application**, FinXScope itself is the scenario application, directly containerized and deployed to provide services externally.

**As an imported dependency**, FinXScope is packaged and deployed together with the host scenario application, requiring no independent runtime instance; its resource consumption is included in the host application.

**In the absence of a concrete scenario application:**

- It is a reference implementation/source code in a code repository.
- It is a dependency package (JAR) in a Maven repository.

In other words, FinXScope has no independent "always-on" state detached from a scenario. Deployment planning should be made around actual scenario applications.

**3. Deployment Architecture Reference**

![Deployment architecture reference](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j36lNGguDx3MiajAFQye0Y4iajrlR2dXE4eZVtMS5ULo8C2fQf6VdoZk0dKMj3mDEFwvb2J24Huj99kEAKubgfXXYCehnVCR7j5g/640?wx_fmt=png&from=appmsg)

**4. Required Data Resources**

Project execution depends on the following data-type resources. All data-type resources prefer reusing the customer's existing infrastructure; standalone deployment is considered only when no corresponding product exists in the customer environment.

![Required data resources](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3bNlxYKI5AgfP0Ds2v9aLAP9ll502I1F4IICodnQVsj9mdejicrTibZMBIKibomibImLFt4H6ib9gANfspaykp1VwRtbDPicRYtPCMA/640?wx_fmt=png&from=appmsg)

**Core principle**: use whatever the customer has; do not rebuild duplicates. All data-type resources (PostgreSQL, Redis, vector store, OSS) prefer connecting to the customer's existing infrastructure; the project itself adapts via configuration files. Only when the customer truly lacks a certain resource is the corresponding component deployed separately for them.

**5. Resource Estimate Reference**

![Resource estimate reference](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j11oYh5kWSsljpFzjzNDO6XrfWpopC5LNNfvOvwNaA7kutFudJfFicicjbicGnkheKghQd4tPTVxqVIia20OtPmkRoRic9aNhVZjzPU/640?wx_fmt=png&from=appmsg)

Resources marked (reuse) in the table can consider connecting to the customer's existing instances, with no standalone deployment required.

### 8.3 Quick-Start Recommendations

![Quick-start recommendations](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1k6VBIePqJ4nWibrqg1w4vdwMPRicUckzQGzIOzRHdwEN6jJrEfxXbGDUyHqKKTvMy1pNA9IAZVd3TGkCSf4o59aIibGK4v6exe0/640?wx_fmt=png&from=appmsg)

## 09 FinXScope 2.0 Version Evolution

FinXScope 2.0 completes the leap from a "single-agent runtime framework" to an "enterprise-grade agent runtime and management platform":

- **Multi-agent collaboration system (newly built)**: a unified workflow execution engine supporting eight orchestration strategies; workflow mounting onto OneAgent (MountableResource mechanism); subprocess event passthrough (GraphNodeEmitter); DAG graph executor (topological sort, parallel within a layer, serial between layers).
- **Permission and security system (newly built)**: User-Agent-Skill three-level permissions, FILTER/REJECT dual modes, permission cache optimization, fail-open/fail-close fault tolerance. JWT authentication integration.
- **Observability system (newly built)**: BizLogger dual-mode output, ObservabilityHook automatic observation, BizLoggerProvider SPI extension.
- **High availability and statelessness (major upgrade)**: STM → Redis, LTM → PostgreSQL, configuration hot update engine (Redis Pub/Sub + ConfigVersionRegistry), fully stateless.
- **Intent layer enhancements**: runtime intent tree overrides, runtime model overrides, intent tree management API (CRUD + version rollback + intent switch tracking).
- **Access layer and presentation layer enhancements**: gateway engine refactoring, DocumentParserService multi-format document parsing, workflow_command_agent, WorkflowToolRegistry dynamic tool injection.
- **TodoList task management (newly built)**: two-level step management, 7 Tools, Hook auto-injection, Pipeline integration.
- **MCP configuration management enhancements**: McpClientRegistry dynamic management, CustomMcpToolsInjector auto-injection, configuration synchronization.
- **HITL human intervention system**: tool-level interception of sensitive operations with human confirmation; Agent memory snapshots and resumable breakpoints; distributed persistence of suspended state; automatic determination of user confirm/reject intent.
- **A2A multi-agent collaboration protocol**: standard Agent-to-Agent protocol server and consumer ends; automatic AgentCard generation and multi-mode service discovery; synchronous/streaming dual-channel cross-Agent invocation; bidirectional event translation between AG-UI and A2A; Nacos registry integration and dynamic refresh.
- **Sandbox secure execution system**: support for multiple mainstream sandbox backends; asynchronous sandbox creation and session-level instance reuse; file upload/download and workspace synchronization; command execution isolation and timeout control; automatic deployment and keep-alive management of skill resources.

## 10 Typical Application Scenarios

![Typical application scenarios](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1Mhicibw5CgqWBiaSrodVTcVEOxibvl5SdoH5WbSv9oCHuib8Yq1Su2Nm38giawbKiaYGW0a6CCkIuhz5oxVicKTwodE3Yx4DK7xp9GxA/640?wx_fmt=png&from=appmsg)

## 11 Summary and Outlook

FinXScope represents a brand-new paradigm of financial-grade agent foundations. It does not attempt to become an "all-purpose AI application", but focuses on doing one thing well: providing financial institutions with a reliable, high-performance, easily extensible agent runtime and management foundation, so that any financial application can gain AI-native capabilities at extremely low cost.

FinXScope's foundation is built entirely on top of AgentScope Java 2.0, deeply reusing its core capabilities for enterprise-grade production environments — including the ReAct reasoning-acting loop, the five-stage middleware chain, the unified message model and typed event stream, the tool and skill framework, layered memory and state persistence, the tri-state permission engine, and graceful shutdown and interruption mechanisms. On this basis, FinXScope performs systematic industry-specific enhancements for the financial industry's compliance auditing, data security, and high-availability requirements.

Looking back at FinXScope's core value: the six-layer standardized architecture provides a clear architecture blueprint and planning path for enterprise AI construction; the dual-mode execution architecture finds the optimal balance between flexibility and controllability; the configuration-driven design combines the high capability ceiling of pro-code with a low usage barrier; enterprise-grade guarantees of high availability, security compliance, and observability make financial-grade production deployment no longer a difficult problem.

From 1.0 to 2.0, FinXScope completed the leap from a "single-agent framework" to an "enterprise-grade agent runtime and management platform". The addition of core capabilities such as the multi-agent collaboration system, the permission and security system, the observability system, and the configuration management platform marks that it now possesses the complete capability to support scaled agent deployment in financial institutions, providing a solid foundation for the intelligent transformation of financial enterprises.

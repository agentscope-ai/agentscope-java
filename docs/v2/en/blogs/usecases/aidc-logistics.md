---
hide-toc: true
---

# From Configuration-Driven to Business-Native: Enterprise-Grade Agent Development Practices with AgentScope

## 01 Background

### 1.1 Starting Point

#### 1.1.1 Business Thinking

> Note: the author is from the overseas logistics business-finance team, so the payment process is a core capability for us.

A recent requirements planning discussion triggered deep reflection on our existing product. Our product colleague raised several key questions about the "Payment Approval Agent" we had previously launched, pointing directly at the limitations of the current system:

**First, does the Agent possess real "memory" and "wisdom"?**

There is a large amount of repetitive rejection reasons in the current approval scenarios. For example, after rejecting payment A because the supplier has unresolved negative bills, when payment A+ is submitted a few days later, can the Agent remember the previous rejection reason and automatically check whether that issue has been resolved? Furthermore, can the Agent analyze approval trajectories, discover efficient patterns like user A's "one-shot clear feedback", and recommend such best practices to users like user B who need multiple rounds to get an approval through, thereby improving overall efficiency?

**Second, can interactions be more personalized?**

Can the Agent automatically memorize a person's operating habits and, during conversations, provide customized tips based on what the user usually cares about?

**Third, can operations staff achieve self-service rapid customization?**

We hope that in the future, with the participation of non-technical operations colleagues, anyone can quickly customize their own dedicated Agent. For example, in the "billed cost tracing analysis" scenario, helping business personnel understand "why this fee is calculated this way". Ideally, it should have the capabilities of rapid configuration, rapid testing, and rapid go-live.

**Fourth, how do we guarantee finance-grade operational safety?**

Financial safety is the top priority — any write operation requires rigorous approval and confirmation. The current practice of relying on Prompt hints to make the model perform secondary confirmation cannot achieve 100% interception. We need a more precise mechanism that, within self-planning and self-executing workflows, strictly controls which write operations must go through user confirmation.

**Finally, is precise canary release and experimentation supported?**

The system needs to support canary releases targeting specific user groups, and even A/B tests, to validate the effectiveness of different strategies.

These questions made us suddenly realize: what we had built before was just a simple Chatbox, not a truly intelligent Agent. This insight drove the team into deep reflection. Over the following month, we studied cases from other teams, held multiple rounds of analysis and discussion, and finally distilled the following technical thinking directions.

#### 1.1.2 Technical Thinking

##### 1.1.2.1 Thoughts

Let's first ask: what does your high-code actually look like?

We observed several Agent applications (Java) built on frameworks such as Google ADK and AgentScope. They exhibited a strikingly similar structure:

```text
my-agent-app/
├── FinanceAgent.java ← new ReActAgent(), hardcoded prompt
├── HRAgent.java ← new ReActAgent(), hardcoded another prompt
├── SalesAgent.java ← new ReActAgent(), yet another prompt
├── FinanceTool.java ← @Tool annotation, registered directly
├── HRTool.java ← @Tool annotation, registered directly
├── SessionManager.java ← self-built session management
├── ContextCompressor.java ← self-built context compression
├── SSEController.java ← self-built SSE streaming output
└── ...
```

Every team is doing the same thing:

1. **Hand-written Agent classes**: each application has 10+ or even dozens of hand-written Agents; each Agent corresponds to a Java class with hardcoded prompts, specified models, and registered tools in the constructor.
2. **Self-built infrastructure**: session persistence, context compression, SSE protocol adaptation, HITL framework — every team builds its own.
3. **Changes require releases**: changing a prompt, adding a SKILL, adding an MCP tool all require code changes, compilation, and deployment — a simple prompt tuning becomes a full development cycle.

This is an engineering problem, not an AI problem.

##### 1.1.2.2 Typical Bad Smells in Agent Engineering

In the actual implementation of Agent systems, due to the lack of unified engineering standards and platform support, the codebase is often filled with large amounts of duplicated, rigid, and hard-to-maintain implementation patterns. The following summarizes seven typical "bad smells" that not only increase development costs but also introduce severe stability and security risks.

**Bad smell 1: boilerplate proliferation — "cloning" instead of "creating"**

Phenomenon: each Agent is implemented as an independent Java class, and every class contains an identical double-checked locking (DCL) singleton pattern, `@PostConstruct` initialization logic, and hardcoded dependency injection.

```java
// BAD: every Agent class copies 200+ lines of boilerplate
@Component
public class FinanceMasterAgent {
    private static volatile LlmAgent instance;
    // ... standard DCL getInstance() implementation ...
    @PostConstruct
    public void initializeOnStartup() {
        AgentRegistry.register(getInstance());
    }
}
```

Analysis: in a project with 20+ Agents, `private static volatile instance` appears 30+ times, and the `getInstance()` logic is copied 20+ times. Adding a new Agent essentially becomes "copy & paste → modify class name/Prompt/tool list → fix `@DependsOn`". This "clone-style development" results in extremely high code redundancy, and any underlying framework upgrade requires modifying all Agent classes, causing maintenance costs to grow linearly.

**Bad smell 2: core configuration hardcoded — Prompt iteration blocked**

Phenomenon: the three core elements of an Agent — Prompt (instructions), model parameters (Temperature, etc.), and API Key — are all hardcoded in the code as Java constants or string literals.

```java
// BAD: 500+ lines of business decision tree hardcoded in a Java method
private static String buildInstruction() {
    return """
        You are a professional financial diagnostic assistant...
        ## Act 0: identify user input...
        ## Act 1: diagnose the root cause... (30+ error code branches)
        """;
}
// BAD: sensitive information and model configuration hardcoded
public class ModelFactory {
    public static final String API_KEY = "sk-xxxxxxxx"; // even committed to Git
}
```

Analysis:

- Development bottleneck: prompt tuning must go through the full "code change → Code Review → compile → deploy" cycle, measured in weeks, while business prompt iteration needs are measured in days.
- Security risk: sensitive information such as API Keys is directly exposed in the code repository.
- Lack of flexibility: Temperature parameters for different Agents are scattered everywhere, impossible to centrally control or dynamically adjust.

**Bad smell 3: rigid capability binding — tools and Skills cannot be dynamically orchestrated**

Phenomenon: the tool sets (Tools) and skills (Skills) an Agent possesses are fixed at build time, loaded via hardcoded lists or Classpath paths.

```java
// BAD: tool list welded into the code
.tools(List.of(
    AgentTool.create(OrderAgent.getInstance()),
    AgentTool.create(RefundAgent.getInstance())
))
// BAD: Skill paths hardcoded
Skill diagnosisPlan = factory.createSkillFromResource("skills/diagnosis/diagnosis_plan");
```

Analysis:

- Operations difficulty: if an MCP service fails and needs to be temporarily taken offline, or a new tool needs canary release, code must be modified and a new version released — runtime hot-swapping is impossible.
- High coupling: hundreds of scattered capability bindings make it hard to build a global view of dependencies, hindering reuse and composition of Agent capabilities.

**Bad smell 4: MCP management absent — connection leaks and environment confusion**

Phenomenon: MCP Server addresses are hardcoded in the code, and every tool invocation re-establishes a connection, lacking connection pool management.

```java
// BAD: pre-release environment addresses hardcoded, very likely to cause production incidents
private static final String ORDER_MCP_URL = "https://...pre-region...";
// BAD: every invocation performs TCP handshake + initialization + shutdown
public static String invokeMcpTool(...) {
    McpSyncClient client = McpClient.sync(transport).build();
    client.initialize();
    // ... execute ...
    client.closeGracefully();
}
```

Analysis:

- High-risk configuration: hardcoded environment addresses (e.g., `pre-`) that are not cleaned up during release will cause production to call pre-release services, resulting in P1-level incidents.
- Poor performance: frequent short connections in the ReAct loop cause extra 100-500ms latency, and there is no circuit breaking — under high concurrency it can easily drag down the MCP Server.

**Bad smell 5: broken identity propagation — the "god's-eye view" security risk**

Phenomenon: user identity is only marked at the Session layer and is not propagated to downstream tools or MCP invocations; all operations are executed under the application service account identity.

```java
// BAD: operator is empty when the tool is invoked, or application-level authentication is used
request.put("creatorNo", "");
ClientMcpRequestAuth auth = ClientMcpRequestAuth.of(serverUrl).generateAuthMaterial(); // application identity
```

Analysis:

- Missing audit trail: it is impossible to trace which user performed a sensitive operation (e.g., creating rules, approvals) through the Agent.
- Permission out of control: lack of user-level data isolation and permission control violates the zero-trust security principle. In finance and other sensitive scenarios, this is an unacceptable red line.

**Bad smell 6: HITL (human-in-the-loop) logic scattered and fragile**

Phenomenon: human confirmation logic is implemented by each tool separately in various ways: some return magic strings hoping the LLM recognizes them, some block threads polling Redis, some throw exceptions to interrupt the flow.

```java
// BAD 6a: relying on the LLM to understand magic strings, easily falling into infinite loops
return "NEED_CONFIRM: amount too large, please confirm";
// BAD 6b: blocking the Agent thread waiting for confirmation
Thread.sleep(1000); // occupying precious thread resources
```

Analysis:

- Unreliable: the LLM may not correctly parse the stop signal, possibly causing infinite loops or mis-execution.
- Resource waste: blocking waits severely consume server thread resources.
- Fragmented experience: confirmation interactions are inconsistent across tools, making frontend adaptation difficult.
- Incomplete coverage: external MCP tools cannot embed local HITL logic, leaving high-risk operations without necessary human oversight.

**Bad smell 7: lack of configuration version management — rollback is like "archaeology"**

Phenomenon: even when configuration is externalized to a database or configuration center, complete version snapshots, audit logs, and canary mechanisms are still missing.

Analysis:

- Difficult rollback: Prompt, model parameters, and tool sets are often changed together. When hallucinations occur or effectiveness drops, there is no way to roll back to "last week's stable state" with one click, because related configurations are scattered across different Commits or records — the recovery process is like "archaeology".
- No trace: there is no audit chain of "who changed what when", nor can A/B tests or canary releases be performed, making optimization effects unquantifiable and risks uncontrollable.

**Summary**: the above bad smells reflect the typical growing pains of Agent engineering evolving from "prototype demo" to "production system". The solution lies in building a unified Agent Runtime platform that pushes down singleton management, configuration externalization, dynamic orchestration, connection pools, identity propagation, HITL interception, and version control as platform foundational capabilities, so developers can focus on business logic and Prompt optimization instead of reinventing the wheel.

#### 1.1.3 Conclusion Summary

Based on the above business and technical thinking, we conclude:

1. Long-term memory needs to be structured, persisted, and customizable.
2. Agent self-evolution needs to be defined at the business dimension, and to achieve an automatic closed loop requires specific and flexible high customization.
3. To truly enable operations staff to customize Agents at any time requires zero-code, configuration-based access: adding new Agents/SKILLs/MCP requires no code — only page configuration for instant go-live, lowering the usage barrier.
4. HITL capability, to achieve engineering-grade precise matching, requires framework-level transformation — building engineering-grade HITL.
5. Canary strategy customization is naturally also engineering-grade development.

We initially hoped to find the optimal solution within internal AI platforms, but gradually found that achieving all of the above is hard to rely on AI technology platforms for a full-link closed loop, for the following reasons:

1. For example, business customization of long-term memory — what platforms usually provide is a RAG mode, which is hard to make precisely controllable.
2. Operations usability is even harder — after all, Agent configuration platforms are full of technical details, and precise binding with business platforms requires significant technical transformation.
3. Engineering-grade HITL customization cannot possibly rely on technology platforms either.

With the above thinking, an uncontrollable idea kept growing in our minds: we want to build, based on high-code development, an Agent platform fully and deeply integrated with the business-finance platform, with fast configuration, fast release, canary, and self-evolution!

### 1.2 Summary of Platform Building Key Points

**1. A fully configuration-driven Agent execution engine**

- **Zero-code, configuration-based access**: adding new Agents/SKILLs/MCP requires no code — only page configuration for instant go-live, lowering the usage barrier.
- **Lightweight isolated Runtime**: adopts a "build on use" mechanism, dynamically constructing lightweight Agent instance shells at runtime. This mechanism reuses the underlying Skill, SKILL, and MCP instances, ensuring ① efficient resource sharing and ② excellent performance, while achieving complete isolation of the Agent session execution environment, guaranteeing stability and security.

**2. Low-cost compatibility with various ecosystem platforms**

- Universal Skill marketplace extension capability: can quickly support other Skill marketplaces
    - Production environment: integrates with the Aone Skill marketplace, meeting high-availability, high-standard production-grade needs.
    - Development and testing: builds OSS Skills, providing a flexible debugging and validation environment to accelerate iteration cycles.

**3. Multi-dimensional promotion of overall Agent effectiveness evolution**

- Inner loop: persist and structure conversation and user operation trajectories, automatically performing preference and case consolidation on a schedule.
    - Supports configurable enabling and customization of collection strategies
    - Feature: self-closing-loop
- Outer loop: user feedback closed-loop optimization, automatically collecting metrics such as session ratings, tool call success rate, and task completion rate, combined with human-annotated data to drive Prompt and Skill strategy iteration, making the Agent increasingly accurate.
    - Not an automatic closed loop; human intervention required
- A/B experiments and canary releases: support multi-version Agent parallel execution and traffic splitting, validating improvement effects with real business data, ensuring each upgrade brings quantifiable experience improvement.
    - Not an automatic closed loop; human intervention required

**4. Fully vertical business Agent configuration, integrated with the business-finance platform admin console, achieving fully free-form page customization**

- **Business semantic native mapping**: Agent configuration items directly correspond to business-finance domain business objects (e.g., invoice types, settlement entities, expense categories) rather than abstract technical parameters; business personnel can precisely define Agent behavior boundaries without understanding the underlying model structure.
- **Integrated admin console governance**: Agent creation, publishing, permission assignment, and version management are all integrated into the business-finance admin backend, seamlessly connecting with existing organizational structures, role systems, and approval flows, ensuring Agent governance aligns with enterprise management systems.
- **Zero-code page customization**: supports freely arranging conversation interfaces, form fields, result display cards, and operation buttons through a visual editor; different business lines can independently craft dedicated interaction experiences, quickly responding to personalized needs without frontend development.

**5. Full-link identity marking and establishment of fine-grained permission control mechanisms**

In the original system, we implemented permission management based on the Web layer, using ACL, data marking, user whitelists, and other means for hierarchical management. But as MCP descends to the HSF interface layer and Agents lead the invocation chain, the traditional Web-layer permission model can no longer cover the new invocation paths.

## 02 Technology Selection

In the selection of the Agent runtime framework, we systematically evaluated the three mainstream technology systems — LangChain, Google ADK, and AgentScope. Finally choosing AgentScope as the core foundation was a comprehensive decision based on three dimensions: enterprise-grade production requirements, Alibaba internal ecosystem fit, and the specificity of business-finance needs.

### 2.1 Core Capability Comparison of the Three Frameworks

![Core capability comparison of the three frameworks](https://mmbiz.qpic.cn/mmbiz_jpg/bvDbzNRia8j3ywu8nhfPjptJvUHNNSeO8Ao4BIz4NSnHPFnLCtQyD9AckiaKTwsUocPGzpZ3OOnnknORmdHITRqGU77JOYb7oK8oYcoXibLYSw/640?wx_fmt=jpeg&from=appmsg)

### 2.2 Final Choice

![Final choice: AgentScope](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3y2iaYibfXsUyoZiayltDUG2aZjqhkAfSXqKQkhA9pwMtY0uUOAiamgaCBK54f8nSa16djxaic8FC80XOIwhUiaQPtFA6eqJCKCzWiaA/640?wx_fmt=png&from=appmsg)

## 03 AgentScope's Three-Layer Architecture Capability Analysis

I believe AgentScope has three layers of capability: the model layer, the ReAct reasoning loop layer, and the external abstraction layer:

### 3.1 Model Layer

As the bottom-most layer of the architecture, its core is interacting with the LLM, centered on the following 5 points:

**1. Unified abstraction and protocol decoupling**

- Rule: all model implementations must inherit `ChatModelBase`, using the Formatter mechanism to convert platform-agnostic Msg into vendor-specific request/response formats.
- Source analysis: `OpenAIChatModel` internally holds `Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest>`; adding a new model only requires implementing the corresponding Formatter without modifying core invocation logic, naturally supporting OpenAI-compatible protocols and various domestic models.

**2. Fine-grained parameter control and multi-level configuration merging**

- Rule: generation parameters are standardized via `GenerateOptions`, supporting "runtime parameters > build-time default parameters" priority merging, and allowing vendor-specific extension fields to be passed through.
- Source analysis: `GenerateOptions.mergeOptions(primary, fallback)` implements configuration merging; the three extension Maps of additionalHeaders/BodyParams/QueryParams ensure the framework does not lag behind API evolution.

**3. Built-in enterprise-grade invocation governance**

- Rule: governance capabilities such as timeout, retry, and circuit breaking sink to the model layer, automatically injected as part of the data flow rather than as external wrappers.
- Source analysis: `ModelUtils.applyTimeoutAndRetry()` directly injects `.timeout()` and `.retryWhen(Retry.backoff(...))` on the Flux chain, automatically effective based on ExecutionConfig, with zero intrusion to business code.

**4. Reactor Flux native streaming output**

- Rule: the entire model invocation chain is fully based on Project Reactor; streaming/non-streaming return Flux from the same interface, supporting backpressure and non-blocking I/O.
- Source analysis: `doStream0()` dynamically switches between SSE streaming responses and `Flux.defer().subscribeOn(boundedElastic())` synchronous calls based on the stream parameter; governance operators are seamlessly embedded in the stream, and upstream perceives a continuous data flow.

**5. Zero-intrusion integration of observability and advanced reasoning capabilities**

- Rule: Trace instrumentation, Prompt caching, and tool invocation enhancements are automatically completed at the model layer; business code needs no manual handling.
- Source analysis: `ChatModelBase.stream()` automatically wraps calls via `TracerRegistry.get().callModel()`; when cacheControl=true, `OpenAIBaseFormatter.applyCacheControl()` automatically adds cache markers; toolChoice and parallelToolCalls parameters directly control tool behavior.

![Model layer architecture I](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2vyuibOsbQibMibMjVQOymQcVxoTOX2VY8z2jHJ6XdAN5A5FCfD8zWgxt5Abdt2sGI95MLD7eJFMF6pKYduAc8jvaMYS0VfMWw8c/640?wx_fmt=png&from=appmsg)

![Model layer architecture II](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1DibUdPnJYUkxRCUuFicfJYuVibukFtNia9avWGA43z3Y4wRZdibIjpELEIG5mcdibnHDaX7wavnsMHEibZz20TE8FFys2bNw8KjKkpU/640?wx_fmt=png&from=appmsg)

### 3.2 ReAct Reasoning Loop — from "Q&A" to "Multi-Step Autonomous Decision-Making"

1. **Standardized three-phase loop**: strictly follows the "Thought → Action → Observation" state machine, with the model autonomously deciding termination or forced exit upon reaching `maxIterations`, avoiding infinite recursion.
2. **Dynamic context injection**: before each reasoning round, available tool Schemas and historical trajectories are automatically formatted and injected into the Prompt, ensuring model decisions are based on the latest information and reducing hallucinated calls.
3. **Streaming intermediate state exposure**: returns `Flux<AgentResponse>`, pushing thinking, tool calls, execution results, and other events in real time, supporting frontend word-by-word display of the reasoning process, breaking the black-box experience.
4. **Tool execution safety isolation**: tool calls are executed independently, with parameter validation and return-value filtering; exceptions are fed back to the model as Observations for self-correction, preventing crashes or data leaks.
5. **Hard constraints on resource boundaries**: through `maxIterations`, `maxTokensPerStep`, `toolTimeout`, and other configurations, over-limit requests are automatically intercepted in the Flux chain, keeping production environment SLAs controllable.

**Core value**: transforms LLM reasoning into a monitorable, constrainable, interactive engineering service, supporting autonomous completion of complex business-finance tasks.

![ReAct reasoning loop](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1MeshjI7gMzwO6VSgm5Zk5BMW86TIVic964Wz9CAztjhugiba0T88libnUCLeWGCjIPiaLjQEK5eope1N8yicgeWdXqFNlW873l1fI/640?wx_fmt=png&from=appmsg)

### 3.3 External Abstraction Layer

#### 3.3.1 Core Classes

![Core classes I](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3ib9BywL0pHsIic713gTxfAiaSjG8BGuSfUuWev261JK5icTkcK3JMq5mK9N7kkaac4sSTfKDVKicdLZZ0wticn8oxB2IAlJdFOibRHg/640?wx_fmt=png&from=appmsg)

![Core classes II](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1iaBUVfzYHicXd1agekCxh6mIAy5QzvfVTibiarpjSOCbGvOnnV3RRib5unBhb75Wx4T7etLn6l9ETfNhjQfYmjPRZf3DwoPxL8dTU/640?wx_fmt=png&from=appmsg)

#### 3.3.2 Tool Shortcut Mechanism

![Tool shortcut mechanism](https://mmbiz.qpic.cn/sz_mmbiz_jpg/bvDbzNRia8j2aaNqJMg8QVlluoW4DZo4porVe4rTHBQ2PI9koG87c9oCPEibvSqnWEtBTsia7ctecEO4s6Pian9cZKAuYK1rcm917qZUdIKsvhw/640?wx_fmt=jpeg&from=appmsg)

## 04 The Three-Layer Architecture of the Reuse Layer

Beyond considering platform building points, before practice we also considered building the reuse layer — this is to achieve higher-level out-of-the-box usability after integrating with the Alibaba ecosystem!

We also considered that in the future, other businesses wanting to reference our Agent implementation could more conveniently perform rapid customization!

### 4.1 Tool Classes and Integration with Alibaba Ecosystem Platforms

![Integration of tool classes with Alibaba ecosystem platforms](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1XPXn6Kj87p0BzWDFYssB8Mw2pzcF6OUOrUJzJt4sYBIP3hWXTkzGEBDBIlGwfcEvMKicmrKibJKhG3qh8VhwAnS8dyPKHHGv6I/640?wx_fmt=png&from=appmsg)

AgentScope natively only provides the `AgentSkill` + `SkillBox` interface definitions and does not provide any external sources. The reuse layer completes the ecosystem of enterprise-grade tools and derived classes:

![Enterprise-grade tool ecosystem completed by the reuse layer](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3QNqRlZnYSoAmIS1sic4TbsDYUyzicviaO3Fr2dIRwZX4OnBJJOpic0usOQOMKYK7vrJdzeJaXP1dUDmNbdWh9erBcTEmelcVqqVw/640?wx_fmt=png&from=appmsg)

This layer provides simple access capability to the Alibaba marketplace.

### 4.2 Agent and Related Ecosystem Registration, Discovery, and Invocation

![Agent registration and discovery I](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1o7ibI14knbyklsPOjHiaVegyIEGbvlhBPbfuUnVtY4nfQS0oE8IviaiatSdBWUaRDPAfLN84XsnojicyNmwBGticX0aoS48aq80pwY/640?wx_fmt=png&from=appmsg)

![Agent registration and discovery II](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0QXYD5uibKFWaVgiaT1kWH9iavqze4dnp0b13SweLZDycLQF6wc2mTETrjcvvfMLtZAPXGdKHibicFUJiaXe2wmaAlKSZTMZzQ3G9Eg/640?wx_fmt=png&from=appmsg)

### 4.3 Link Orchestration and Management | AG-UI Protocol Link Orchestration

![AG-UI protocol link orchestration](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1ib4ASy4QUsSj3rlvxoNgvX5OswkBn5vWQicwTncRA3icicX0pT7vGoNTl0cYhJykPq86SXKvRhZKQwtG2AV2qovBBicgmN69AyDD8/640?wx_fmt=png&from=appmsg)

The reuse layer implements end-to-end streaming from frontend to LLM based on the AG-UI protocol (SSE): externally exposing AG-UI standard input/output (`AguiMvcController` handles `RunAgentInput` / emits `AguiMessage` event streams); internally using `ReActAgent` as the core orchestration engine driving the LLM reasoning-tool invocation loop, carrying request-level context (user identity, RAG query) through the `AguiRuntimeContextBuilder` SPI and `RuntimeContext`, and managing session-level state persistence (Memory / Toolkit / AgentMeta) through the `Session` SPI and `AguiSessionManager`.

![AG-UI end-to-end streaming link](https://mmbiz.qpic.cn/sz_mmbiz_jpg/bvDbzNRia8j3P12U6CQY6ibyibEggtr41SicE8qmdwHan1ibf1V8Bnvxj5gam1icFzsLictMcfNf6XTFmxHkHodv36oxnYTsM6o4fvWBzdadevic2KA/640?wx_fmt=jpeg&from=appmsg)

There are many concepts in the runtime layer; I will elaborate on the practical approach combined with details in the following sections.

### 4.4 Full-Link Observability

![Full-link observability](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3zVzFVHq6pZE5YFjYqgvYsHINtyFGZ3icJTozY0kZ6UuXZMja3iabfNicnohTLEGRhzo50BzR8EWalNQibNQniczOt8pFrRPvIp5Ik/640?wx_fmt=png&from=appmsg)

## 05 Practice Process Based on Development Key Points

The previous sections sorted out the core architecture and capability boundaries of AgentScope and the reuse layer. Based on this, we walk through the practice process around the five major building points one by one — what problems we encountered, how we decomposed them, and how we designed and implemented the solutions.

### 5.1 Key Point One: DB-Driven Agent Execution Engine

#### 5.1.1 Focused Goals

As the saying goes, "function serves value". Before diving deep into technical implementation, let us first paint a picture of the future working scene of the logistics cost business-finance team.

Imagine that on our platform, there are hundreds of frontline operations staff. Every day they shuttle between the five core links of billing, settlement, fund flow, invoice processing, and financial accounting. The current reality is: despite a huge system, a large amount of high-value energy is consumed in inefficient "manual verification" and "repetitive moving" — eyes switching wearily between screens, data mechanically copied between spreadsheets. They long for liberation and urgently need a tireless, precise, and efficient digital assistant.

Therefore, our goal is not merely to deliver a few fixed automation tools, but to build a thriving Agent usage ecosystem.

In this ecosystem, those who best understand business pain points are no longer the remote developers, but the frontline colleagues. We will give them the ability to manually configure Agents, allowing them to define and train dedicated Agents with their own hands, like assembling building blocks, based on current business fluctuations and personalized needs. Whether handling settlement peaks during big sales or processing complex abnormal bills, they can configure on demand, publish instantly, and iterate quickly.

This is not only an efficiency revolution, but also a role reshaping: every operations staff member evolves from a tedious operator to a designer of intelligent processes, truly achieving "everyone is a developer, intelligence everywhere".

![Agent usage ecosystem vision](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2jwHfUJaSKtjNoqgASzpWJhMdFmh50vX5MGl8nBtWsQiaZPSSric4Bs6wWBoZVI5PuhMLx9kxal4IC41iapwdr4sosiawWwQuwnBI/640?wx_fmt=png&from=appmsg)

#### 5.1.2 DB Design

![DB design overview](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2cgjUo9FO8uYpiaeF4xd3mC894tHRk1YE3w9vooNP5CaMUUic9WUia302XANwqdMWwibhkQMbrexhzbzGbiaKSaUOSjgfticL1zoZ4k/640?wx_fmt=png&from=appmsg)

The project has 19 tables in total, divided into 7 modules by functional domain:

![Functional domain modules I](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3t1TeDCm6Obs94Am796JmJs5ISAWIBNPJd8AxB1FNHpRwticSvTI6DBQtUEUrINyNqNrhx9deQmL6FtwibBHibcPS4dATlpbxqE0/640?wx_fmt=png&from=appmsg)

![Functional domain modules II](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2fnYFHhfVTia7rDxNtaEkRdbo8IMbkzfQlRumXojOcbU7jRm3McrfxDeoyKqdGAsFlP5lWMwqJbooXGY358tpjibKnO5CIvbcC4/640?wx_fmt=png&from=appmsg)

#### 5.1.3 Overall Flow Core Design (viewed together with the reuse layer)

![Overall flow core design](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0c2NLt9WpyAeQVYM6pb7PR2uicPFQnJAuibAOcgRcnEWXEaD1HWjQPhKlJtZlv63nPQM2Cw0XgibYtgm9AO26AwqgPxvWibQrLUibs/640?wx_fmt=png&from=appmsg)

The core design points for achieving DB-driven Agent execution are as follows:

##### 5.1.3.1 Core Link One: Startup Registration — from DB to In-Memory Snapshot

The reuse layer has a large amount of registration capabilities; the core of the DB-driven design naturally needs to connect DB data to registration points — this is a typical design of the socket pattern!

When is the right time to connect? Answer: application startup and updates of core SQL attributes both need to trigger overall Agent configuration updates through the registry!

Starting with application startup: we uniformly start from a super base class, scan the DB, and complete the connection — that is `AgentFactoryRegistry`!

Besides connecting, another core goal of `AgentFactoryRegistry` is to build a runtime snapshot, which plays a key role in subsequent chat conversations!

`AgentFactoryRegistry` (implementing `SmartInitializingSingleton`) performs three-phase startup after all Spring Beans are initialized:

1. **Build static index**: collect all `BaseFinanceAgentFactory` subclass Beans (static Agents defined in code), and put them into `staticIndex` by `agentCode`.
2. **Read DB and split**: read all enabled configurations from `ac_agent_config`, and take the intersection with the static set:
    - **Static Agents** (with corresponding Factory classes) → call `factory.refreshFromDb()`
    - **Dynamic Agents** (in DB but no Factory class in code) → call `dynamicRegistry.register(agentCode)` to automatically create `DynamicAgentEntry`, and finally also call refreshFromDb
3. **Failure aggregation**: after all Agents finish, the failure list is thrown once for fail-fast.

First, look at `AgentFactoryRegistry`:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentFactoryRegistry implements SmartInitializingSingleton {
    private static final Logger logger = LoggerFactory.getLogger(AgentFactoryRegistry.class);
    @Resource
    private List<BaseFinanceAgentFactory> staticFactories;
    @Resource
    private DynamicAgentRegistry dynamicRegistry;
    @Resource
    private AcAgentConfigMapper acAgentConfigMapper;
    @Resource
    private DynamicAgentProperties dynamicProperties;
    private final Map<String, BaseFinanceAgentFactory> staticIndex = new LinkedHashMap<>();
    @Override
    public void afterSingletonsInstantiated() {
        // ==================== Phase 1: build static index (exclude DynamicAgentEntry as defensive fallback) ====================
        for (BaseFinanceAgentFactory f : staticFactories) {
            if (f instanceof DynamicAgentEntry) continue;
            String agentCode = f.simpleName();
            BaseFinanceAgentFactory previous = staticIndex.put(agentCode, f);
            if (previous != null) {
                logger.warn("[AgentFactoryRegistry] duplicated static agentCode '{}': {} replaced by {}",
                        agentCode, previous.getClass().getName(), f.getClass().getName());
            }
        }
        logger.info("[AgentFactoryRegistry] static factories ({}): {}",
                staticIndex.size(), staticIndex.keySet());
        // ==================== Phase 2: read full DB set, split ====================
        List<AcAgentConfigDO> allEnabledConfigs;
        try {
            allEnabledConfigs = acAgentConfigMapper.selectAllEnabled();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load active configs (status>=1) from ac_agent_config", e);
        }
        if (allEnabledConfigs == null) {
            allEnabledConfigs = Collections.emptyList();
        }
        // 2a. Startup self-check: forbid legacy agentCode containing the _published substring (defend against suffix method R1 naming key collision)
        List<String> illegalCodes = allEnabledConfigs.stream()
                .map(AcAgentConfigDO::getAgentCode)
                .filter(c -> c != null && c.contains(AgentVariant.PUBLISHED_SUFFIX))
                .collect(Collectors.toList());
        if (!illegalCodes.isEmpty()) {
            String msg = "ac_agent_config contains agent_code with reserved substring '" + AgentVariant.PUBLISHED_SUFFIX
                    + "' (conflicts with PUBLISHED variant suffix): " + illegalCodes;
            if (dynamicProperties.isStrictMode()) {
                throw new IllegalStateException(msg);
            } else {
                logger.warn("[AgentFactoryRegistry] strict-mode=false, continuing despite illegal agent_code: {}", msg);
            }
        }
        List<String> dbCodes = allEnabledConfigs.stream()
                .map(AcAgentConfigDO::getAgentCode)
                .collect(Collectors.toList());
        Set<String> dbCodeSet = new LinkedHashSet<>(dbCodes);
        Map<String, Throwable> failures = new LinkedHashMap<>();
        // 2b. Static: DB must have a corresponding enabled row (consistent with V1 strong constraint); call refreshFromDb
        for (Map.Entry<String, BaseFinanceAgentFactory> e : staticIndex.entrySet()) {
            String code = e.getKey();
            if (!dbCodeSet.contains(code)) {
                failures.put(code, new IllegalStateException(
                        "static factory exists but ac_agent_config has no active row (status>=1) for agent_code='" + code + "'"));
                continue;
            }
            tryRefresh(code, e.getValue()::refreshFromDb, failures);
        }
        // 2c. Dynamic: full DB set - static set, automatically create DynamicAgentEntry (DRAFT variant)
        List<String> dynamicCodes = dbCodes.stream()
                .filter(c -> !staticIndex.containsKey(c))
                .collect(Collectors.toList());
        for (String code : dynamicCodes) {
            tryRefresh(code, () -> dynamicRegistry.register(code), failures);
        }
        // 2d. Register PUBLISHED variant for agents with current_publish_version > 0 (key=${code}_published)
        //     Note: PUBLISHED variant failure does not affect DRAFT service availability; aggregated separately, warn-only, no fail-fast
        Map<String, Throwable> publishedFailures = new LinkedHashMap<>();
        List<String> publishedCodes = new ArrayList<>();
        for (AcAgentConfigDO cfg : allEnabledConfigs) {
            Integer cpv = cfg.getCurrentPublishVersion();
            if (cpv == null || cpv <= 0) {
                continue;
            }
            String code = cfg.getAgentCode();
            String publishedKey = AgentVariant.PUBLISHED.registryKey(code);
            tryRefresh(publishedKey,
                    () -> dynamicRegistry.registerPublishedVariant(code),
                    publishedFailures);
            publishedCodes.add(code);
        }
        if (!publishedFailures.isEmpty()) {
            String summary = publishedFailures.entrySet().stream()
                    .map(e -> "  - " + e.getKey() + " → " + e.getValue().getMessage())
                    .collect(Collectors.joining("\n"));
            // PUBLISHED variant failure is warn-only, no fail-fast thrown (DRAFT can still serve)
            logger.warn("[AgentFactoryRegistry] PUBLISHED variant init failed for {} agent(s), DRAFT remains available:\n{}",
                    publishedFailures.size(), summary);
        }
        // ==================== Phase 3: failure aggregation + strict-mode control (only DRAFT failures participate in fail-fast) ====================
        if (!failures.isEmpty()) {
            String summary = failures.entrySet().stream()
                    .map(e -> "  - " + e.getKey() + " → " + e.getValue().getMessage())
                    .collect(Collectors.joining("\n"));
            String msg = "Agent init failed for " + failures.size() + " of " + dbCodes.size()
                    + " agent(s):\n" + summary;
            if (dynamicProperties.isStrictMode()) {
                throw new IllegalStateException(msg);
            } else {
                logger.warn("[AgentFactoryRegistry] strict-mode=false, continuing despite failures:\n{}", msg);
            }
        }
        logger.info("[AgentFactoryRegistry] init OK. static={}, dynamic={}, published={}, total={}, strict-mode={}",
                staticIndex.size(), dynamicCodes.size(), publishedCodes.size() - publishedFailures.size(),
                dbCodes.size(), dynamicProperties.isStrictMode());
    }
    private static void tryRefresh(String agentCode, Runnable r, Map<String, Throwable> failures) {
        try {
            long start = System.currentTimeMillis();
            r.run();
            logger.info("[AgentFactoryRegistry] init OK for '{}' in {} ms",
                    agentCode, System.currentTimeMillis() - start);
        } catch (Throwable t) {
            logger.error("[AgentFactoryRegistry] init FAILED for '{}': {}", agentCode, t.getMessage(), t);
            failures.put(agentCode, t);
        }
    }
    // ==================== External query / dispatch API ====================
    /**
     * Unified lookup: static first, then dynamic if not hit.
     */
    public BaseFinanceAgentFactory find(String agentCode) {
        BaseFinanceAgentFactory f = staticIndex.get(agentCode);
        return f != null ? f : dynamicRegistry.get(agentCode);
    }
    /**
     * Dispatch refresh by registry key (key may be the DRAFT business agentCode, or the PUBLISHED
     * {@code agentCode_published}).
     *
     * <p>Registered → {@link BaseFinanceAgentFactory#refreshFromDb}; not registered → on-demand dynamic registration:</p>
     * <ul>
     *   <li>PUBLISHED key (matching {@link AgentVariant#PUBLISHED_SUFFIX}) →
     *       {@link DynamicAgentRegistry#registerPublishedVariant} (loaded via published snapshot path);</li>
     *   <li>DRAFT key → {@link DynamicAgentRegistry#register} (loaded via draft path).</li>
     * </ul>
     *
     * <p>This way, when cluster broadcast {@code AGENT/xxx_published} is received by other nodes, the PUBLISHED
     * variant can also be correctly registered instead of being mistaken for a new DRAFT agent.</p>
     */
    public void refresh(String key) {
        BaseFinanceAgentFactory f = find(key);
        if (f != null) {
            f.refreshFromDb();
            return;
        }
        AgentVariant.ParsedKey parsed = AgentVariant.parse(key);
        if (parsed != null && parsed.variant() == AgentVariant.PUBLISHED) {
            logger.info("[AgentFactoryRegistry] PUBLISHED key '{}' not registered yet, attempting on-demand register", key);
            dynamicRegistry.registerPublishedVariant(parsed.agentCode());
        } else {
            // A new row was added to DB at runtime → on-demand dynamic registration of DRAFT variant
            logger.info("[AgentFactoryRegistry] '{}' not registered yet, attempting on-demand dynamic register", key);
            dynamicRegistry.register(key);
        }
    }
    /**
     * Full refresh of all registered Agents.
     * <p>A single failure does not affect other Agents; failed agentCodes are returned to the caller.</p>
     */
    public Map<String, String> refreshAll() {
        Map<String, String> failures = new LinkedHashMap<>();
        for (String code : allAgentCodes()) {
            try {
                find(code).refreshFromDb();
            } catch (Exception ex) {
                logger.error("[AgentFactoryRegistry] refresh failed for agentCode='{}'", code, ex);
                failures.put(code, ex.getMessage());
            }
        }
        return failures;
    }
    /** All registered agentCodes (static ∪ dynamic, deduplicated, ordered). */
    public Set<String> allAgentCodes() {
        Set<String> all = new LinkedHashSet<>(staticIndex.keySet());
        all.addAll(dynamicRegistry.agentCodes());
        return Collections.unmodifiableSet(all);
    }
    /** For the Controller GET endpoint: get Factory by agentCode (used to view snapshot). */
    public BaseFinanceAgentFactory get(String agentCode) {
        return find(agentCode);
    }
    /** List of registered agentCodes; old method name kept for compatibility with existing Controllers. */
    public Set<String> agentCodes() {
        return allAgentCodes();
    }
}
```

Why use afterSingletonsInstantiated?

First, `AgentFactoryRegistry` needs to collect all static `BaseFinanceAgentFactory` instances and obtain DB configuration data. If we used `@PostConstruct` or `InitializingBean.afterPropertiesSet()` here, there would be timing issues.

This can be understood through the following diagram:

![Bean initialization timing](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0JQ3bR9OexSyM7QcPlpfvJGXdVVjcXd09mib4tjT0MDCic2pME2K1T4Dkg4Y2aZt5678PPfiarZrpPEWpnLLjL5WQOhlljQs2DdA/640?wx_fmt=png&from=appmsg)

Whether static or dynamic, every Agent ultimately performs the `refreshFromDb()` operation. The core action of `refreshFromDb()` is to build `AgentConfigSnapshot` — an immutable volatile snapshot object aggregating the Prompt, model parameters, Toolkit (including MCP Client), and SkillBox (including Skill content). Subsequent `createAgent()` calls only need one volatile read to obtain a cross-field consistent view, with no DB calls.

![AgentConfigSnapshot construction](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2glZsQwTL1t3dSjQnSsdQtCQeJYT8QZmGocMt6JI5Ap1HJ7KmQqh9NE4fbmOgGvERgko88JFqicEknQFDuFib5da0AXToFdrUeI/640?wx_fmt=png&from=appmsg)

Besides the snapshot, there is registration — the registration step does three things:

- Register to `AguiAgentRegistry`: `sparkRegistry.registerFactory(agentCode, entry::createAgent)`, implementing PROTOTYPE semantics via method reference (new instance per request); subsequently the framework's `DefaultAgentResolver` looks up by agentCode.
- Register to `ResourceRegistry`: making the Agent appear in the DevTool topology diagram.
- Register to the A2A gateway: exposed as an A2A protocol endpoint via `DynamicA2ARegistrationAdvice`.

##### 5.1.3.2 Core Link Two: all main tables and association properties are in the management state, and admins persist through the page

In the Management State, all Agent-related configurations are operated through frontend pages and persisted to the relational database. The Runtime only acts as a read-only consumer, loading the corresponding configuration snapshot or draft based on the publication status, ensuring decoupling between configuration changes and online execution.

The configuration system is divided into the main configuration domain (independent entities) and the binding relationship domain (associated entities), with the specific mapping as follows:

![Main configuration domain and binding relationship domain I](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2Vg1gqBrQKtcBYr67nAYLOpdWhXIYYGkacUrYPLL1bgoEGsOB7mCgUNbyhJUmBubMHFOWsRGIPA7NibXjl6avzZWaTaDqPRgcQ/640?wx_fmt=png&from=appmsg)

![Main configuration domain and binding relationship domain II](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1FyqwFXfR7MuD4l6mhPWqHOHT0zPZUUFuia6aRlaxCG4aSPKwiaoFcSEvMiazbHnGPd798YdjUIwkibRsiceEXtksyasWNvOdjFVWA/640?wx_fmt=png&from=appmsg)

To balance "configuration flexibility" and "online stability", the system adopts a strict dual-track version management mechanism.

![Dual-track version management mechanism](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1UY3qg9SKRfl1NVzjxXm8FUHHT4HvZiaOZTiaXd929mWSWb0b1j8kwxznAbx3VK2qK4b7Q9iaia29Vr5gOPlVToHODPictTVP9rhwg/640?wx_fmt=png&from=appmsg)

**Drafting phase**:

- Operation: users modify Prompt, model parameters, or binding relationships on the page.
- Persistence: all changes are directly updated to the main table `ac_agent_config` and its associated binding tables.
- Status mark: `ac_agent_config.status` stays at `1` (Enabled/Draft).
- Impact scope: only affects development/testing environments or debug sessions explicitly reading drafts; does not affect official online traffic.

**Publishing phase**:

- Trigger: the user clicks the "Publish" button.
- Snapshot generation: the system serializes the complete configuration of the current main table and all binding tables into JSON and writes it to `ac_agent_publish_record`. This record is immutable, used for auditing and rollback.
- Version promotion:
  1. `ac_agent_config.current_publish_version` is incremented.
  2. `ac_agent_config.status` is updated to `2` (Published).
- Impact scope: official online traffic immediately switches to the newly published snapshot version.

**Runtime loading**:

- DRAFT Variant: mainly used for DevTool debugging or canary testing scenarios, directly reading the latest draft data in `ac_agent_config`.
- PUBLISHED Variant: the standard mode for production environments, loading the corresponding immutable snapshot from `ac_agent_publish_record` based on `current_publish_version`.

**Advantages**:

- Safety isolation: intermediate states during configuration modification (such as unfinished Prompt edits) do not pollute online services.
- Instant rollback: if anomalies occur after publishing, pointing `current_publish_version` to the previous version number or republishing an old snapshot achieves second-level rollback.
- Audit trail: `ac_agent_publish_record` retains the complete scene of every release; combined with `ac_agent_config_audit_log`, full-link configuration change tracing can be achieved.

![Configuration publishing flow](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3JjOgxLmxic3vLibA4icLrIHwokubCCspMCQwjZUCdDk8NWt99BHXggSFyxhEobS8vR0COZ543fxZftGOcd1dR4M4lcJEiaRKtGZA/640?wx_fmt=png&from=appmsg)

##### 5.1.3.3 Core Link Three: runtime state — full-link SPI customization of the reuse layer

Chapter 4 earlier defined the runtime link of the reuse layer — this is to keep all subsequent Agent operations links consistent! And `AguiMvcController` is precisely what governs the overall runtime entry of the reuse layer!

`UnifiedAguiRestController` is the core controller we defined to handle chat requests; it directly delegates requests to the reuse layer framework's `AguiMvcController`, fully reusing runtime capabilities such as AG-UI protocol SSE streaming, ReAct loop, and Tool invocation.

What if the reuse layer's capabilities cannot cover customization scenarios? Customization of the reuse layer is achieved through framework-predefined SPI interfaces:

![Reuse layer SPI customization points](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1XnmwPF6fSGNzAgJUOZBpjb07e07hHQo3QE3XPVliaHXllcgl7EC9ia0g88ZREO6y2qnlPmIVaVjhZl5Jvx5MetkdIUL3SOtc2Q/640?wx_fmt=png&from=appmsg)

**Key design**: we neither modify the framework runtime link nor enrich the link; instead, we replace default implementations at the SPI points left by the framework. The framework is responsible for protocol parsing and ReAct orchestration; the project is responsible for business logic and infrastructure integration.

#### 5.1.4 Runtime Deep Dive

The previous section said the runtime link fully reuses the reuse layer full link, but it also indicated that every checkpoint of the link is full of SPIs. This section analyzes this link and explains how we achieved the design goals:

Goal: lightweight isolated Runtime: adopting the "build on use" mechanism, dynamically constructing lightweight Agent instance shells at runtime. It must both reuse the underlying Skill, SKILL, and MCP instances (the snapshot mentioned earlier), and ensure:

① efficient resource sharing

② excellent performance

while achieving complete isolation of the Agent session execution environment, guaranteeing stability and security.

Some link concept details are in Chapter 4's operations link layer with detailed content; the focus here is analyzing how to perform runtime design combined with actual scenarios.

Look again at the core classes and key SPIs of the reuse layer runtime:

![Reuse layer runtime core classes and key SPIs](https://mmbiz.qpic.cn/sz_mmbiz_jpg/bvDbzNRia8j0ue9sLicFyTFeiaYJRWWcIA5JqA28U3somYl4DxlSWvZLHgKEKDiczFUT1HsjSVJLicZzDP5le4pkZgfGIGWDJzzAI3rwgerYSdE8/640?wx_fmt=jpeg&from=appmsg)

Key design patterns of the reuse layer SPIs:

1. **Strategy pattern**: `AguiRuntimeContextBuilder`, `AguiSessionManager`, `AgentTool`, and `Hook` are all replaceable strategies.
2. **Factory pattern**: `AguiAgentRegistry` stores `Supplier<Agent>` factories, creating a new instance per request.
3. **Adapter pattern**: `AguiAgentAdapter` converts ReActAgent's internal events to AG-UI standard events.
4. **Observer pattern**: `Hook` implements tool invocation interception via event listening.

If you don't care about technical details, you can use the following diagram to see where the reuse layer SPI design nodes are:

![Reuse layer SPI design nodes](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3twOmMLjPRVa3WopcJ61RQhC2CSvSL3Eupf2bZ0KB1C4wKKV2uWXLb7DfHGac4tIx6ttAm1KcqFYbRdiacoygF6UlQDJ1HrBsc/640?wx_fmt=png&from=appmsg)

If you are technical, it is recommended to look at where these core classes sit in the complete invocation link diagram:

![Core class nodes in the complete invocation link diagram](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1pf1AvyaV7eZuAfmreAJmy34ItjZfKbfInhNQUH1hNic4TtUNnVIrmbdsPVUia0iaB96ibxNUzIekcN4Ed9zzQ0FZKrialhnqhFjjI/640?wx_fmt=png&from=appmsg)

Now look at how we combined the capabilities of the 4 major SPIs of the runtime link to extend the core capabilities of build-on-use, identity propagation, precise human-machine collaboration, and session persistence:

![Overview of the four major SPI customization capabilities](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2F61Ra4lOcybxiaoUet1ttUZKyeAcdxQibgrWuomF2iacd8rVibvdlJG2JAhv9jPMWnZkXaxMiaER8ckonIcI7tdW5dALlhKHNMiciaA/640?wx_fmt=png&from=appmsg)

Each of the 4 SPIs will produce a customized Finance-grade instance; see the detailed descriptions below.

##### 5.1.4.1 FinanceAguiRuntimeContextBuilder (SPI1)

FinanceAguiRuntimeContextBuilder is the first SPI we customized for the runtime link:

```java
public ProcessResult process(RunAgentInput input, String headerAgentId, String pathAgentId) {
        String threadId = input.getThreadId();
        String agentId = this.resolveAgentId(input, headerAgentId, pathAgentId);
        RunAgentInput effectiveInput = input;
        if (this.agentResolver.hasMemory(threadId) && !input.hasResume()) {
            logger.debug("Using server-side memory for thread {}, extracting latest user message", threadId);
            effectiveInput = this.extractLatestUserMessage(input);
        }
        // Context built before execution
        RuntimeContext runtimeContext = this.buildRuntimeContext(effectiveInput);
        Agent agent = this.agentResolver.resolveAgent(agentId, threadId);
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, this.config, runtimeContext);
        Flux<AguiEvent> events = adapter.run(effectiveInput).doFinally((signal) -> {
            logger.debug("Request completed for thread {}, signal: {}", threadId, signal);
            this.agentResolver.onComplete(threadId, agent);
        });
        return new ProcessResult(agent, events);
}
```

It essentially constructs a context object that spans the entire AgentScope request link. The reuse layer framework opens up the creation of `RuntimeContext` to the business layer for customization via the `AguiRuntimeContextBuilder` SPI.

This project implemented `FinanceAguiRuntimeContextBuilder` by inheriting `AguiRuntimeContextBuilder`, assembling the context data of each AG-UI request into the framework's `RuntimeContext`, which contains 4 fields:

![The 4 fields of RuntimeContext](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2j9Ej4HMKzsGkOYGgZzrIMSMvElOudtFzH76cSqswrOz6I1Vw8tXgTP1yCoZ7qoCklicCGtDvUTXNGBp3v8VFI0wUOUvNTNFw4/640?wx_fmt=png&from=appmsg)

Why do it this way?

**Reason one: thread safety.** The underlying model of the reuse layer is an asynchronous thread pool; threads are reused across multiple sessions. If request-level data is passed via `ThreadLocal`, residual data after a thread returns to the pool may pollute subsequent sessions. `RuntimeContext` is bound by the framework to the `AgentBase` instance (per-agent-instance), created with the request and destroyed with it — naturally isolated, with no risk of cross-session data leakage.


**Reason two: full-link reachability.** `RuntimeContext` spans the complete lifecycle of an Agent from creation to execution; multiple downstream link nodes can consume it directly via `agent.getRuntimeContext()` without extra parameter passing:

![Full-link reachability of RuntimeContext](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1eicYBIKHufpOPERtSVarFrRCbGAfib3n75RdOUicqaXEomK9zicOGgJbZN98nR3ic4bBFvXKfuIdYBJTkDkNX56CtX0xLmcicak2pQ/640?wx_fmt=png&from=appmsg)

Combining the two design advantages: it both avoids data cross-talk caused by thread pool reuse, and makes request-level data readily available at any node along the full link — this is the fundamental reason for choosing `RuntimeContext` over `ThreadLocal` or method parameter passing.

##### 5.1.4.2 FinanceAguiSessionManager (SPI2)

Just by its name, you might think it is only a session manager, but that's not entirely true — let's see its interface:

```java
public interface AguiSessionManager {
    Agent getOrCreateAgent(String var1, String var2, Supplier<Agent> var3);
    boolean hasMemory(String var1, String var2);
    boolean removeSession(String var1, String var2);
    void cleanupExpiredSessions();
    int getSessionCount();
    default void saveAgent(String threadId, String agentId, Agent agent) {
    }
    void clear();
}
```

**1) The design intent of AguiSessionManager**

AguiSessionManager is one of the core designs of the reuse layer. It is essentially the bridge between Agent instances and persistent storage — resolving the contradiction between stateless HTTP and stateful Agents under the AG-UI protocol. The framework defines the complete session lifecycle contract through 5 methods:

![Session lifecycle contract](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j0fe64w7b8fR1pCZcmGEavpJ7ALLOxgDvo9iac2niaapxdr9Fk5qSXxaVibSJvGlhqsG2falUtQl7MZZQNibbiaqQ7nicF5knOX2gbJI/640?wx_fmt=png&from=appmsg)

The reuse layer framework provides by default `InMemoryAguiSessionManager` (ConcurrentHashMap cache, data lost on process restart) and `SessionAwareAguiSessionManager` (goes through the framework Session SPI, but fully serializes/deserializes memory every time). Neither can meet production-grade requirements.

When we designed the `getOrCreateAgent` method of `AguiSessionManager` in the reuse layer, it was both for implementing the "build on use" mechanism and, of course, for being able to use a caching mechanism.

Clearly, the reuse layer supports defining a real-time Agent creation entry point on the operations link — the framework hands the creation right of Agents to the business layer through the `Supplier<Agent>` parameter, and our `AgentConfigSnapshot` was actually designed for the caching mechanism as well.

As mentioned earlier, the snapshot builds everything related to the Agent: MCP, Skills, SkillBox, modelParams, Toolkit, and so on. So we can quickly create a lightweight agent and achieve the following:

- **Efficient resource sharing**: the snapshot is a volatile reference; all requests share the same configuration snapshot, and creating an Agent only performs one volatile read — zero DB/network overhead.
- **Excellent performance**: `createAgent()` triggers no remote calls at all; it just assembles prompt + model + tools from the snapshot, completing in milliseconds.
- **Complete isolation, guaranteeing stability and security**: each request gets an independent ReActAgent instance (PROTOTYPE scope), with per-request memory and RuntimeContext not interfering with each other.

After setting the goal, we also need to understand what AguiSessionManager is; and to understand AguiSessionManager, we need to understand the core role of `AguiRequestProcessor`.

**2) The reuse layer AguiRequestProcessor: the control hub for the first two major stages of the operations link**

If broken down finely, the operations link of the reuse layer has more than 20 nodes, but roughly classified it can be divided into 4 stages:

1. **Request preprocessing** — building request context, initializing various core components.
2. **Agent creation and session/memory loading** — instantiating the Agent and restoring historical state.
3. **ReAct loop mechanism** — LLM reasoning → tool invocation → observing results → reasoning again.
4. **Session persistence and wrap-up** — saving state, triggering asynchronous tasks.

`AguiRequestProcessor` is the core control class for everything except the ReAct loop mechanism stage. Judging from the framework code, it holds three key components and one core method — let's take a look:

```java
public class AguiRequestProcessor {
    private final AgentResolver agentResolver;              // → bridges AguiSessionManager
    private final AguiAdapterConfig config;                 // adapter configuration
    private final AguiRuntimeContextBuilder runtimeContextBuilder;  // → our FinanceAguiRuntimeContextBuilder
}
```

It has one core method, process; let's take a look:

![The process method of AguiRequestProcessor](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1n0hYq1bnIuyw72nflL3pZhH4nmxJiaQPfPjU7Yo7UTzTEobRyQPl0Dkbe4a4nGDAceQd2t5lxeDF5Fjwy4piaKWmIMsPVAOCWY/640?wx_fmt=png&from=appmsg)

This core method's orchestration clearly shows the role of `AguiRequestProcessor`: it is not responsible for specific Agent creation, state loading, or context building, but for orchestrating the invocation order of these SPIs. Each SPI plays its own role:

![Responsibility division of each SPI](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j33Jb2Q52ZMHu5fRXFxtAzfLic5Wul9GtI2TBQOtVuw5cJdInrtejOickRoQCQc3Qq0xdTh6Hr8H50AVOZuA70JqHmNrxAAowgnM/640?wx_fmt=png&from=appmsg)

`AgentResolver` holds the absolute authority over obtaining Agent instances; spark supports customization, and here we use the reuse layer's `DefaultAgentResolver` by default, without any modification.

The design intent of `DefaultAgentResolver` is to resolve one core contradiction: `AguiRequestProcessor` only cares about "give me an Agent", but the way of obtaining Agents can be completely different under different deployment modes.

`DefaultAgentResolver` does two things:

1. Caches the relationship between threadId and agentId.
2. Calls the sessionManager.getOrCreateAgent method.
   a. And passes the authority of `obtaining the Agent from AguiAgentRegistry` to getOrCreateAgent.

Thus we can see that the way of creating Agents is fully definable.

Let's sort out the relationships: `AguiRequestProcessor` manages Agents through `DefaultAgentResolver`, and `DefaultAgentResolver` in turn delegates the lifecycle to sessionManager. sessionManager is essentially customizable via SPI, so we decided to customize a sessionManager to truly manage FinanceAgent's Agent instances, history, and memory.

Let's see how our FinanceAguiSessionManager design achieves the goals of lightweight and isolation:

The assembly process of the `getOrCreateAgent` method is divided into three stages, and the entire process relies only on one volatile read:

1. Lightweightly assemble the Agent through agentSnapshot.
2. Load historical session + long-term memory.
3. Detect whether an asynchronous compression task needs to be submitted.

The design of agentSnapshot requires some consideration. Earlier we mentioned that `BaseFinanceAgentFactory` builds the snapshot, so we'd better quickly locate the corresponding `BaseFinanceAgentFactory`. Therefore, during project initialization, we register `BaseFinanceAgentFactory`'s snapshot-based Agent creation method createAgent into sparkRegistry:

```java
 sparkRegistry.registerFactory(agentCode, entry::createAgent);
```

Then registry.get can obtain the corresponding creation function — just execute it!

The core logic of createAgent is assembling ReactAgent; specifically, the assembly process of `createAgent()` is divided into three stages, relying only on the `AgentConfigSnapshot` obtained through one volatile read throughout, triggering no DB or remote calls at all:

**Stage one: Prompt enhancement**

```text
Original prompt (from snapshot)
    ↓ concatenate user long-term memory (memoryService.loadInjectionText)
    ↓ concatenate RAG retrieval results (ragRetriever.retrieve, mode=inject)
    = finalPrompt
```

Both enhancement steps are non-fatal: failure of either step only logs a warning and degrades to the original prompt, never blocking the request. There is a key design here — the injection timing of long-term memory and RAG is chosen at `createAgent()` rather than `refreshFromDb()`, because they depend on request-level `userId` and `ragQuery` (from `RuntimeContext`), not configuration-level static data.

**Stage two: ReActAgent.Builder assembly**

```java
ReActAgent.builder()
    .name(agentName())                    // snapshot.agentName
    .sysPrompt(finalPrompt)               // enhanced prompt from stage one
    .model(getModel())                    // qwenModel bean (Spring singleton)
    .generateOptions(buildGenerateOptions(snap))  // temperature/topP/maxTokens/modelName
    .maxIters(resolveMaxIters(snap.modelParams()))
    .toolkit(snap.toolkit())              // pre-built Toolkit (MCP + App Tools + RAG Tools)
    .skillBox(snap.skillBox())            // pre-built SparkSkillBox
    .hook(hitlHook)                       // Human-In-The-Loop interception hook
    .build();
```

Note that `toolkit` and `skillBox` are both objects already built inside the snapshot. This is the core value of the snapshot: pushing all time-consuming MCP client connection establishment, Tool registration, and Skill loading forward to the `refreshFromDb()` stage; `createAgent()` only does reference passing, truly making Agent assembly for each request a pure in-memory operation.

**Stage three: parameter override cascade**

The six parameters `temperature`, `topP`, `maxTokens`, `maxIters`, `enablePlan`, and `modelName` all follow the same priority chain:

```text
HTTP Header (X-Temperature, etc.) > DB JSON field (modelParams) > DEFAULT constant
```

This allows the same DB configuration to support per-request-granularity parameter fine-tuning without modifying the DB or triggering a refresh.

**3) The immutable design of the snapshot**

`AgentConfigSnapshot` is a `final` class; all List fields are wrapped with `Collections.unmodifiableList()`, exposing only getters externally, with no setters. `BaseFinanceAgentFactory` holds a `volatile AgentConfigSnapshot configSnapshot` field:

- **Write**: only replaces the entire reference in `refreshFromDb()` (`this.configSnapshot = newSnapshot`); no partial modification exists.
- **Read**: at the beginning of `createAgent()`, one volatile read stores into local variable `snap`, and this local reference is used for the entire subsequent process.

This guarantees cross-field consistency — in the same `createAgent()` call, prompt, toolkit, and skillBox definitely come from the same version; no intermediate state exists where the prompt is the new version while the toolkit is still the old version.

**4) The overall design of FinanceAguiSessionManager**

Beyond the lightweight-level creation design of `createAgent()`, the customization of `saveAgent`, `removeSession`, and `hasMemory` also contributes significantly to the overall lightness of financeAgent.

- `hasMemory()` — double-check existence probe: first queries `ac_agent_session`, then `ac_agent_block.maxSeq`, determining "whether there is history" without fully loading conversation history. This is the key hook by which the reuse layer decides whether to go through `extractLatestUserMessage`.
- `saveAgent()` — incremental persistence: skips full writes of `memory_messages` through JdbcSession blacklist, only incrementally appending new blocks (`seq > dbMaxSeq`), reducing write amplification from O(N) to O(Δ).
- `removeSession()` — cascading cleanup: completes batch deletion of `ac_agent_session` + `ac_agent_block` within a single lock acquisition, and synchronously cleans up the `tailSnapshot` cache.

The complete lifecycle performance profile table summarizes the latency and DB operations of each stage: `createAgent` → `hasMemory` → `onEnter` → `saveAgent` → `removeSession`.

These four methods together constitute the lightweight runtime of the finance agent: `createAgent` solves lightweight creation (< 1ms, zero DB), `hasMemory` solves lightweight probing (index hit), `saveAgent` solves lightweight persistence (incremental writes), `removeSession` solves lightweight cleanup (batch deletion).

##### 5.1.4.3 Engineering-Grade Human in the Loop (SPI3)

HITL itself exists in two forms: one at the model level, and the other at the engineering level.

Their differences:

![Differences between model-level HITL and engineering-level HITL](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j08KkDGf8azaoNelSXV7SYWDJicP4rsqbqbc51cyfaRJvRib2BSEBREB8icku29hJFHa8baTicgod0NPIcSz4wL48VeAwecbT3P0aQ/640?wx_fmt=png&from=appmsg)

An example:

![Engineering-level HITL example](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3JhIzwnibSEwKWjibPWnjwODtAiahVL3oAR7BEial9L0nH2JVM4gCS7PftUZRfDKmKBCHFy9zk1PsZRejmN1Z6ZnBvuawHeX3tzicY/640?wx_fmt=png&from=appmsg)

Why use engineering-level HITL? What problems arise if using model-level HITL?

![Problems with model-level HITL](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3dhLI4TM1Dy4lZoDqVel9kNzo9D7af8VibjbpNkVswZOEmiastoSbhYq72giaXVm0HW0YGmtnFR8wib9zSgqeLD8GmSIJ9sWEhLQA/640?wx_fmt=png&from=appmsg)

Since the model is unaware of engineering-level HITL, we can see that engineering-level HITL requires interception operations. The interception points are as follows:

1. How to confirm that the user is performing a confirmation action?
2. How to confirm that interception operations are needed before/after the user uses a tool?
   a. Once an interception point is discovered, how to stop all Agent behavior! Wait for the confirmation result before continuing the previous link;

Fortunately, AgentScope's hooks system is very powerful:

![AgentScope hooks system](https://mmbiz.qpic.cn/mmbiz_jpg/bvDbzNRia8j3JvZBkDfGvriaX6YEvybArD2xo992Frx8VT3D3gubqmv2ASmUbKsAvt1THovYldvo8wzDSxytIjLsQnMLPOPOGibQCtLHCJa8Dc/640?wx_fmt=jpeg&from=appmsg)

Overall link presentation:

![HITL overall link](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0qOJAiaRaRibbB4amWr7VFqrfT2KZ5eQia0nybxxdG6ypQSuPLxHBNsrNYFOdLCiaXcJBHHIBG0VeDbEHLY5VXTiankvJImXTsQzjY/640?wx_fmt=png&from=appmsg)

To summarize:

1. The message format passed by users can be recognized — just agree on the HITL confirmation format.
2. PostReaningEvent is before tool invocation and has engineering-level stopAgent.
3. PostActingEvent is after tool invocation and has engineering-level stopAgent.

Having solved the basic HITL capabilities, we still need to define specific priority strategies and working modes!

**HitlResolver's decision priority**: for both before and after modes, HitlResolver determines which HITL is hit; the specific 5-layer interception logic is as follows:

![HitlResolver five-layer interception logic I](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0IV57m3Yz9WfjUrkcOW8VoTENtcpX2EcPpdL1CUuJYTicmlqvlcfCiaNbg6SuMjPyCRaiaQgYYDKW2r54OvkVEFP95CxFMZ44nmg/640?wx_fmt=png&from=appmsg)

![HitlResolver five-layer interception logic II](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j34V9sN9btJVPnRzjQsBBxBQzFRf7bIjRGVA5qogHoZxMVoyWluib7zFxBJvWBbO8tjT84OoyhV1hNVHO4mEiaa12yCO0R0pCrfI/640?wx_fmt=png&from=appmsg)

Detailed explanation of the three working modes:

**BEFORE (interception before execution)**

![BEFORE interception before execution](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j00MlCnAyB2iaQf8JRTX3zvWWKsMSYia267cBJjjdLDq1JIxQznWBwon4x4A40u5bqaEXUShQGvpX892x84jt8uCICQBmIxIKw8Y/640?wx_fmt=png&from=appmsg)

**AFTER (review after execution)**

![AFTER review after execution](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j33HEd6IHGfLVUnZeSGKHnGXOtfsrId5TF3RJTd3ka39ibaw35X5zRoX7b1KoIVMcNQAiaY7l100LJYbdiaTTE7PZfln3UQXyemp8/640?wx_fmt=png&from=appmsg)

**Resume (resumption after user confirmation)**

![Resume resumption after user confirmation](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j0cPvwOrQYibW8A861O7dnKx174bYzQhaSSszObIVz8W43oEyuftWVcocsparV38sXLOFicsoyN6ztb2VXibLIvOMaKvWbfI9YtIo/640?wx_fmt=png&from=appmsg)

##### 5.1.4.4 ContextInjectingMcpTool (SPI4)

AgentTool is AgentScope's tool-level SPI extension point, which can define encapsulated tool invocation logic:

```java
public interface AgentTool {
    String getName();
    String getDescription();
    Map<String, Object> getParameters();
    Mono<ToolResultBlock> callAsync(ToolCallParam param);
}
```

`ContextInjectingMcpTool` implements this interface, wrapping the framework's native `McpTool` in decorator pattern, solving one core problem: **the MCP Server's authentication identity cannot be welded at build time; it must be dynamically bound to the current request user at every invocation**.

**1) Problem background**

The identity of AgentScope's native `McpTool` is already determined at the build time of `AoneMcpClientBuilder.buildSync()` (DB `ac_mcp_server.user_id`); all users share the same identity when calling the MCP Server. This is unacceptable in financial scenarios — when different users call the same MCP tool (such as querying approval forms), the MCP Server must see the caller's own identity to perform permission verification and data isolation.

**2) Design: identity injection at invocation time**

`ContextInjectingMcpTool` does not change the tool's external contract (`getName` / `getDescription` / `getParameters` all pass through to the delegate); it only completes identity injection at the `callAsync` boundary:

![Identity injection design at invocation time](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0B4SA0OcLHF0IiaoOfQ3HOQcBc6BuD7wuIhS0tOGNo4N2VT4aKdibcygxEluvSL8WfpeMjGqqrbykiaPjLDWwVBx0xY8aZ3iczz5Y/640?wx_fmt=png&from=appmsg)

Key point: identity propagation goes through Reactor Context (a reactive, thread-safe context propagation mechanism), not ThreadLocal. This is because the MCP invocation chain is fully asynchronous (`Mono` / `Flux`), and ThreadLocal is lost during cross-thread scheduling.

**3) McpCallIdentity: the invocation-level identity carrier**

```java
public final class McpCallIdentity {
    public static final String CONTEXT_KEY = "mcpCallIdentity";
    private final String empId;        // BUC employee ID → Normandy bucUserId
    private final String ssoToken;     // BUC SSO token → Normandy bucSsoToken
    private final Map<String, String> extras;  // reserved extension slot
}
```

The reason for designing it as an immutable value object rather than scattered keys: identity fields will grow (empId → +ssoToken → possibly +sessionId / tenant ID in the future). After converging into one object, the middle layers of the transmission chain (`ContextInjectingMcpTool` → `McpTransportContext`) need no modification as fields are added or removed; only the two ends — the assembly end (RuntimeContext reading) and the consumption end (authentication header generation) — need to change.

![McpCallIdentity transmission chain](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2CyV1QicIHtibeKYIkZnibS3SYRYiakA7rbqUVvYthS8I5ObwStL3ibicbtCcFKbyKrgYN6NoPG4CI2NwwO1pXCugiaSM2QEJOEgkOWM/640?wx_fmt=png&from=appmsg)

#### 5.1.5 Summary of Seven Design Philosophies

1. Convention over configuration.
2. DB and application layer isomorphism (eliminating the conversion layer).
3. Draft/publish dual-track (modifications don't affect production).
4. Full coverage of optimistic locking (preventing concurrency conflicts).
5. Component marketplace + binding separation (reuse + independent evolution).
6. Asynchronous task decoupling (not blocking the main flow).
7. Incremental persistence (reducing write pressure).

### 5.2 Key Point Two: Low-Cost Compatibility with Various Ecosystem Platforms

#### 5.2.1 SkillSourceLoader

The current system has established the `SkillSourceLoader` SPI abstraction layer, encapsulating Skill source differences in the interface implementation layer; the upper-layer `SkillRegistry` + `BaseFinanceAgentFactory` are completely unaware of underlying source details. This means the cost of connecting to a new ecosystem platform is compressed to the level of "implementing one `@Component` class".

![SkillSourceLoader SPI abstraction layer](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1FqXialZpBTml6Uhd2fic7WZu7ibHvdHq4896L7kRpicG4k7lDN7GENbPmcUbuL3SFXneIKSUmYb9pFPeBqoLsjiajpFDeLy4icNC90/640?wx_fmt=png&from=appmsg)

**Key interface contract**:

```java
public interface SkillSourceLoader {
    String skillSource();              // source identifier: "AONE" / "OSS" / "GITHUB" / ...
    AgentSkill load(SkillKey key);     // load skill (download + parse)
    default int purgeLocalCache();     // purge all local cache
    default Path localCacheDir();      // local cache root directory
    default void purgeLocalCacheForKey(SkillKey key); // purge single cache
}
```

**SkillKey triple**: `(skillSource, skillName, skillVersion)` — source is a first-class citizen, naturally supporting coexistence of multiple sources.

##### 5.2.1.1 AoneSkillSourceLoader

![AoneSkillSourceLoader](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1xm0T81jLGbATQ9R53PbhaI8Ue9x3SBl63rsTic8bM2lADrpfxZjZUt4Tgl4L3AcWZs5ck0MmGVZ3ZDCoMAeVYeHLHsVvibXDfA/640?wx_fmt=png&from=appmsg)

**Production-grade guarantees**:

- **Strong constraint layer**: every row of `ac_agent_skill` must point to an `ac_skill` row with `enabled=1`, otherwise fail-fast at startup (`IllegalStateException`), eliminating the hidden risk of "configured but cannot be loaded".
- **Weak failure layer**: when the Aone service is temporarily unavailable, the Agent can still start (skipping that Skill), with background scheduled retry for recovery.
- **Version management**: `ac_skill.skill_version` supports semantic versioning; upgrading a Skill only requires adding one row in DB + updating the `ac_agent_skill` binding.

##### 5.2.1.2 OssSkillSourceLoader

![OssSkillSourceLoader](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j0TTmI9j8qicebqVmnT9MMXJ1Sn6tTo6QVFbsrBMs0U5kXK19m8Oy0VJOoG0ngPQoyTgqibwR1CptYTeBscK2pDBwMYg8uhevpsQ/640?wx_fmt=png&from=appmsg)

**Development acceleration value**:

- **Zero-approval iteration**: developer modifies SKILL.md → packages zip → uploads to OSS → adds one row `ac_skill(source=OSS)` in DB → refreshes Agent; the entire process requires no Aone release approval.
- **Rapid version switching**: the same Skill can maintain multiple versions on OSS; developers switch in seconds by modifying `ac_agent_skill.skill_version`.
- **Local debugging friendly**: the local cache directory of OSS Skills is isolated from AONE, not interfering with each other.

##### 5.2.1.3 Cost Analysis of Extending New Ecosystem Platforms

Steps to connect a new Skill marketplace:

![Steps to connect a new Skill marketplace](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2hxTJDtcYyRHvNKEl9QYWE5ibXZtkJWeNXfFO6hertcLXkJ5AIyoyicv7QWgohQnDlV2reGicztE1bHscjmhsCwORPGuZGunsDJc/640?wx_fmt=png&from=appmsg)

**Parts that do NOT need modification**:

- `SkillRegistry` — automatically discovers new Loaders, no modification needed.
- `SkillKey` — already contains the source dimension, naturally compatible.
- `BaseFinanceAgentFactory.resolveSkills()` — only cares about `SkillKey`, not source implementation.
- `ac_agent_skill` / `ac_skill` table structures — the `skill_source` field is already an open string.
- Frontend management pages — just add the new source value to the dropdown.

##### 5.2.1.4 Standardized Skill-Driven Flow

![Standardized Skill-driven flow](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3t41WGiaCOA1A6Wk1omgZoZhtmYGPXxGJc8FwaZs7KS3tWXsjVTtej5SUe5Y6LjibKR949RWkRdmFfMNqPB270zfAibxIARaXrY8/640?wx_fmt=png&from=appmsg)

##### 5.2.1.5 How to Monitor the Complete Driven Lifecycle of Agent-Bound Skills

**1) Skill lifecycle panorama**

![Skill lifecycle panorama](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3eBaicnFz0Jr95Z1LPHkrDp2WvQjZUljpDkcmryAMryDd4PKXGP52eIT8ibyBuyJv4fKwC8TjzaFia7JuGQN54HSGsPtFjMQLWws/640?wx_fmt=png&from=appmsg)

**2) Detailed explanation of the ac_agent_skill_task state machine**

This is the core driving engine of the Skill lifecycle; the currently implemented state transitions:

![ac_agent_skill_task state transitions](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2wNKiapPOBNDxWZicZq0jCSH4yOabHWAGZiclrxSrdsGSvr0Ykkicg2FqIDEz3VwS6UgbR3sgricVibGEIRO9IFgyACG9oeLhFRib3cA/640?wx_fmt=png&from=appmsg)

Stepped 6-time retry strategy:

![Stepped 6-time retry strategy](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3bp29OYy6aiaHOib2YtIVxbJkCxQ2EicbmDzW93KXV2U9gOShuyjiblaaH7Ha7X3qXIzRqRYVshASnib4A58FBHgmwybB43kSAMPoc/640?wx_fmt=png&from=appmsg)

**Scheduler dual-track system**:

- Production environment: `AgentSkillTaskScheduler` (SchedulerX2), recommended cron `0/20 * * * * ?` (one round every 20 seconds).
- Development environment: `AgentSkillTaskLocalScheduler` (Spring `@Scheduled`), `fixedDelay=20s`, needs manual enabling.

#### 5.2.2 MCP

`McpClientFactory.build()` does only one thing — mapping DB rows field by field to the reuse layer framework's `AoneMcpClientBuilder`:

```java
// McpClientFactory.java:69-101
AoneMcpClientBuilder builder = AoneMcpClientBuilder
    .create(server.getMcpServerName())
    .mcpId(server.getMcpServerId());
// Each DB column → one builder setter, all if-not-blank mappings
if (isNotBlank(server.getMcpServerType())) builder.type(parseEnum(AoneMcpType, ...));
if (isNotBlank(server.getAuthMode()))      builder.authMode(parseEnum(AoneMcpAuthMode, ...));
if (isNotBlank(server.getRegion()))        builder.region(parseEnum(AoneMcpRegion, ...));
// ... 8 fields in total
return builder.buildSync();  // transport/auth/URL resolution all handed to the framework
```

**Key point**: URL resolution, transport selection, and auth implementation are all inside the reuse layer framework. The project code only does the "dumb mapping" of "DB → Builder". When the framework adds a new `AoneMcpType` enum value, there are zero lines of code changes on this side — `parseEnum()` is generic reflection, automatically recognizing new enums.

#### 5.2.3 Two Compatibility Strategies: Skill vs MCP

![Two compatibility strategies: Skill vs MCP](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0TvHQIPU3ztNZWSVTCuFk5jic77oCfRIuJUPZbl0Y5dkvGHlmmXd2AFCCvjSxGGoKzzlRM4Qo6GcnAIiaqWmSlVWM3sZv4Qrxhg/640?wx_fmt=png&from=appmsg)

**The design trade-off is clear**: Skills have large differences across platforms (repository protocols, file formats, version models all differ), so we must build our own plugin system; the MCP protocol itself is standardized (MCP schema + HTTP transport), with differences only in URL resolution and authentication methods — these happen to be problems the framework has already solved, so we choose to yield them to the framework and only do the data-driven configuration layer ourselves.

### 5.3 Key Point Three: Overall Agent Effectiveness Evolution

#### 5.3.1 Thoughts

2026 is regarded as the first year of the AI explosion, and Hermes's phenomenal rise made "evolution" a buzzword in the industry. Although various technical articles overwhelmingly analyze various "closed-loop evolution" strategies, few in the industry seem able to clearly define: what exactly is true "evolution"? What is its core measurement metric?

We need to return to the essence and clarify a key proposition: how to distinguish "change" from "evolution"? Taking Hermes's Skill creation strategy as an example, strictly speaking, this is more of a random "change". Due to the lack of an effective value verification mechanism, it is hard to judge whether a newly generated Skill truly improves system capability — change without positive feedback can only be called perturbation, not evolution.

I browsed some of Hermes's source code and checked related materials, reaching a conclusion:

![Capabilities Hermes has achieved](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j22lfS6HV4lZrbrcmicNA86ibybE6kwAudClCfz8Nriau3PfdbdAkZcO2Aqdprk2dQ2IafdibarbDXvM1v7LUU1aeN1j47GOKENVtE/640?wx_fmt=png&from=appmsg)

And what Hermes has NOT achieved:

![Capabilities Hermes has not achieved](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2iblwZAXHib9qtibcWKEeLdyxEMZSicw6tFz5MUJRwg1mPpLWiaAiaicDt4LVHc7CuPuENrIeLPhicwxLEgib2icd8seSlhzricJpRE1PFeI/640?wx_fmt=png&from=appmsg)

Hermes's "evolution" is essentially "self-organized knowledge governance" — it is good at controlling entropy (merging redundancy, archiving outdated content, deduplicating names), but there is no closed-loop effectiveness feedback to prove that change is improvement. The Curator Prompt itself explicitly acknowledges "DO NOT use usage counters as a reason to skip consolidation", indicating the system knows it cannot judge Skill quality from telemetry data. Whether this is a deliberate design trade-off (prioritizing structural governance) or a capability gap to be filled depends on product positioning.

#### 5.3.2 How to Define Evolution

In building autonomous Agents and their underlying Skills, we often fall into a misconception: believing there exists a perfect, ultimate model state. However, the core driving force of Agent and Skill evolution is not abstract self-improvement, but effectiveness feedback based on specific scenarios, especially quantifiable effectiveness comparisons.

One core cognition must be made clear: "absolute evolution" is a pseudo-proposition; only "relative evolution" is a real and executable proposition in engineering practice.

**1) Why is "absolute evolution" a pseudo-proposition?**

In open-domain natural language processing or complex task planning, there is no unique "standard answer". The same user intent may have multiple valid execution paths; the same piece of code may have multiple equivalent implementations. Therefore, attempting to define a universally applicable "perfect Agent" is not only theoretically infeasible but also meaningless in engineering.

True evolution occurs in comparative advantage within finite boundaries. We need to map the infinite real world onto a finite evaluation set (Benchmark). Only by building high-quality test cases and introducing fine-grained scoring mechanisms such as 3-point, 6-point, or 9-point scales can we transform vague "good and bad" into trackable, optimizable "high and low".

The logical chain of this relative evolution is as follows:

- Baseline establishment: establish a performance baseline on the current version's evaluation set.
- Differential comparison: score differences on specific metrics between the new version and the old version, or between different strategies.
- Iteration orientation: locate weaknesses based on score gaps, driving the next round of parameter adjustment or logic optimization.

**2) Defining metrics**

![Evolution metric definition I](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j0jQgVMwiaz7Nhjw5qQlqm3Fq0vdgcn8hbSmxD86QORJFvSePLcy9MNqyqU67hqfVCcphV3GyObFSoLlRo4DXRtrIABTkNnl0icg/640?wx_fmt=png&from=appmsg)

![Evolution metric definition II](https://mmbiz.qpic.cn/mmbiz_jpg/bvDbzNRia8j2dlVgchT5kRaia2NNEdGsEiayqicJLXuQt69PGNfj4CU3U12TeA1o1muILIIqfEuevCfPvqOaC4iaV7YLmNMibvmb7TI3I0SaPJheY/640?wx_fmt=jpeg&from=appmsg)

Weight design:

![Metric weight design](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3icJEPicichoQllq9aOGmdUia7f1tTyktiav8AvbpQM4iaASFGAL6QDTaIUYYYuEZLibZeIB2VfIoDaWZoZucCyZYhahrzOC3ubqSDNo/640?wx_fmt=png&from=appmsg)

**3) Defining evaluation**

![Evaluation definition](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1InMIyfagxHALib3tB5s4bJvhF7M44OB2MkTNmQicHI7cDcSIvm8Q5q7PbRMNyh6Q5ib3CTsZfq0bkl8qTtyB5s7I6PDh4cF4HIA/640?wx_fmt=png&from=appmsg)

#### 5.3.3 How to Develop the Capability of an Evolution Closed Loop?

Human closed loop vs automatic closed loop

**1) Human closed loop: the minimum viable evolution system**

Before discussing automation, we must first admit one thing: **the human closed loop is not a primitive form of the automatic closed loop — it is the prototype validation of the automatic closed loop.**

The flow of the human closed loop is intuitive:

![Human closed loop flow](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1nWicialOJYu8l691eV9VJwIUdsGXRYFANzfolf2YxHqddLEiaKcs8ca2xkFaHQM8rEkOjCP8YRFU7JoK8Ry4t03L6jibKE2RGuSs/640?wx_fmt=png&from=appmsg)

This flow looks primitive, but it is irreplaceable at certain stages.

**The true value of the human closed loop**

First, it establishes the mental model of attribution.

In the early stages of the system, you don't even know what "failure" looks like. User complaints might be routing problems, or underlying data source problems, or the user's own unreasonable expectations. These classifications can only form a stable judgment framework after humans manually review hundreds of cases.

Skipping this step and going straight to automation is like training a classifier without labeled data — you don't know what you are optimizing.

Second, it validates whether interventions are effective.

The biggest risk early on is not "evolution too slow" but "evolution in the wrong direction". The human closed loop lets you directly perceive: after modifying a skill description, did routing really become more accurate? Or did only the test cases you constructed happen to pass?

This intuition, when the system is immature, is more reliable than any automated evaluation.

Third, it discovers "unfixable" cases.

Some failures are not Skill problems but system architecture problems, or even product definition problems. These cases need to be manually identified and upstream changes pushed forward; the automatic closed loop lacks this judgment.

Deciding whether an automatic closed loop is needed:

![Deciding whether an automatic closed loop is needed](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2ACKDucc2JseNXJ2YEYd6l6xtadqumJRwb6gmG9WFhBGl7MXRw3W7ojdPy0iaLibibshOmadd3kC6LMRHuxqvOTuiaviaNFuxGuGvg/640?wx_fmt=png&from=appmsg)

**2) The bottleneck of the human closed loop: why will it inevitably hit the ceiling?**

The bottleneck of the human closed loop is not "slowness" — slowness is just the surface. There are three real bottlenecks:

**Bottleneck 1: evaluation consistency**

For the same bad case, two developers may give different attribution conclusions. This is not a capability problem; it is cognitive bias.

```text
Case: user asks "last month's supplier payment term distribution"
Developer A: attributes to routing problem — should go to financial-analysis-skill,
         but was routed to data-query-skill
Developer B: attributes to capability gap — financial-analysis-skill exists,
         but lacks the "payment term analysis" sub-capability
```

Both attributions are reasonable, but the intervention directions are completely different. If attribution is inconsistent, all subsequent optimizations cancel each other out — today A changes the routing description, tomorrow B adds a new capability to the Skill, and the system's evolution direction becomes a random walk.

The automatic closed loop solves this problem not by "eliminating disagreements", but by solidifying a set of evaluation standards so that all decisions are made under the same yardstick.

**Bottleneck 2: evaluation coverage**

Human review can only ever cover a sample, not the full volume. When the system handles thousands of requests daily, human review may cover only 5%.

This 5% also has severe selection bias — what gets reviewed is often the cases where "users complained", while large numbers of cases where "users didn't complain but the effectiveness is mediocre" are ignored.

The automatic closed loop can process the full traffic volume. It doesn't need user complaints — it can proactively discover cases with poor effectiveness based on predefined evaluation metrics.

**Bottleneck 3: feedback delay**

The feedback loop of the human closed loop is day-level: discover the problem today, analyze tomorrow, fix the day after, release three days later.

But Skill problems may be hour-level — a schema change in an underlying data source causes all SQL generated by the SQL generation Skill to error out. By the time humans discover it, hundreds of requests have already been affected.

The automatic closed loop can compress feedback delay to minute-level: evaluation runs continuously, and the moment metrics drift, alerts and automatic interventions are triggered.

**3) Automatic closed loop**

![Automatic closed loop three-layer architecture](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0BjkaicCeicq7bu3icWtia8BD1TtvpSNTevbA2bR8K9xKzwKVGoDmNMCqe9T6kibXwZrjBUZicYZmyXsibXia6nib5uJB0OIySNWwhe5q8/640?wx_fmt=png&from=appmsg)

The relationship of these three layers is: the data pipeline provides raw materials, the execution engine provides compute power, and the strategy layer provides evaluation logic. If any layer is missing, the automatic closed loop cannot operate.

**Evaluation data pipeline: from Trace to evaluation assets** — this is the most underestimated but most important part of the automatic closed loop.

**L1: Trace: structured execution records**

Trace is not logs; Trace is structured execution records. The difference between the two: logs are for humans to read, Traces are for machines to analyze. A qualified Trace must contain:

![Composition of a qualified Trace](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1KLbHg4BCHcOo068FUb4jjYRiaxGE6ibM3lI652bXpHaIicSkhOwmqMyyrk1Ip2LiaBqHwzNt4B8hGHfhBnkCC9HvDmGqdZcBmDLc/640?wx_fmt=png&from=appmsg)

**Outcome Signals: the outcome signal system**

![Outcome Signals outcome signal system](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3Y1APJcuj1t3HMq1SJnp5LWy6gUsIlrO3vMZ9JMScs62eSTVpMaibpY6Qm2rG2lrzCmhQ9JscMRcHwpM4sibgcnUHjUBN3oG1jU/640?wx_fmt=png&from=appmsg)

A data pipeline is needed to unify these heterogeneous signals into standardized outcome labels.

**Scoring mechanism**:

![Scoring mechanism](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j190uVc8hZdjOMibG5AzmS8ibjxQpgvwonNt7MUBvJcVdibgzk2V8ib2x8icYswQDsMBaHbI1H1fjGAEUq4AeHDVnJfztdOY1ZX6aUM/640?wx_fmt=png&from=appmsg)

**Several easy pitfalls**

**Pitfall 1: bias in the evaluation dataset**

If the evaluation dataset mainly comes from user complaints (explicit feedback), it will have severe bias — only covering scenarios where "users are willing to complain". Large numbers of cases where "effectiveness is mediocre but users are too lazy to give feedback" are not in the dataset.

Solution: proactive sampling. Stratified sampling by Skill, intent type, and time period, ensuring the evaluation dataset's distribution is close to real traffic distribution.

**Pitfall 2: stability of LLM-as-judge**

Using an LLM to evaluate an LLM's answers introduces randomness in scoring. The same answer may receive different scores in two evaluations.

Solutions:

- Use a four-dimensional rubric (rather than open-ended scoring), with clear 1-5 point standards for each dimension, reducing scoring randomness.
- Average multiple evaluations (at least 3).
- Regularly calibrate with human annotations, ensuring the Pearson correlation coefficient between automatic and human scores > 0.7.
- The performance dimension fully uses automated metrics, not going through LLM-as-judge, avoiding unnecessary scoring noise.

**Pitfall 3: over-optimization**

The automatic closed loop may cause the system to "overfit" to the evaluation dataset. The Skill performs very well in evaluation, but online effectiveness is mediocre.

Solutions:

- Update the evaluation dataset regularly (replace at least 20% monthly).
- Keep a "hidden" evaluation set, never used for optimization, only for validation.
- Monitor online metrics and evaluation metrics simultaneously; investigate immediately when the two diverge.

**Pitfall 4: ignoring the evaluation system's own cost**

Evaluation is not free. Each evaluation consumes LLM API calls, compute resources, and storage resources. If evaluation frequency is too high or the evaluation set too large, costs may spiral out of control.

Solutions:

- Incremental evaluation first: only evaluate affected Skills, no full regression.
- Tiered evaluation sets: core set (must run every time) + extended set (low-frequency runs).
- Use small models for LLM-as-judge, escalating to large models only when uncertain.

**Pitfall 5: lack of calibration of four-dimensional weights**

The default weights (relevance 0.30 / accuracy 0.35 / completeness 0.20 / performance 0.15) are a starting point, not an endpoint. The real weights may vary greatly across different business scenarios and user groups. If not calibrated for a long time, the composite score will diverge from users' real experience.

Solutions:

- Collect human-annotated "overall satisfaction" scores, perform regression analysis with the four-dimensional weighted score, and reverse-engineer the true weights.
- Maintain differentiated weights by Skill type (e.g., raise the performance weight of alert-type Skills to 0.30).
- Review weight configuration quarterly, ensuring alignment with user perception.

### 5.4 Key Point Four: Context Compression Strategy and Long-Term Memory Strategy

#### 5.4.1 Purpose

The purpose in one sentence:

- **Context compression**: prevents the LLM context window of a single request from growing infinitely with conversation turns. Core idea: **old conversations → LLM summarization → replace original blocks, keeping the most recent N blocks fully uncompressed**.
- **Long-term memory** (User-level): manages "remembering who the user is and what they prefer across sessions".

#### 5.4.2 Compression

![Context compression design](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2vnMbOdRVfG1IZzlZh8kBedvNM4yVH4dt6ibDmiaShNKUZu4BOqMk7lKRm0pQBEibqL1iakX03SFqRAs7ibFhTnYUZricBa2Gz5nEY0/640?wx_fmt=png&from=appmsg)

A Block's `seq` = the `timestamp(ms)` of the first Msg, guaranteeing strict increment and idempotency.

When does it start? Answer: still a capability of FinanceAguiSessionManager.

![Compression trigger timing](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j2n14coOAFrbCtHMrvMjyPNvIianbIoh6ul14TanVgfaISptEHK3jyJOcZDuotXQiaIaRJJkAPNzUPQkfmC98ZCNrbvO2BxrwOPI/640?wx_fmt=png&from=appmsg)

Compression timing occurs at the createAgent stage, with synchronous probing — exceeding any of the three thresholds triggers it:

![Three compression trigger thresholds](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j3UddfNeaYjgxknpsJxdpwyrMGicQvWw1UQPRrxPIYj5CpSdT2libbMMKPUEAUpR1zJ6ljuuIRcg5o1EKZqeT9mo8IayJGKJby8s/640?wx_fmt=png&from=appmsg)

**Idempotency guarantee**: `INSERT IGNORE` + UK `(agent_code, thread_id, status)` → only one PENDING per thread at the same time.

Complete design:

![Complete context compression design](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0kHPFq8xA5dkIqibPWRfumX8wdDhTORWFaaibgeohTkMkkTheibBkoEvCFicTUh4CLrpgR45zCbSnUgWyvfjfnfmE6ZH517icDXOe4/640?wx_fmt=png&from=appmsg)

**Key design points**:

1. **Asynchronous execution**: compression is not on the request path and does not affect user latency.
2. **CAS contention**: concurrency-safe for multiple workers (`updateStatus(id, PENDING, RUNNING)` returning 0 = someone else already took it).
3. **Executed within Session lock**: avoiding concurrent operations on the block table with onEnter/onExit.
4. **Failure retry**: up to 5 times; after the limit is exceeded, keep RUNNING + last_error awaiting operations intervention.
5. **SUMMARY seq conflict protection**: abandon when `INSERT IGNORE` returns 0, avoiding duplicate archiving.
6. **At the next `createAgent` load**:
   a. `blockMapper.selectActiveAsc()` only fetches blocks with `archived=0`
   b. Returns: `[SUMMARY block] + [most recent 5 NORMAL blocks]`
   c. The SUMMARY block's `role=SYSTEM`, with text starting with `[Conversation Summary]`, which the LLM can recognize as a historical summary

#### 5.4.3 Long-Term Memory Strategy

Automatically extract user preferences and facts from conversations, persist them across sessions, and inject them into the system prompt at each request, making the Agent "recognize" returning users.

![Long-term memory strategy](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1BBSfDvddCPUXdoxajgz2wTrKB0BF95qgCPuslZ6zG9eibqw6qqn9Jx2U195shP7gkfMrL4UicrkCnlGJ8Khnw6swuFzS5qyor0/640?wx_fmt=png&from=appmsg)

### 5.5 Key Point Five: Integration with the Business-Finance Platform Admin Console, Free-Form Page Customization

![Integration with the business-finance platform admin console and free-form page customization](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j3mp5I90ZPWtxPV9wEJicetbU5001Jk7yTZCEToibRJy51W9XQTiaJwz6TwQxkgmKxjW4iafcwBaLp6bicgwZ0u1t1IxRN8lVPu2ZIw/640?wx_fmt=png&from=appmsg)

### 5.6 Key Point Six: Full-Link Identity Marking and Establishment of Fine-Grained Permission Control Mechanisms

When designing the permission system, focus on the following aspects:

- How to embed permission verification logic into the Agent invocation chain.
- How to implement data isolation based on user identity and context.
- How to support dynamic, visual, auditable permission application and authorization flows.
- How to ensure permission policy consistency between the MCP/Skill layer and underlying data services.

Solving this problem directly affects the system's security, compliance, and scalability, and needs to be planned and implemented in sync with architecture evolution.

#### 5.6.1 Sorted Out the Four Defense-in-Depth Aspects of the Existing Permission System

![Four defense-in-depth aspects of the existing permission system](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j1icPhMKJKibmExib9icfMKe0GcryBg7T8qw1K4XznXbrn835mHia74yk4VPy2ZibwuxHLIlZUtSdFRKIEMRKxd8qOPkoRicVrvzl1MGw/640?wx_fmt=png&from=appmsg)

#### 5.6.2 Full-Link Identity Unification

**Current state**: the identity propagation chain within the financeAgent project is largely connected — BUC SSO completes authentication at the entry layer, `empId` + `ssoToken` are bridged to AG-UI's `RuntimeContext` via HTTP Header, and the MCP invocation layer achieves per-request identity isolation through `McpCallIdentity` + Normandy SM2 signatures (`ContextInjectingMcpTool` → `McpClientFactory.buildIsolated()`). This chain is closed within the project.

**Problem**: but when the invocation chain goes beyond this project's boundaries, the identity context breaks:

![Identity context break outside project boundaries](https://mmbiz.qpic.cn/mmbiz_png/bvDbzNRia8j0gaqCmKicm7EpMBSicuYJsO0JbaWNLH8VqdTYHGavgVqonhABb7pcFx2aOzmib1DicM2mSjtheMDQaZBWPfR0WyfRvzvyrt7xx6aw/640?wx_fmt=png&from=appmsg)

**Plan**:

1. **HSF invocation chain identity propagation**: inject EagleEye RpcContext in the `HsfUtil` generic invocation layer, propagating `empId` as caller context to downstream HSF services. Downstream services can then perform user-level authentication and auditing, rather than only identifying the caller application. Prioritize covering the AMDP data query chain (currently all user queries share the same `AuthParam`, with unauthorized query risks).
2. **Full coverage of MCP identity isolation**: currently `shouldIsolate()` only takes effect for `NORMANDY_AUTH` + `AONE/ZETTA` types. In the future it should extend to all MCP Server types; for MCP Servers that do not support per-request identity, push them to integrate Normandy Auth or OAuth2 token exchange mode, gradually eliminating the security surface of shared tokens.
3. **Unified identity context (Identity Context)**: abstract a `CallerIdentity` layer (beyond the MCP scope of the current `McpCallIdentity`), covering all outbound invocation scenarios such as HSF / MCP / HTTP callbacks / MetaQ message producers. Ensuring that no matter which channel a request enters from and exits through, the end user identity is always traceable.

#### 5.6.3 Canary System Construction

**Current state**: the core framework for canary routing is already built — the `ac_agent_gray_config` table + `GrayMatcher` three-dimensional matching (percentage / whitelist / environment) + the version splitting logic of `BaseFinanceAgentFactory.buildPublishedSnapshot()`, supporting the complete lifecycle of canary release → validation → full rollout (`promoteToStable`).

**Current shortcomings and evolution directions**:

1. **Percentage routing user stickiness**: `GrayMatcher.matchPercentage()` currently uses `ThreadLocalRandom` for per-request randomization; the same user may hit canary in one request and stable version in the next. This is unfriendly to both troubleshooting and user experience. Change to deterministic bucketing via `hash(workNo) % 100 < percentage`, ensuring the same user is always routed to the same version within a canary cycle.
2. **Canary observability**: currently canary hit results are only reflected in version loading logic, lacking explicit instrumentation and metrics. Needed:
   - Mark `X-Gray-Hit: true/false` and `X-Agent-Version` in AG-UI response Headers, for frontend awareness and debugging.
   - Split core metrics (success rate, average latency, tool invocation failure rate) by canary/stable version dimension, connecting to Sunfire Dashboard.
   - Output canary hit logs in structured form, supporting three-dimensional search by `empId` + `agentCode` + `version`.
3. **Multi-level canary orchestration**: current canary granularity is single Agent level. Future needs to support:
   - Skill-level canary: within the same Agent, some Skills use canary versions (e.g., new prompt templates) while others keep online versions.
   - MCP Server-level canary: new-version MCP Servers are only open to canary users, avoiding new tool instability affecting all users.
   - Combined strategies: whitelist + percentage can be stacked (first whitelist internal validation → then percentage expansion); currently the three strategies are mutually exclusive.
4. **Automatic canary promotion and rollback**:
   - Set automatic promotion conditions for canary stages: canary user count ≥ N and success rate ≥ threshold → automatically expand percentage → full rollout.
   - Set automatic rollback conditions: canary version error rate suddenly increases (> X% relative to stable version) → automatically switch canary traffic back to stable version, emit Sunfire alert.
   - Currently `promoteToStable` is a manual operation; an automated decision layer needs to be added on this basis.
5. **Canary-HITL linkage**: new tools/Skills in canary versions should automatically raise the HITL (Human-in-the-Loop) confirmation level — tool invocations in canary traffic go through BEFORE mode confirmation by default, while stable versions can downgrade to AFTER or no confirmation, reducing the blast radius of canary versions.

#### 5.6.4 Other Security Infrastructure

![Other security infrastructure](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1yFo9I3eNlmV6MicSfQ6EKk1GK9wTzaUOXdFZTfOC5sSvfKy9Y5ibO8IbOD2VSqLoyz5iaz6WUDFYU51Nrz9TKZPT9jgj9IvMRbw/640?wx_fmt=png&from=appmsg)

The above plans are based on the current actual state of the code (`GrayMatcher`, `McpClientFactory.buildIsolated()`, `HitlHook`, `UserUtils`, etc.); each improvement has clear code entry points and can be implemented in phases by priority.

### 5.7 Implementation of Full-Link Observability

![Implementation of full-link observability](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j2RYiabI55AylqPA6QW12s0l6Z3KL9WBhOeUtHibQxtdVicPL4f8Pia0ib4smWAPiaY7Dqo9giceIPg5264PMpibQe33tSQyqqkVFe8cCg/640?wx_fmt=png&from=appmsg)

## 06 Final Overall Framework Summary

![Overall framework summary](https://mmbiz.qpic.cn/sz_mmbiz_png/bvDbzNRia8j1Z2sPdjkXUOwibQL1cmqYzEWrMgic2B3cTbKQ1zOUFpnpFyv4ww63oKuW8mweOxq49qYNibrpjgnX0d9AUvsZSpxVqbZQelI5HoM/640?wx_fmt=png&from=appmsg)

---
title: AgentScope 2.0 Production-Ready - Enterprise Harness Explained
---

<font style="color:rgb(44, 44, 43);">The core idea behind AgentScope Java 2.0 is to add a </font>`Harness`<font style="color:rgb(44, 44, 43);"> engineering layer on top of the </font>`ReActAgent`<font style="color:rgb(44, 44, 43);"> reasoning kernel. Developers can keep using the lightweight ReAct loop, or selectively enable Workspace, persistent memory, Session, Sandbox, Skill, and Subagent capabilities to land the same set of Agent logic in an enterprise-grade distributed service.</font>

After five RC iterations, the AgentScope Java 2.0 GA release is officially available:

+ Docs: [https://java.agentscope.io](https://java.agentscope.io)
+ GitHub: [https://github.com/agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)
+ Release Notes: [https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.0](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.0)

> This article is compiled from Liu Jun's public technical talk on AgentScope 2.0 in July 2026, faithfully reproducing the on-site presentation.
>

##  <font style="color:#5e5e5e;">AgentScope 2.0 Introduction</font>
AgentScope has been around for two years. We released the 2.0 version in the first half of this year. The core capability of 2.0 is to integrate the entire Harness solution into the framework. This also means the scenarios we target are mainly enterprise-grade distributed agent scenarios.

This article is structured in three parts. The first part covers the core capabilities of 2.0 that everyone cares about. Some developers, including those inside enterprises, have already built many production-grade applications and agents with AgentScope 1.0, so I will briefly introduce the design differences and how to migrate. The middle and largest part introduces the core design of Harness and what capabilities it provides. Finally, we will look at a few examples to see what real enterprise-grade things it can do.

### <font style="color:#5e5e5e;">AgentScope Ecosystem Panorama</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122675006-d6f5a6c1-4239-447e-a1fb-92eb318317ac.png)

Let's start with the big picture. You can think of AgentScope as a framework, which is the blue part in the diagram.

As a framework, we now have implementations in Python, Java, and TypeScript, and the Go implementation is also under development, so the framework basically covers all mainstream languages.

The framework layer mainly defines how agents are developed and defined. For example, in the middle is the entire Agent Loop, with well-designed Reasoning and Tool Call implementations that you don't need to worry about. Models, as well as the delivery of Events and Messages, are all built in.

In 2.0 we added Workspace as a very core abstraction, and also did more context management. This is the framework in the blue middle part.

Extending outward, there are many ecosystem adaptations around the agent construction process. On the model side, the left part covers domestic DeepSeek, OpenAI-compatible models, and Qwen models, all of which are supported.

For observability, the framework now has default OpenTelemetry instrumentation, so observability data can be reported to any OpenTelemetry-compatible platform, such as open-source LangFuse, or Alibaba Cloud products—formerly ARMS in the microservices era, and now products specifically for the Agent era targeting the Agent Loop, all of which can be plugged in.

Then there is Higress. As mentioned earlier when talking about Agent Teams, whether it is model proxying or MCP proxying, including using Nacos for Skill or MCP marketplace management, the entire agent ecosystem is fully integrated.

Further up, QwenPaw and AgentTeams are concrete products and enterprise-grade agent management capabilities derived from this framework ecosystem.

### <font style="color:#5e5e5e;">ReActAgent Kernel and Core Components</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122684082-53e4938d-8fbc-4d7a-af69-0538ce8955c6.png)



After the big picture, let's return to the AgentScope framework itself. For the bottom layer, 2.0 remains unchanged: the core reasoning-and-tool loop of the ReAct Agent is unchanged.

I listed a few core capabilities here; the difference from 1.0 is not large, and the bottom-layer capabilities are unchanged, with only some design optimizations. Or looking below, we added Permission, which is an extra design for tool-call permission control, because previously there was no tool permission management capability.

In the middle there is also Middleware, which is equivalent to the previous Hook. In the new version we optimized event delivery and intermediate intervention. So overall, Model, Tool definition, and the context in the middle (the content we previously produced) are largely similar—this is the whole ReAct Agent part.

### <font style="color:#5e5e5e;">1.0 -> 2.0 Migration Guide</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122690675-75e21e37-f202-4cd0-ab71-c28649694fa7.png)

Although the core bottom-layer logic is unchanged, I still list three migration points from 1.0 to 2.0 to watch. Let's look from left to right across three levels.

First, the green part. During the upgrade we guarantee overall compatibility, meaning all the capabilities mentioned earlier are generally compatible. Even if some APIs are deprecated—such as Hook being replaced by Middleware by design—in the 2.0 release we still mark them deprecated but keep them. So theoretically, most capabilities can be compatible and smoothly upgraded.

The middle part lists what you must change. Although most capabilities remain compatible, some things have changed. If this part is not changed, you may get compile or runtime errors. This mainly shows up in several aspects:

The first is state management. We now introduced the concept of Agent State across the framework; all Agent runtime state is managed through Agent State. It differs from the previous Session in underlying data format, which you should be aware of. If you previously had running 1.0 Agent states, we provide a compatibility layer—if you switch to 2.0 and publish, it will still recognize your previous 1.0 state. But you should know that from API to implementation, state management has changed.

Another big change: because we strongly emphasize multi-tenancy—whether User-dimension isolation or Session-dimension isolation—we added the concept of Runtime Context to the entry points of the Agent's `call` and `stream` methods. That is, you must pass in the runtime context—such as which User and which Session. At the same time, it provides extensibility; you can build many extensions on top of it.

The following items are all strongly related to State and Session.

The last part is what you can migrate gradually. Once you fix the breaking API changes in the middle part, the rest are deprecated items that may be removed in version 2.1, so you can migrate to 2.0 first and then upgrade step by step.

This part is rather tedious; there is a dedicated migration link on the official website, so you can check it out.



## AgentScope Harness Core Design and Features in Detail
Next, we come to the important part of today: the design of the entire AgentScope Harness. Let's first look at the overall architecture of Harness on AgentScope.

### <font style="color:#5e5e5e;">Harness Overall Architecture</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122696150-b486a69e-f316-407b-b023-edf68b00e52f.png)



Look at the big blue part in the middle. Harness is built on top of the AgentScope underlying agent reasoning and execution component—whether 1.0 or the current one. It can be understood as another layer wrapped around the 1.0 ReAct Agent.

On top of this layer, it packages the capabilities essential for long-running agents—context management, context compaction, agent orchestration, Skill execution, isolated tool execution in sandbox environments, reasoning planning and task state tracking, and even integration with IM messaging systems and tool permission control—into a Harness suite that is built into the framework's bottom layer. You can enable it with some switches, or by following the Harness development pattern. This is equivalent to adding such a layer of capability.

### <font style="color:#5e5e5e;">Harness Quick Tour</font>
Here we use a Java example. How do you use the Harness layer in AgentScope Java? First, you need to add a dependency. Because we added another layer on top, you need to add that layer's dependency.


![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122707767-503a3d8a-41c0-4b3e-a975-ffec45443c78.png)



Next is the development entry point. The ReActAgent API entry still exists, but now there is a new API entry called HarnessAgent. You can use it to build an agent directly—it still uses ReAct Agent underneath, but at the API level you can directly use Harness Agent.



We can see the differences between them: the top part is the same—Name, System, Model; below you can see it has the Workspace concept, you can specify its Workspace, specify some compaction strategies, and more configurations, including Sandbox isolation configuration, all directly through the API.



The difference below is that when calling, you need the Context mentioned earlier. A Runtime Context is defined here; when calling, you mainly pass User and multi-tenant isolation information.




![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122710684-609ea62c-3cd3-42ff-a07c-818ad562d0ca.png)



### <font style="color:#5e5e5e;">Workspace – The Source of Truth for Agent Evolution</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784125157045-a1a7a8e3-e9de-45f9-ae0f-5f2562316c75.png)



Workspace is a core design in today's mainstream agents—whether agent products or agent frameworks. We can understand it as a logical concept. What assets does it contain?



The first part is static assets, which are agent-definition related, such as `AGENTS.md`, Skills, or Sub-Agent. These define what things exist in this business-oriented agent. These are what I define and package with my image; they are called static assets.



Another part is runtime data. This data is generated during agent runtime and accumulated through user interaction—whether real-time Session state records, Task state information, or `MEMORY.md`-style sedimented memory. All these static or runtime assets settle in the Workspace. This is the core concept of Workspace.



### <font style="color:#5e5e5e;">Abstract File System – The Physical Carrier of Workspace</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784125168400-b13a9d3d-9d2f-420b-808f-61c1cea17803.png)



And in AgentScope, we handle Workspace at a finer granularity. For example, one agent has one Workspace, but one agent is used by many users. For different users, we do logical multi-tenant isolation within this Workspace—it can be user-level, Session-level, or agent-level. These are different isolation dimensions.

At the bottom layer, Workspace is a logical concept; what is its physical storage? The most intuitive understanding is disk, which is the most direct. But disk has a problem: for example, in on-premise scenarios it can only be on your local disk, which is the limitation of a Workspace bound to disk.

To solve this—especially since we target enterprise-grade distributed scenarios—we abstracted an interface when going from the upper-layer logical implementation to the bottom physical implementation, the black part in the middle, called Abstract File System, an abstract file system interface.

When the agent operates the Workspace, the physical layer uses this abstract file system interface. We provide three default implementations for it, and of course you can extend arbitrarily:

+ The first is local on-premise on this machine, directly operating disk.
+ If you want user isolation, it is a tree filesystem, a tree-like structure.
+ In production deployment, because one agent is deployed across multiple instances, each instance must see the same Workspace. At this point you can connect the abstract file system interface to a database such as MySQL or Redis, or Alibaba Cloud OSS. This achieves Workspace sharing—the same Workspace instance can be seen by different agent instances.

If you have higher isolation requirements for the Workspace—for example, tool execution (tool execution also happens in the Workspace space)—you can connect it to a Sandbox. One Workspace maps to one Sandbox; as long as Sandbox lifecycle is managed well, multi-tenant isolation is achieved.

This is the logical concept and physical storage implementation of Workspace in Harness, which thus supports distributed scenarios.

### <font style="color:#5e5e5e;">Built-In Context Compaction Strategy – Four Lines of Defense</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784125116995-c234913e-e1b1-4dcb-8f53-e58380e9bbcb.png)



How do we manage all context in the Workspace? First, we provide built-in compaction strategies. During a session run, models have context-window limits; how do we ensure context stays within those limits?



Several compaction strategies are provided here; the figure only shows some of them, and there are actually more detailed configurations. For example, after tool execution results exceed a certain size, we have a truncate-and-offload implementation—offloaded to disk and given as a file reference path; when tool input parameters are too large, there are also length-based truncation strategies; these are basic measures. It also includes compressing past messages and keeping the most recent ones, which are familiar routine compaction strategies.



During compaction, there are still some caveats. The most typical is: try not to lose information when compressing. What information should not be lost?



The lower-right part has a few examples. For example, the overall task execution plan—some complex tasks require planning, and this plan may be in the first few messages; without special handling, direct compression would drop the plan.



Also, based on this plan I may have spawned some subagents, some of which are asynchronous, or the tasks are asynchronous. In asynchronous cases the task may not have returned; I need to continuously track task state. At this point, if we compress crudely, the subagent state may be lost.



Therefore, for such globally updated state—whether plan details, subagent asynchronous task state, my TODO list, or various tool permission authorization records—this information must be guaranteed not to be compressed. So these two parts need differentiated handling.



### <font style="color:#5e5e5e;">Dual-Layer Long-Term Memory – Facts Automatically Persist</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784124420246-a74faf0f-3b3e-4498-b058-4e15955eb0d8.png)



Another capability is long-term memory sedimentation. The compaction discussed earlier is more about transient state—managing and controlling instantaneous state during runtime. When compressing, some information will inevitably be lost; this information can be sedimented as long-term memory.



The framework's strategy is: before session compaction, a Flush sort can be done. The first layer is recorded into a daily file, a structure similar to QwenPaw, into which it can be recorded.



At the same time, we also have a background task that periodically scans the day's memory and the entire Memory sediment, distilling it into a global `MEMORY.md`. This `MEMORY.md` is globally loaded into your System Prompt on every request, so its size and data quality are very important.



Several memory management tools are also provided: Memory Search, Memory Get, and Session Search. The model will query your ledgers and other information at appropriate times based on `MEMORY.md` guidance. This is a coordinated long-term memory sedimentation strategy.



All these steps—whether daily ledger memory extraction, periodic distillation of `MEMORY.md`, or compaction—the Prompts inside are customizable, making it convenient for different scenarios to guide better extraction and memory implementation. The following items in the framework are all Prompt config entry points.

### <font style="color:#5e5e5e;">Subagent Orchestration, Delegation, Parallelism, and Asynchronous Notification</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784125262501-e219f86e-b8c1-4b93-92c0-f8d8a51cf131.png)



Another very important point in Harness is agent orchestration. This diagram expresses: the parent agent directly manages all subagents.



After a task enters the parent agent, we have built-in tools such as Agent Fork and Agent Spawn; the parent agent will spawn subagents as needed. Spawned subagents first have two types: synchronous subagents and asynchronous subagents. Asynchronous subagents are suitable for long-running tasks, and now we support them actively notifying results back to the parent agent when done.



Another special type is the remote subagent, which is also supported, allowing a remote subagent to be spawned. At the same time, we provide the parent agent with a Task List toolkit covering all supporting tools. The parent agent can actively check which subagents exist and the state of each subagent, with supporting tools.



Another point worth mentioning, and a core demand of many enterprise users: usually the user talks directly to the parent agent, and the parent agent spawning subagents is its own business; it manages subagents to complete its task. But in practice, many users (including when we use Claude Code) want to directly switch to a subagent spawned by the parent agent and talk to that subagent to guide it through its subtask.



AgentScope also supports directly talking to subagents. Although this subagent is spawned by the parent agent, there is a way to expose it so that you can talk to it directly.



The whole design of subagents, besides the larger layers mentioned earlier, also has details. For example, whether context is shared between the parent agent and subagent, listed below; including how subagent events are forwarded through the parent agent and how to distinguish whether an event is from a subagent or the parent agent (because everyone's event streams are mixed together), we have markers.



Then there is the permission issue—after the parent agent spawns a subagent, what are the subagent's permissions, and whether it inherits the parent agent's permissions. For these detailed matters, the entire framework has a mechanism in place.

### <font style="color:#5e5e5e;">Sandbox Management: Isolation, Recovery, and Distribution</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122744046-584de68f-e539-424a-8293-694603ad0ab6.png)

There is also a mechanism about sandbox. Sandbox mainly solves the security problem of agent execution, which is a very important part. During agent tool execution, we can put tool execution in a sandbox; the framework has a sandbox lifecycle management system. We won't expand on it here; interested readers can refer to the official documentation.

### <font style="color:#5e5e5e;">Skills: Four-Layer Registry & Execution Inside the Sandbox</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122747931-aaa39b3a-95fc-40c1-b572-cc652a409fbc.png)



There are two parts worth covering about Skill.



The first part is Skill management. AgentScope Harness for Skill management connects to centralized Skill management systems such as Nacos, automatically loading centrally managed Skills for local recognition and use. At the same time, based on the Workspace fine-grained management mechanism mentioned earlier, you can also implement Skill isolation between different users—this user has their own Skills, another user has different Skills, and they are invisible to each other. This can also be achieved.



The other part is Skill execution. Skills are sometimes not simple workflows; they also include supporting scripts and resource files, so their execution must be security-controlled. We now support projecting the entire Skill into the Sandbox, allowing all Skill scripts to execute in a closed loop inside the Sandbox.



### <font style="color:#5e5e5e;">Plan Mode: Think It Through -> Write It Down -> Then Act</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122751350-9d17d85b-4f11-4a40-b87c-5e996beff223.png)



Harness now also supports plan mode. Users familiar with 1.0 should know that 1.0 also had a Plan mode—given a task, plan first and then execute.



The 1.0 implementation was more like an internal state machine, driven by a state machine. In 2.0 we optimized the entire Plan mode: first, a built-in set of Plan-related supporting tools, such as PlanEnter, PlanExit, and so on.



When a user request comes in, you can directly enable Plan. If you are familiar with Coding Agent, you can understand it as the same as Coding Agent's Plan mode—for example, in Codex or Claude Code you can open Plan. Business agents developed with AgentScope are the same: there is an interface you can call to tell it to enable Plan mode; then when you ask a question, it generates a Plan, and later switches to Agent mode to execute, and it executes directly based on the previous Plan.



Or you can let it enter autonomous recognition mode, where it can switch to Plan mode based on the task itself. Because each tool has Permission, when it switches to Plan mode it may ask you first; after the Plan is executed, like Coding Agent, it will pop up a window to ask when switching back to Agent mode. The whole flow is the same; we can string it together on the front-end UI.

### <font style="color:#5e5e5e;">Channel: Messaging Platform -> Gateway -> Agent</font>

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122754918-bd884356-94db-4615-b469-9ae38f00dab2.png)



<font style="color:#080808;background-color:#ffffff;">In some enterprise business scenarios, there is a need to connect to Channel platforms—to connect background tasks with enterprise IM systems. The framework provides native support for this.</font>



## AgentScope Enterprise Agent in Practice (Official Examples)
<font style="color:#080808;background-color:#ffffff;">That covers the Harness part. Harness design is relatively extensive; you can check the official documentation for specific usage. Finally, let's introduce a few examples.</font>

### **<font style="color:#1f2937;">Personal Assistant — Direct Local FS & Shell, Grows with Use</font>**

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122784444-12b760c1-6fb4-4c21-b808-186230266ea5.png)

The first example—these examples are all in our official GitHub repo—is a QwenPaw-like product for AgentScope Java. It implements a very simplified QwenPaw, not a concrete released product; we built it just to verify how to develop a personal assistant product with AgentScope.



As mentioned earlier, its Workspace mode is completely bound to the local disk, so it does not support distributed deployment.

### **<font style="color:#1f2937;">Multi-Tenant Managed Agent Platform — One Self-Evolving Agent, Shared by an Organization</font>**

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122793414-88f0d3ad-ba3b-4ab8-8f57-1902692e8c40.png)



Another example is a multi-tenant agent platform, which you can understand as a no-code agent development platform called Agent Builder.



It has a UI interface and can be deployed centrally in a company. After deployment it becomes a SaaS platform; everyone in the company can create agents on it. As an administrator, I can also create a shared agent for everyone in the company to use.



Although each user uses the same agent, the bottom layer can achieve multi-tenant isolation—relying on the Workspace and physical File System grouping below. This realizes a multi-tenant agent platform where each user's data is isolated. At the same time, I can define my own agent and share it with others.



<font style="color:rgb(44, 44, 43);">This is essentially the prototype of Claude Managed Agents, Langchain Managed Agents, and Qoder Cloud Agents platforms. With AgentScope 2.0 you can build it very quickly by just exposing the control plane and data plane APIs.</font>

### **<font style="color:#1f2937;">Data Agent Platform — Per-User Evolution + Approval-Based Capability Marketplace</font>**

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784122802713-c8373afe-1cc1-4083-a8a7-2865074a89c6.png)



Finally, two more examples. One is Data Agent, which is also a multi-tenant scenario example.



This Data Agent provides each user with an isolated space for their data foundation. Each user can also have their own Skills; Skills sedimented by different users have an approval mechanism in this system—my Skill can apply to be shared, go through an approval process, and after passing it becomes shared and available to all users.



### **<font style="color:#1f2937;">Autonomous Coding Bot — Thread Routing + One-Shot Docker Containers</font>**

![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1784124780453-da758680-dccd-4e49-9eab-ec5529cb782a.png)

The last example is Coding Agent. This Coding Agent is different from locally installed tools such as Claude Code or Cursor.



It is an enterprise-grade service for shared agent scenarios. For example, after deploying this Coding Agent service in an enterprise, a typical scenario is connecting to GitLab—connecting the deployed Coding Agent service to GitLab.



When everyone handles Issues or Pull Request Reviews on GitLab, all requests sent are received by this Coding Agent service. Your task runtime and other users' runtime environments are isolated—it will spawn a Sandbox specifically serving your user. It ensures the continuity of all Issue and Pull Request states you handle, with no mutual impact between users.



Including when you have continuous conversations for each Issue, the state of the entire Issue will not be mixed with other Issues or Pull Requests or affect each other. So it is a Coding Agent example deployed inside an enterprise for R&D collaboration. Building it as a CI/CD platform and driving it with AI is also fine. The whole mechanism underneath uses AgentScope Harness design.



## AgentScope Is Widely Adopted in Enterprises
<font style="color:rgb(44, 44, 43);">Since its open-source release in 2024, the AgentScope agent framework has gradually become an Agent Framework widely adopted by enterprise users, especially for distributed, production-ready agent scenarios.</font>

<font style="color:rgb(44, 44, 43);">Within Alibaba Group, AgentScope (Java & Python) is already the most widely used framework, covering business lines such as Fliggy, Taobao Flash Purchase, Hujing Entertainment, AIDC, Alibaba Holdings, Taobao Trading, Taobao Mobile, 1688, Qwen APP, Amap, Alibaba Cloud, Ant International, Ant Global Payments, and others.</font>

<font style="color:rgb(44, 44, 43);">On the open-source and Alibaba Cloud public cloud user side, it is widely used by leading enterprises in finance, transportation/logistics, consumer retail, manufacturing, energy, healthcare, education/government-media, internet, SaaS, consulting, and many other industries.</font>

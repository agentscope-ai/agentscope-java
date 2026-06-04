---
title: "FAQ"
description: "Frequently asked questions about AgentScope Java 2.0"
---

:::{dropdown} Is AgentScope Java 2.0 compatible with 1.0?
AgentScope Java 2.0 aims to preserve compatibility with 1.x where possible so that most users can upgrade smoothly. That said, 2.0 does introduce API-level breaking changes — including a redesigned agent abstraction and the new event system, permission system, and middleware stack. See the [Changelog](../change-log.md) for details.

    For new projects we recommend adopting 2.0 directly to benefit from the new capabilities; the 1.0 docs remain available for existing users.
:::

  :::{dropdown} Is there a frontend that ships with AgentScope Java 2.0?
Yes. The repository includes the `agentscope-admin` module, an out-of-the-box web app that speaks the same protocol as `ReActAgent`. It works without any custom UI code and integrates cleanly with the event system (`AgentEvent`) and the HITL flow of the permission system.
:::

  :::{dropdown} Will 2.0 ship RAG and long-term memory?
Yes. `io.agentscope.core.rag` and `io.agentscope.core.memory.LongTermMemory` already exist in the repo, but knowledge bases, document readers and similar components are still being completed — track progress in the changelog and on GitHub releases.
:::

  :::{dropdown} Is there a non-Java edition?
Yes. AgentScope ships in three independent language editions, each in its own repository:

    - **Java** — [`agentscope-ai/agentscope-java`](https://github.com/agentscope-ai/agentscope-java) (this docs site)
    - **Python** — [`agentscope-ai/agentscope`](https://github.com/agentscope-ai/agentscope)
    - **TypeScript** — [`agentscope-ai/agentscope-typescript`](https://github.com/agentscope-ai/agentscope-typescript)
:::

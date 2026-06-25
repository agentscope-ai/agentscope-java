# v2.0.0-RC4 Release Notes

## Highlights

This release introduces async tool execution and notification support for the agent harness, adds a persistent spawn registry for subagent session recovery, and includes critical fixes for Kubernetes sandbox stability, sub-agent resource leaks, and MySQL utf8mb4 compatibility.

---

## New Features

- Agent harness now supports async tool execution and notifications, including message bus, async tool registry, and scheduled wakeup dispatching ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- Added String/Message convenience overloads for agent calls; all formatters now support HintBlock ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- Persistent spawn registry in tool context state enables subagent cross-replica routing and session recovery ([#1817](https://github.com/agentscope-ai/agentscope-java/pull/1817))
- DynamicSkillMiddleware implements ToolkitAware to receive the resolved toolkit dynamically ([#1828](https://github.com/agentscope-ai/agentscope-java/pull/1828))
- Kubernetes sandbox now supports injecting environment variables into pods ([#1789](https://github.com/agentscope-ai/agentscope-java/pull/1789))

## Bug Fixes

- Fixed SIGKILL race condition in Kubernetes file uploads by using two-phase archive strategy ([#1826](https://github.com/agentscope-ai/agentscope-java/pull/1826))
- Fixed resource leak where timed-out sub-agents were not interrupted on retry ([#1784](https://github.com/agentscope-ai/agentscope-java/pull/1784))
- Fixed typed attributes being lost when copying RuntimeContext ([#1813](https://github.com/agentscope-ai/agentscope-java/pull/1813))
- Fixed JdbcStore table initialization failure under MySQL utf8mb4 charset ([#1781](https://github.com/agentscope-ai/agentscope-java/pull/1781))
- Made session JSONL offload idempotent to prevent duplicate writes ([#1774](https://github.com/agentscope-ai/agentscope-java/pull/1774))
- Fixed OpenTelemetry context propagation in TelemetryTracer ([#1799](https://github.com/agentscope-ai/agentscope-java/pull/1799))
- Fixed NPE in OllamaChatModel when options are null during tool choice retrieval ([#1803](https://github.com/agentscope-ai/agentscope-java/pull/1803))
- Added missing Jackson annotations to LocalSandboxSnapshot for proper serialization ([#1825](https://github.com/agentscope-ai/agentscope-java/pull/1825))
- Fixed sandbox glob not supporting `**/` recursive patterns ([#1684](https://github.com/agentscope-ai/agentscope-java/pull/1684))
- Fixed SkillFilter matching using composite ID instead of skill name ([#1771](https://github.com/agentscope-ai/agentscope-java/pull/1771))
- Allow custom default vision model in MultiModalTool ([#1701](https://github.com/agentscope-ai/agentscope-java/pull/1701))

## Documentation

- Fixed incorrect hook signatures in middleware docs ([#1835](https://github.com/agentscope-ai/agentscope-java/pull/1835))
- Fixed references to non-existent `.sandboxContext()` in doc examples ([#1792](https://github.com/agentscope-ai/agentscope-java/pull/1792))
- Fixed `getToolName()` → `getToolCallName()` in v2 docs ([#1760](https://github.com/agentscope-ai/agentscope-java/pull/1760))
- Added AI context menu to documentation site

## Full Changelog

https://github.com/agentscope-ai/agentscope-java/compare/v2.0.0-RC3...v2.0.0-RC4

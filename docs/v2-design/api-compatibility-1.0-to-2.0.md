# API Compatibility: 1.0 → 2.0

## Conclusion

Java 2.0 (`v2_dev`) maintains full backward compatibility with 1.0 (`main`) public API.

## AgentBase (all agent types)

| Method | Status |
|--------|--------|
| `call(List<Msg>)` | Unchanged |
| `call(List<Msg>, Class<?>)` | Unchanged |
| `call(List<Msg>, JsonNode)` | Unchanged |
| `stream(List<Msg>, StreamOptions)` | Unchanged |
| `stream(List<Msg>, StreamOptions, Class<?>)` | Unchanged |
| `stream(List<Msg>, StreamOptions, JsonNode)` | Unchanged |
| `observe(Msg)` | Unchanged |
| `observe(List<Msg>)` | Unchanged |
| `interrupt()` | Unchanged |
| `interrupt(Msg)` | Unchanged |
| `interrupt(InterruptSource)` | Unchanged |
| `getAgentId()` | Unchanged |
| `getName()` | Unchanged |
| `getDescription()` | Unchanged |
| `getRuntimeContext()` | Unchanged |
| `getHooks()` | Unchanged |

## ReActAgent (additional overloads)

| Method | Status |
|--------|--------|
| `call(List<Msg>, RuntimeContext)` | Unchanged |
| `call(List<Msg>, Class<?>, RuntimeContext)` | Unchanged |
| `call(List<Msg>, JsonNode, RuntimeContext)` | Unchanged |
| `stream(List<Msg>, StreamOptions, RuntimeContext)` | Unchanged |
| `stream(List<Msg>, StreamOptions, Class<?>, RuntimeContext)` | Unchanged |
| `stream(List<Msg>, StreamOptions, JsonNode, RuntimeContext)` | Unchanged |
| `getMemory()` / `setMemory(Memory)` | Unchanged |
| `getSysPrompt()` | Unchanged |
| `getModel()` | Unchanged |
| `getMaxIters()` | Unchanged |
| `getGenerateOptions()` | Unchanged |
| `getPlanNotebook()` | Unchanged |
| `saveTo(Session, SessionKey)` | Unchanged |
| `loadIfExists(Session, SessionKey)` | Unchanged |
| `loadFrom(Session, SessionKey)` | Unchanged |
| Builder API | Unchanged (additive only) |

## New methods in 2.0 (additive, non-breaking)

| Method | Description |
|--------|-------------|
| `streamEvents(List<Msg>)` | Fine-grained AgentEvent stream |
| `streamEvents(Msg)` | Convenience single-message variant |
| `getAgentState()` | New state management |
| `getModelConfig()` | Config accessors |
| `getContextConfig()` | Config accessors |
| `getReactConfig()` | Config accessors |
| `getPermissionEngine()` | Permission system |
| `getMiddlewares()` | Middleware list accessor |
| `getSystemPrompt()` | Computed system prompt |
| `close()` | AutoCloseable support |
| `getWorkspaceManager()` | Workspace management |
| `getCompactionHook()` | Context compaction |
| `getSkillRepositories()` | Skill system |

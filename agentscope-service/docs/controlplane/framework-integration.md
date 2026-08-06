# 异构 Agent 框架适配设计

本文档描述 aistio 控制面如何通过 **SDK 嵌入** 和 **Sidecar 代理** 两种模式，适配市面上主流 Agent 框架（Claude Agent SDK、LangChain、Google ADK、OpenClaw 等），实现统一的 Session 观测与管控。

> 本文档为设计参考，供后续实现使用。
> 实施细化（能力映射、混合通道、ASDP/HTTP 协议扩展、FrameworkAdapter 扩展、路线图）：[sdk-design.md](./sdk-design.md)

---

## 1. 核心问题

每种 Agent 框架的 Session 概念、数据格式、存储介质完全不同：

| 框架 | Session 概念 | 存储介质 | 观测接口 |
|------|-------------|---------|---------|
| Claude Agent SDK | SessionStore Protocol + 本地 JSONL | 文件 / 自定义 Store | `list_sessions`, `get_session_messages` |
| LangChain / LangGraph | Checkpointer State Snapshot | SQLite / PG / Redis | `checkpointer.get()`, Callback Handler |
| Google ADK | SessionService Events | Vertex AI / 内存 / SQLite | `SessionService.get_session()`, `list_events()` |
| OpenAI Agents SDK | Thread + Run + Messages | 云端 / 本地 Session Backend | REST API `GET /threads/{id}/messages` |
| OpenClaw | SessionEntry + Transcript Events | SQLite（per-agent DB） | Plugin SDK `listSessionEntries` / Gateway WebSocket RPC |
| Claude Code CLI | 本地 JSONL Transcript | `~/.claude/projects/` 文件 | 文件监听 |

控制面需要一种统一的机制，将这些异构框架的 Session 数据汇聚到运行时 Store（PostgreSQL / memory，见 [storage-design.md](./storage-design.md)）中，同时满足：

- **不侵入**：不影响框架原有的存储路径和行为
- **统一 API**：用户接入方式一致，不随框架变化
- **流量可控**：不因全量实时上报打爆控制面
- **可观测实时状态**：不仅看到事件流，还能看到 Agent 当前的生效 Context

---

## 2. 两种适配模式概览

```
模式 1: SDK 嵌入（推荐，最精确）          模式 2: Sidecar 代理（零侵入，兜底）
─────────────────────────────          ─────────────────────────────────
在 Agent 进程中嵌入 aistio SDK          在 Agent Pod 中部署 Sidecar 容器
通过框架钩子拦截 Session 数据           通过 HTTP 代理 / 文件监听获取数据

适用：能修改 Agent 代码的场景           适用：不能修改代码 / 第三方 Agent
精度：能看到框架内部完整状态             精度：只能看到 LLM 调用层
实时性：秒级                            实时性：取决于轮询/监听间隔
```

### 模式选择决策树

```
框架有 Session 存储接口（SessionStore / SessionService / Checkpointer / Plugin SDK）？
├── 有（Claude Agent SDK、ADK、LangGraph、OpenClaw）
│   ├── 能改代码 / 能装 Plugin？ → SDK 嵌入（最精确）
│   └── 不能改代码？ → API 轮询（Sidecar 定期拉取）
│
└── 没有（Claude Code CLI 等无 Session 接口的工具）
    ├── 能改代码？ → SDK 嵌入（唯一选择）
    ├── 不能改代码且有本地文件？ → 文件监听（Claude Code CLI）
    └── 不能改代码且无文件？ → Sidecar HTTP 代理（兜底）
```

---

## 3. SDK 嵌入模式

### 3.1 统一 Instrument API

参考 OpenTelemetry 的 instrument 模式：**统一入口，可插拔适配**。用户只看到一种 API，不管底层是什么框架。

```python
import aistio

# Claude Agent SDK
from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions
client = ClaudeSDKClient(ClaudeAgentOptions(...))
aistio.instrument(client, control_plane="aistiod:9090", agent_name="my-claude-agent")

# LangChain
from langchain.chains import LLMChain
chain = LLMChain(llm=llm, prompt=prompt)
aistio.instrument(chain, control_plane="aistiod:9090", agent_name="my-langchain-agent")

# Google ADK
from google.adk import SessionService
session_service = SessionService(...)
aistio.instrument(session_service, control_plane="aistiod:9090", agent_name="my-adk-agent")

# OpenClaw（通过 Plugin 嵌入，TypeScript 侧）
# 见 3.5 节 OpenClaw 适配器实现要点
```

`instrument()` 内部自动识别框架类型，选择合适的 `FrameworkAdapter` 进行拦截。用户无需关心拦截方式是 SessionStore 装饰、CallbackHandler 注册还是 Agent Middleware。

### 3.2 统一事件模型

所有框架的 Session 活动都转换为统一的 `SessionEvent`：

```python
@dataclass
class SessionEvent:
    # ─── 必填 ───
    session_id: str
    event_type: str          # "session_start" | "message" | "tool_call" | "tool_result" | "session_end" | "compaction"
    timestamp: float

    # ─── 消息类事件 ───
    role: str | None         # "user" | "assistant" | "system" | "tool"
    content: str | None      # 消息内容摘要（截断，非全文）

    # ─── 工具类事件 ───
    tool_name: str | None
    tool_input: dict | None
    tool_output: str | None

    # ─── 会话指标 ───
    message_count: int | None
    prompt_tokens: int | None
    completion_tokens: int | None
    context_pressure: float | None  # 0.0 - 1.0

    # ─── 框架元信息（自动填充）───
    framework: str | None         # "claude-agent-sdk", "langchain", "adk", ...
    framework_version: str | None
    framework_state: dict | None  # 框架特有的额外状态
```

### 3.3 FrameworkAdapter 接口

每种框架一个适配器，接口统一：

```python
from abc import ABC, abstractmethod
from typing import Any, Callable

class FrameworkAdapter(ABC):
    """所有框架适配器实现同一个接口"""

    @abstractmethod
    def framework_name(self) -> str:
        """框架标识，如 'claude-agent-sdk'"""
        ...

    @abstractmethod
    def can_handle(self, target: Any) -> bool:
        """判断能否处理这个对象（用于自动识别）"""
        ...

    @abstractmethod
    def attach(self, target: Any, emit: Callable[[SessionEvent], None]) -> None:
        """附加到框架对象，Session 事件通过 emit 回调发出"""
        ...

    @abstractmethod
    def detach(self) -> None:
        """移除拦截，恢复原状"""
        ...

    @abstractmethod
    async def extract_context(self, session_id: str) -> ContextSnapshot:
        """提取指定 Session 的当前生效 Context 快照"""
        ...
```

### 3.4 装饰器模式：旁路复制，不影响主路径

SDK 嵌入的 **第一原则**：不替换、不阻塞、不影响框架原有的存储路径。所有拦截都通过 **装饰器 / 观察者** 模式实现旁路复制。

以 Claude Agent SDK 的 SessionStore 为例：

```python
class _InterceptingSessionStore:
    """装饰器：包装原有 SessionStore，旁路复制事件到 aistio。

    - append() 先写原有 store（主路径，必须成功），再旁路发事件
    - load() 直接从原有 store 读，不经过 aistio
    - 旁路上报失败时静默忽略，不影响主路径
    """

    def __init__(self, inner: SessionStore, emit: Callable[[SessionEvent], None]):
        self._inner = inner    # 原有 SessionStore（JSONL / 数据库 / 内存）
        self._emit = emit      # aistio 旁路上报

    async def append(self, key: SessionKey, entries: list[SessionStoreEntry]) -> None:
        # 1. 先走原有路径（必须成功）
        await self._inner.append(key, entries)
        # 2. 旁路发事件到 aistio（失败不影响主路径）
        try:
            for entry in entries:
                self._emit(SessionEvent(
                    session_id=key["session_id"],
                    event_type="message",
                    role=entry.role,
                    content=entry.content[:500],
                    timestamp=time.time(),
                ))
        except Exception:
            pass  # 旁路失败，静默忽略

    async def load(self, key: SessionKey) -> list[SessionStoreEntry] | None:
        return await self._inner.load(key)  # 读路径完全不经过 aistio
```

数据流向：

```
框架原有路径（主路径，不能受影响）     aistio 旁路（观测路径，允许失败）

  SessionStore.append() ──────┐
                              ├──► 同一份数据，两条路径
  SessionStore.load()  ──────┘
         │
         ▼
  ┌──────────────┐         ┌──────────────┐
  │ 原有存储      │         │ aistio SDK   │
  │ (JSONL/DB/   │         │ (旁路上报)    │
  │  内存)       │         │              │
  │ · 必须成功    │         │ · 允许失败    │
  │ · 不感知aistio│         │ · 不阻塞主路径│
  └──────────────┘         └──────────────┘
```

对不支持装饰 Store 的框架（LangChain Callback、OpenClaw Plugin Hook），本身就是在框架的回调链上额外注册一个 Observer，天然就是旁路的。

### 3.5 各框架适配器实现要点

#### Claude Agent SDK

```python
class ClaudeAgentSDKAdapter(FrameworkAdapter):
    def framework_name(self): return "claude-agent-sdk"
    def can_handle(self, target): return isinstance(target, ClaudeSDKClient)

    def attach(self, client: ClaudeSDKClient, emit):
        original_store = client.options.session_store
        client.options.session_store = _InterceptingSessionStore(original_store, emit)

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        entries = await self._store.load(SessionKey(session_id=session_id))
        messages = []
        compaction_summary = None
        for entry in entries:
            if entry.type == "summary":
                compaction_summary = entry.summary
                messages = [ContextMessage(role="system", content=entry.summary, is_compaction=True)]
            elif entry.type in ("user", "assistant"):
                messages.append(ContextMessage(role=entry.role, content=entry.content))
        return ContextSnapshot(
            session_id=session_id, messages=messages,
            is_compacted=compaction_summary is not None,
            compaction_summary=compaction_summary,
            framework="claude-agent-sdk",
        )
```

**关键点**：Compaction 后 Agent 看到的是 `summary + 后续新消息`，不是全部历史。`extract_context()` 重建的是 **生效 Context**，而非完整历史。

#### LangChain / LangGraph

```python
class LangGraphAdapter(FrameworkAdapter):
    def framework_name(self): return "langchain"
    def can_handle(self, target): return isinstance(target, Chain)

    def attach(self, chain: Chain, emit):
        handler = _AistioCallbackHandler(emit)
        chain.callbacks = (chain.callbacks or []) + [handler]

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        state = await self._checkpointer.get(config={"configurable": {"thread_id": session_id}})
        messages = [ContextMessage(role=m.type, content=m.content)
                    for m in state.channel_values.get("messages", [])]
        custom_state = {k: v for k, v in state.channel_values.items() if k != "messages"}
        return ContextSnapshot(
            session_id=session_id, messages=messages,
            framework="langchain", framework_state=custom_state,
        )
```

**关键点**：LangGraph 的 Context 不只是 messages，还有 Graph State 中的自定义 Channel（Agent 的长期记忆、中间计算结果），这些也是影响 Agent 行为的生效 Context。

#### Google ADK

```python
class ADKAdapter(FrameworkAdapter):
    def framework_name(self): return "adk"
    def can_handle(self, target): return isinstance(target, SessionService)

    def attach(self, service: SessionService, emit):
        original_append = service.append_event
        service.append_event = _intercepted_append(original_append, emit)

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        session = await self._session_service.get_session(session_id)
        events = await self._session_service.list_events(session_id)
        messages = [ContextMessage(role=e.role, content=e.content) for e in events]
        return ContextSnapshot(
            session_id=session_id, messages=messages,
            framework="adk",
            framework_state={"agent_state": session.state, "agent_config": session.config},
        )
```

#### OpenClaw

OpenClaw 不是简单的 SDK，而是一个完整的 Agent 平台（Gateway + Plugin 体系）。适配 OpenClaw 有两条路径：

**路径 A：OpenClaw Plugin 模式（推荐，SDK 嵌入的变体）**

开发一个 OpenClaw Plugin，通过 Plugin SDK 的 Session API 获取数据，上报到 aistio 控制面。Plugin 运行在 OpenClaw Gateway 进程内，可以直接访问所有 Session 数据。

```typescript
// TypeScript 实现，作为 OpenClaw Plugin 运行
import {
  listSessionEntries,
  getSessionEntry,
  loadTranscriptEventsSync,
  readTranscriptStatsSync,
} from "openclaw/plugin-sdk/agent-sessions";

class OpenClawAistioPlugin {
  private bridge: AistioBridge;  // aistio 上报客户端（gRPC/HTTP）

  // Plugin SDK 提供的 session 访问 API
  async pollSessions(): Promise<void> {
    const entries = await listSessionEntries({ agentId: this.agentId });
    const snapshots = entries.map(entry => this.toSnapshot(entry));
    await this.bridge.reportSnapshots(snapshots);
  }

  async extractContext(sessionId: string): Promise<ContextSnapshot> {
    const events = await loadTranscriptEventsSync({ sessionId });
    const stats = await readTranscriptStatsSync({ sessionId });
    // 从 transcript events 重建生效 Context
    return this.rebuildContext(events, stats);
  }
}
```

**路径 B：Gateway WebSocket 轮询（零侵入）**

aistio 控制面作为 OpenClaw Gateway 的 operator 客户端，通过 WebSocket RPC 定期拉取 Session 状态。不需要安装任何 Plugin。

```python
# Python 实现，aistio 控制面侧
class OpenClawAdapter(FrameworkAdapter):
    def framework_name(self): return "openclaw"
    def can_handle(self, target): return isinstance(target, str) and "openclaw" in target

    def attach(self, gateway_url: str, emit):
        from gateway_client import GatewayClient
        self._client = GatewayClient(url=gateway_url, token=self._token)
        # 通过 WebSocket RPC 轮询
        # sessions.list → SessionEntry 列表
        # sessions.get → 单个 Session 详情
        # sessions.subscribe → 实时变更事件

    async def extract_context(self, session_id: str) -> ContextSnapshot:
        # 通过 sessions.get + sessions.preview 获取完整 Context
        session = await self._client.call("sessions.get", {"sessionId": session_id})
        preview = await self._client.call("sessions.preview", {"sessionId": session_id})
        return ContextSnapshot(
            session_id=session_id,
            messages=self._parse_transcript(preview),
            framework="openclaw",
            framework_state={
                "status": session.get("status"),
                "model": session.get("model"),
                "modelProvider": session.get("modelProvider"),
                "createdVia": session.get("createdVia"),
            },
        )
```

**OpenClaw Session 数据模型**：

| 数据 | 存储位置 | 访问方式 |
|------|---------|---------|
| Session 元数据 | `session_nodes` 表（per-agent SQLite） | Plugin SDK `listSessionEntries()` / WebSocket `sessions.list` |
| 对话记录 | `transcript_events` 表 | Plugin SDK `loadTranscriptEventsSync()` / WebSocket `sessions.preview` |
| ACP Session | `acp_sessions` 表（全局 state DB） | Plugin SDK / WebSocket `sessions.list` |
| Session 统计 | 内存计算 | Plugin SDK `readTranscriptStatsSync()` |

### 3.6 自动识别入口

```python
_adapters: list[FrameworkAdapter] = [
    ClaudeAgentSDKAdapter(),
    LangGraphAdapter(),
    ADKAdapter(),
    OpenClawAdapter(),
]

def instrument(target: Any, *, control_plane: str, agent_name: str, **kwargs):
    """一行代码接入任何框架"""
    bridge = SessionBridge(control_plane=control_plane, agent_name=agent_name, **kwargs)
    for adapter in _adapters:
        if adapter.can_handle(target):
            bridge.attach(target, framework=adapter.framework_name())
            return bridge
    raise ValueError(f"Unsupported framework: {type(target).__name__}")

def register_adapter(adapter: FrameworkAdapter):
    """注册新的框架适配器"""
    _adapters.append(adapter)
```

新增框架只需实现 `FrameworkAdapter` 接口并调用 `register_adapter()`，不需要修改 `SessionBridge`、`SessionEvent` 或上报逻辑。

---

## 4. Sidecar 代理模式

当不能修改 Agent 代码时，通过 Sidecar 容器在 Pod 层面拦截 Session 数据。

### 4.1 HTTP 代理子模式

Sidecar 作为 HTTP 代理，拦截 Agent 发出的 LLM API 请求/响应，从中重建 Session 数据。

```
┌──────────────┐      ┌──────────────┐      ┌──────────┐
│  Agent       │─────►│  Sidecar     │─────►│  LLM API │
│  Container   │◄─────│  (Bridge)    │◄─────│  (Claude/│
│              │      │              │      │   OpenAI)│
└──────────────┘      └──────┬───────┘      └──────────┘
                             │
                             ▼
                      aistio 控制面
                      (Session 上报)
```

**实现要点**：
- Sidecar 识别不同 LLM 的 API 格式（Anthropic Messages API、OpenAI Chat Completions、Google Gemini）
- 从请求/响应中提取：session ID（从 header / metadata）、message count（messages 数组长度）、token usage（response.usage）
- 将提取的数据转换为 `SessionSnapshot` 上报

**局限**：只能看到 LLM 调用层面，看不到框架内部的 Agent State（LangGraph 图状态、OpenClaw Session 元数据）。

### 4.2 文件监听子模式

监控框架的本地 Transcript 文件变更，解析并上报。

**适用框架**：Claude Code CLI、Codex CLI（有本地 JSONL Transcript 的工具）

**实现**：
- Sidecar 挂载 Agent 的 Transcript 目录
- 使用 fsnotify 监听 JSONL 文件变更
- 增量解析新增行，提取 Session 信息
- 转换为 `SessionSnapshot` 上报

### 4.3 API 轮询子模式

通过框架自身的 API 定期拉取 Session 状态。

**适用框架**：OpenAI Assistants API、Google ADK (Vertex AI Agent Engine)、以及任何暴露了 Session 查询接口的框架

**实现**：Sidecar 作为 HTTP 服务，内部定期调用框架 API（如 `GET /threads/{id}/messages`），将结果转换为 `SessionSnapshot` 上报。

---

## 5. 四级数据上报模型

不做全量实时上报。根据数据粒度和流量成本，分为四个层级：

```
Level 1: 摘要快照（默认，定期批量上报）
         → session_id, phase, message_count, token_usage, context_pressure
         → context_hash, is_compacted, effective_message_count
         ≈ 200 bytes/session，每 10s 批量上报
         500 agent × 5 session × 200B / 10s ≈ 50 KB/s

Level 2: 事件流（可选，调试/审计）
         → 每条消息和 tool call 的摘要
         ≈ 1-2 KB/event，按 session 批量上报（每 5s 或每 20 条）
         只在需要时开启

Level 3: 完整内容（按需拉取，不主动上报）
         → 完整消息内容、tool input/output 全文
         存储在数据面本地
         控制面通过 HTTP 合约 API 按需拉取

Level 4: Context 快照（按需拉取 + 关键变更时通知）
         → 当前 Session 的完整生效 Context
         → system prompt、压缩后 messages、tools、framework state
         → 回答"agent 为什么这样做"
```

### 5.1 Level 1 摘要快照

控制面常态运行只依赖 Level 1。`SessionSnapshot` 在现有字段基础上扩展：

```protobuf
message SessionSnapshot {
  string session_id       = 1;
  string phase            = 2;
  int32  message_count    = 3;
  int64  prompt_tokens    = 4;
  int64  completion_tokens = 5;
  double context_pressure = 6;
  TaskSummary task_summary = 7;
  string team_id          = 8;
  string team_role        = 9;
  string framework         = 10;  // "claude-agent-sdk", "langchain", "adk", ...
  string framework_version = 11;

  // Context 变更检测
  string context_hash      = 12;  // 当前生效 Context 的 SHA-256（前16位）
  bool   is_compacted      = 13;  // 是否经过了 Context 压缩
  int32  effective_message_count = 14;  // 压缩后的生效消息数（≠ 总消息数）
}
```

**`context_hash` 的价值**：控制面不需要拉取完整 Context 就能知道 Context 是否发生了变化。hash 变了 → 拉取详情；hash 没变 → 跳过。

**`effective_message_count` vs `message_count`**：
- `message_count = 50`：历史总共 50 条消息
- `effective_message_count = 5`：压缩后 Agent 实际只看到 5 条
- 差异直接反映 compaction 的效果

### 5.2 Level 4 Context 快照

事件流回答"agent 做了什么"，Context 快照回答"agent 为什么这样做"。

```python
@dataclass
class ContextSnapshot:
    """某一时刻 Agent Session 的完整生效 Context"""
    session_id: str
    timestamp: float

    # 生效的 Context 内容
    system_prompt: str | None           # 当前生效的 system prompt
    messages: list[ContextMessage]       # 压缩后的生效消息列表（不是全部历史）
    tools: list[ToolInfo]               # 当前可用的工具列表

    # 压缩状态
    is_compacted: bool                   # 是否经过了 Context 压缩
    compaction_summary: str | None       # 压缩摘要
    original_message_count: int | None   # 压缩前的消息总数
    compacted_at: float | None           # 最近一次压缩时间

    # Token 状态
    total_tokens: int                    # 当前生效 Context 的总 token 数
    max_tokens: int                      # Context window 上限

    # 框架特定状态
    framework: str
    framework_state: dict | None         # LangGraph graph state、OpenClaw session 元数据等
```

控制面获取 Context 快照的时机：

| 时机 | 触发方式 | 说明 |
|------|---------|------|
| 用户查看 | 控制面 UI 点击 → 实时拉取 | 回答"agent 为什么这样回答" |
| Compaction 事件 | Level 1 快照中 `context_hash` 变化 + `is_compacted=true` | 自动拉取最新压缩后 Context |
| 定期巡检 | 对比 `context_hash` 检测异常 | hash 没变 = Context 稳定 |

### 5.3 各框架 Context 提取实现

#### Claude Agent SDK

```python
def extract_context(self, session_id: str) -> ContextSnapshot:
    # 从 SessionStore 读取当前生效的 entries
    entries = self._store.load(session_id)
    messages = [
        ContextMessage(role=e["role"], content=e.get("content", ""))
        for e in entries
    ]
    return ContextSnapshot(
        session_id=session_id,
        messages=messages,
        framework="claude-agent-sdk",
        framework_state={"session_type": "store-backed"},
    )
```

#### LangGraph

```python
def extract_context(self, thread_id: str) -> ContextSnapshot:
    # 从 Checkpointer 获取最新 graph state
    state = self._checkpointer.get(thread_id)
    messages = [
        ContextMessage(role=m.type, content=m.content)
        for m in state["messages"]
    ]
    return ContextSnapshot(
        session_id=thread_id,
        messages=messages,
        framework="langgraph",
        framework_state={
            "current_node": state.get("__current_node"),
            "graph_name": self._graph.name,
        },
    )
```

#### ADK

```python
def extract_context(self, session_id: str) -> ContextSnapshot:
    # 从 SessionService 获取
    session = self._session_service.get_session(session_id)
    messages = [
        ContextMessage(role=e.role, content=e.content)
        for e in session.events
    ]
    return ContextSnapshot(
        session_id=session_id,
        messages=messages,
        framework="adk",
        framework_state=session.state,
    )
```

#### OpenClaw

```python
def extract_context(self, session_id: str) -> ContextSnapshot:
    # 通过 Gateway WebSocket RPC 获取
    session = await self._client.call("sessions.get", {"sessionId": session_id})
    preview = await self._client.call("sessions.preview", {"sessionId": session_id})
    messages = [
        ContextMessage(role=e["role"], content=e.get("content", ""))
        for e in preview.get("events", [])
    ]
    return ContextSnapshot(
        session_id=session_id,
        messages=messages,
        framework="openclaw",
        framework_state={
            "status": session.get("status"),
            "model": session.get("model"),
            "modelProvider": session.get("modelProvider"),
            "createdVia": session.get("createdVia"),
        },
    )
```

### 5.4 SDK 侧 Context 缓存

SDK 不需要每次都重建完整 Context。维护增量更新的 Context 视图：

```python
class ContextTracker:
    """在 SDK 侧维护 Session 的实时 Context 视图"""

    def __init__(self):
        self._contexts: dict[str, MutableContext] = {}

    def on_event(self, event: SessionEvent):
        ctx = self._contexts.setdefault(event.session_id, MutableContext())
        if event.event_type == "message":
            ctx.messages.append(ContextMessage(role=event.role, content=event.content))
        elif event.event_type == "compaction":
            ctx.messages = [ContextMessage(role="system", content=event.content, is_compaction=True)]
            ctx.is_compacted = True
        ctx.context_hash = self._compute_hash(ctx.messages)

    def get_snapshot(self, session_id: str) -> ContextSnapshot:
        return self._contexts[session_id].to_snapshot()
```

`extract_context()` 直接从内存中的实时视图生成快照，O(1) 复杂度。

---

## 6. HTTP 合约 API 扩展

在现有契约 API（`contract.md`）基础上增加 Context 端点：

```
已有端点：
  GET  /agentscope/info                     # Agent 元信息
  GET  /agentscope/health                   # 健康检查
  GET  /agentscope/sessions                 # Session 列表（Level 1 摘要）
  GET  /agentscope/sessions/{id}/state      # Session 详细状态
  POST /agentscope/sessions/{id}/compress   # 触发压缩
  POST /agentscope/sessions/{id}/terminate  # 终止 Session

新增端点（已实现，详见 contract.md「Capability 门控扩展端点」）：
  GET  /agentscope/sessions/{id}/context    # 获取当前生效 Context 快照（context-query）
  GET  /agentscope/sessions/{id}/messages   # 完整消息历史分页拉取（message-query）
  GET  /agentscope/subagents                # subagent 运行时清单（subagent-inventory）
  GET  /agentscope/workspaces               # workspace 运行时清单（workspace-inventory）
```

---

## 7. 流量估算

| 层级 | 数据量 | 频率 | 500 Agent 总流量 | 适用场景 |
|------|--------|------|-----------------|---------|
| Level 1 摘要快照 | ~200B/session | 10s 批量 | ~50 KB/s | 默认模式，控制面常态运行 |
| Level 2 事件流 | ~1-2KB/event | 5s 批量（可选） | ~500 KB/s | 调试、审计、告警规则 |
| Level 3 完整内容 | ~10-50KB/条 | 按需拉取 | 取决于查询频率 | 用户查看 Session 详情 |
| Level 4 Context 快照 | ~5-20KB/session | 按需 + hash 变更时 | 取决于操作频率 | 观测 Agent 当前生效 Context |

默认只开 Level 1，控制面流量和现有 `session_poller` 基本一致。Level 2/3/4 按需开启，不会成为常态瓶颈。

---

## 8. SDK 项目结构

已实现（仓库内路径 `sdk/python/`）：

```
sdk/python/
├── aistio/
│   ├── __init__.py          # instrument(), register_adapter()
│   ├── bridge.py            # SessionBridge（统一上报引擎）
│   ├── events.py            # SessionEvent / MessageItem 数据模型
│   ├── context.py           # ContextSnapshot 数据模型 + ContextTracker
│   ├── inventory.py         # Subagent / Workspace / InstanceHealth 数据模型
│   ├── proto/               # ASDP Python stubs（asdp_pb2 / asdp_pb2_grpc）
│   ├── transport/
│   │   ├── grpc.py          # ASDP gRPC 双向流客户端（GrpcTransport）
│   │   └── http_server.py   # 内嵌 HTTP 合约服务（ContractHTTPServer）
│   └── adapters/
│       ├── base.py          # FrameworkAdapter 接口
│       ├── registry.py      # 适配器注册表（prototype 模式）
│       ├── claude.py        # Claude Agent SDK（装饰 SessionStore）
│       ├── langchain.py     # LangChain/LangGraph（注册 CallbackHandler + Checkpointer）
│       ├── adk.py           # Google ADK（装饰 SessionService.append_event）
│       ├── openclaw.py      # OpenClaw（Gateway WebSocket RPC）
│       └── openai_agents.py # OpenAI Agents SDK（拦截 session backend）
├── tests/                   # 45 项单元 / 端到端测试
└── pyproject.toml
```

---

## 9. 实施路线图

| 阶段 | 内容 | 覆盖框架 | 状态 |
|------|------|---------|------|
| Phase 1 | Python SDK（Level 1 + Level 4） | Claude Agent SDK、LangChain、ADK、OpenClaw | ✅ 已完成（落地范围超出原规划：另含 Level 2 事件流、Inventory 上报与 OpenAI Agents SDK 适配器；见 [sdk-design.md §9](./sdk-design.md)） |
| Phase 2 | Sidecar HTTP 代理 | 所有通过 HTTP 调用 LLM 的框架（兜底） | 未启动 |
| Phase 3 | 文件监听 | Claude Code CLI、Codex CLI | 未启动 |
| Phase 4 | API 轮询 | OpenAI Assistants 等云端托管框架 | 未启动 |
| Phase 5 | 控制面增强 | Store 查询 API、Context 查询、按 framework/phase/时间查询（写入方见 [sdk-design.md](./sdk-design.md)） | 部分完成（写入方与 REST 读路径已就绪；高级查询未启动） |

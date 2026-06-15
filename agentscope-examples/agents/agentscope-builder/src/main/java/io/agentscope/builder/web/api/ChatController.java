/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.channel.InboundMessage;
import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.builder.runtime.session.SessionAgentManager;
import io.agentscope.builder.runtime.session.SessionEntry;
import io.agentscope.builder.runtime.session.SessionKind;
import io.agentscope.builder.web.audit.ActivityEvent;
import io.agentscope.builder.web.audit.AgentActivityStore;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.identity.IdentityLinkStore;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.usage.UsageStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Chat 控制器 —— 用户与 Agent 对话的 HTTP 入口。
 *
 * <h2>整体请求流程（以 /stream 为例）</h2>
 * <pre>
 * 前端 POST /api/agents/{agentId}/chat/stream
 *   └─ 鉴权（JWT → userId）
 *   └─ 权限检查（用户是否有权运行此 Agent）
 *   └─ 检查是否为斜杠命令（/new, /reset, /identity 等）→ 短路返回
 *   └─ 订阅工具事件总线（实时推送 tool_call / tool_result 到前端）
 *   └─ executeChat() → ChatUiChannel.dispatch() → Gateway.run() → Agent 执行
 *   └─ 合并工具事件 + Agent 文本响应 → SSE 流式输出给前端
 * </pre>
 *
 * <h2>两个关键 key 的区别（重要！）</h2>
 * <ul>
 *   <li><b>gateKey</b>：MsgContext.canonicalKey()，是「路由层面的会话标识」，
 *       由 (channelId, userId, agentId) 组合决定。用于 Gateway 查找/创建 session。</li>
 *   <li><b>sessionKey</b>：SessionEntry.sessionKey()，是「存储层面的会话标识」，
 *       格式为 "agent:{agentId}:main:{uuid}"，由 SessionAgentManager 分配。
 *       用于磁盘文件的定位和 lifecycle 管理。</li>
 * </ul>
 *
 * <p><b>举例说明</b>：用户 bob 对 Agent "assistant" 发消息
 * <pre>
 *   gateKey    = "chatui:bob:assistant"           (路由用，稳定不变)
 *   sessionKey = "agent:assistant:main:a1b2c3d4"  (存储用，UUID 唯一)
 * </pre>
 *
 * <p>每个用户在每个 Agent 下有独立的 session（按 agentId 路径变量隔离）。
 * 斜杠命令 /new、/reset、/identity 和 /dock_&lt;channel&gt; 在进入 Agent 之前就被拦截处理。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 依赖注入 ────────────────────────────────────────────────
    // ChatUiChannel: Web UI 专用的 Channel 实现，负责把消息路由到 HarnessGateway
    private final ChatUiChannel chatUiChannel;
    // SessionAgentManager: 管理所有 MAIN/SUBAGENT session 的创建、查找、重置
    private final SessionAgentManager sessionAgentManager;
    // AgentCatalogService: 解析 agent 定义，包括 gatewayAgentId 的查找
    private final AgentCatalogService catalogService;
    // IdentityLinkStore: 用户的跨平台身份绑定（如 /dock_slack U7F9LZK1A）
    private final IdentityLinkStore identityLinks;
    // UsageStore: 记录每次调用的耗时，用于用量统计
    private final UsageStore usageStore;
    // ToolEventBus: 工具调用事件的发布-订阅总线，让前端能实时看到工具执行
    private final ToolEventBus toolEventBus;
    // AgentAccessGuard: 权限守卫，检查用户对 Agent 是否有 run/edit/fork 权限
    private final AgentAccessGuard guard;
    // AgentActivityStore: 活动日志，记录 RUN_SESSION 等事件
    private final AgentActivityStore activity;

    /**
     * 已记录过 RUN_SESSION 的 session 去重集合。
     *
     * <p>为什么需要这个？因为一个 session 内可能有多次对话（多轮 turn），
     * 但「活动日志」里只应该记录一次 "用户开始了这个 session"。
     * 用 gateKey 作为去重 key（因为此时可能还没有真正的 sessionKey）。
     *
     * <p><b>举例</b>：bob 打开 Agent "assistant" 后连续发了 5 条消息
     * <pre>
     *   turn 1: gateKey="chatui:bob:assistant" → 不在集合中 → 记录 RUN_SESSION → 加入集合
     *   turn 2: gateKey="chatui:bob:assistant" → 已在集合中 → 跳过
     *   turn 3~5: 同上，都跳过
     * </pre>
     * 这样活动日志里只显示 1 条 "bob 运行了 assistant"，而不是 5 条。
     */
    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    public ChatController(
            ChatUiChannel chatUiChannel,
            BuilderBootstrap builderBootstrap,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            ToolEventBus toolEventBus,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.chatUiChannel = chatUiChannel;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.catalogService = catalogService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.toolEventBus = toolEventBus;
        this.guard = guard;
        this.activity = activity;
    }

    /** 前端请求体。{@code sessionKey} 字段保留给未来的路由场景使用。 */
    public record ChatRequest(String message, String sessionKey) {}

    /** 同步模式（/send）的响应体。 */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * /session 接口的响应体。
     *
     * <p>{@code exists} 为 true 表示「用户已经在这个 Agent 下发过消息，session 已创建」。
     * 前端用这个字段来决定页面加载时是否需要拉取历史对话：
     * <pre>
     *   exists=true  → 前端调用 /api/agents/{agentId}/sessions 拉取历史
     *   exists=false → 新用户/新 session，没有历史，跳过拉取
     * </pre>
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /**
     * SSE 流式聊天端点。
     *
     * <h2>前端收到的 SSE 事件类型</h2>
     * 按时间顺序依次为：
     * <ol>
     *   <li>{@code tool_call}  — Agent 调用了工具（如 read_file、write_file）</li>
     *   <li>{@code tool_result} — 工具执行完毕，返回结果</li>
     *   <li>{@code token}       — Agent 的文本回复（当前是整体发送，非逐 token）</li>
     *   <li>{@code done}        — 本轮对话结束，携带 sessionKey 供前端后续使用</li>
     *   <li>{@code error}       — 出错时发送，携带 error 信息</li>
     * </ol>
     *
     * <h2>为什么要用 Flux.merge？</h2>
     * <p>需要同时监听两个数据流：
     * <ol>
     *   <li><b>toolEvents</b>：来自 ToolEventBus，Agent 调用工具时实时产生</li>
     *   <li><b>agentCall</b>：来自 executeChat()，Agent 执行完毕后才产生文本响应</li>
     * </ol>
     * 两个流是异步的——工具调用可能在文本响应之前，必须用 merge 合并才能保证
     * 前端收到完整的时间线。
     *
     * <h2>举例：用户问 "帮我读取 config.json 的内容"</h2>
     * <pre>
     *   时间线 →  前端收到的 SSE 事件：
     *   0ms       (连接建立)
     *   200ms     event:tool_call   data:{"type":"tool_call","toolName":"read_file","toolInput":"..."}
     *   500ms     event:tool_result data:{"type":"tool_result","toolName":"read_file","toolResult":"..."}
     *   800ms     event:token       data:{"type":"token","data":"config.json 的内容是..."}
     *   800ms     event:done        data:{"type":"done","sessionKey":"agent:..."}
     * </pre>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable("agentId") String agentId,
            @RequestBody ChatRequest req,
            Authentication auth) {

        // ── 1. 鉴权 + 权限检查 ──────────────────────────────────
        // 从 JWT Token 中提取 userId（如 "bob"），然后检查 bob 是否有权执行此 Agent
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);

        // 记录活动日志：bob 开始运行 agent "assistant"（同一 session 内不重复记录）
        recordRunSession(def, agentId, userId);

        // ── 2. 斜杠命令处理 ─────────────────────────────────────
        // 如果用户输入的是 /new、/reset、/identity 等命令，不需要调用 Agent，
        // 直接在这里处理并返回结果，避免浪费 LLM 调用。
        //
        // 举例：用户输入 "/reset"
        //   不走 Agent → 直接调用 sessionAgentManager.resetSession()
        //   → 返回 SSE: token("Session reset.") + done
        CommandResult cmd = handleSlashCommand(userId, agentId, req.message());
        if (cmd != null) {
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", Map.of("type", "done")));
        }

        // ── 3. 订阅工具事件总线 ─────────────────────────────────
        // 问题：Agent 调用工具时，前端怎么知道？
        // 答案：ToolEventBus 是一个发布-订阅模式的事件总线。
        //       Agent 执行工具 → ToolEventBus 发布事件 → 这里订阅 → 转成 SSE 发给前端。
        //
        // 但有个「先有鸡还是先有蛋」的问题：
        //   第一次对话时，session 还没创建 → 没有 sessionKey → 无法订阅
        // 解决方案：
        //   - 如果之前已有 session（existingSessionKey != null）→ 正常订阅
        //   - 如果是首次对话（existingSessionKey == null）→ 跳过订阅，先创建 session
        //
        // 举例：bob 第一次和 assistant 对话
        //   首次: existingSessionKey = null → toolEvents = Flux.empty()
        //   第二次及以后: existingSessionKey = "agent:assistant:main:xxx" → 正常订阅
        String gateKey = resolveGateKey(userId, agentId);
        String existingSessionKey = findSessionKeyByGate(userId, gateKey);

        // Sinks.One：一个只能发出一个值就完成的信号，用于通知 toolEvents 流可以结束了。
        // 当 Agent 执行完毕时，done.tryEmitValue(true) 触发 toolEvents 的 takeUntilOther，
        // 优雅关闭工具事件订阅，避免资源泄漏。
        //
        // 加了 10 分钟超时保护：防止 Agent 卡死导致连接永远不会关闭。
        Sinks.One<Boolean> done = Sinks.one();

        Flux<ServerSentEvent<String>> toolEvents =
                existingSessionKey != null
                        ? toolEventBus
                                // 按 sessionKey 订阅 —— 只接收「这个会话」的工具事件
                                .subscribe(existingSessionKey)
                                // 当 Agent 执行完毕（done 信号触发）时停止订阅
                                .takeUntilOther(done.asMono().timeout(Duration.ofMinutes(10)))
                                // 把 ToolEvent 转成前端能识别的 SSE 格式
                                .map(this::toToolFrame)
                                // 订阅出错时静默忽略（不打断主流程）
                                .onErrorResume(ex -> Flux.empty())
                        : Flux.empty();

        // ── 4. 调用 Agent（核心逻辑） ────────────────────────────
        // executeChat 是真正的 Agent 调用入口：
        //   构造 InboundMessage → ChatUiChannel.dispatch() → HarnessGateway.run()
        //     → resolveOrCreateMainSession → HarnessAgent.call() → ReActAgent 循环
        //
        // 返回 Mono<Flux<...>>（外层 Mono 包装内层 Flux）的原因：
        //   executeChat 是异步的（Mono），需要等 Gateway 返回 Agent 执行结果，
        //   拿到结果后才能决定发出什么 SSE 事件。
        //   用 flatMapMany 展开：Mono<Flux<T>> → Flux<T>
        Mono<Flux<ServerSentEvent<String>>> agentCall =
                executeChat(userId, agentId, req.message())
                        .map(
                                reply -> {
                                    // ── 4a. Agent 正常返回 ──
                                    // 提取 Agent 的文本回复内容
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    // 通知 toolEvents 订阅可以停止了
                                    done.tryEmitValue(true);

                                    // 构造 done 帧，携带 sessionKey
                                    // 前端用这个 sessionKey 来获取历史记录、重置 session 等
                                    Map<String, Object> doneFrame = new LinkedHashMap<>();
                                    doneFrame.put("type", "done");
                                    String resolved = findSessionKeyByGate(userId, gateKey);
                                    if (resolved != null) {
                                        doneFrame.put("sessionKey", resolved);
                                    }
                                    // 返回两个 SSE 事件：文本内容 + 完成信号
                                    return Flux.just(
                                            sse("token", Map.of("type", "token", "data", text)),
                                            sse("done", doneFrame));
                                })
                        .onErrorResume(
                                ex -> {
                                    // ── 4b. Agent 执行出错 ──
                                    log.warn(
                                            "Chat stream error: userId={}, agentId={}, error={}",
                                            userId,
                                            agentId,
                                            ex.getMessage());
                                    // 通知工具事件订阅结束（传 false 表示异常终止）
                                    done.tryEmitValue(false);
                                    // 返回 error SSE 事件给前端
                                    return Mono.just(
                                            Flux.just(
                                                    sse(
                                                            "error",
                                                            Map.of(
                                                                    "type",
                                                                    "error",
                                                                    "error",
                                                                    ex.getMessage()))));
                                });

        // ── 5. 合并两个流并返回 ─────────────────────────────────
        // Flux.merge: 同时发出 toolEvents（工具调用实时事件）和 agentCall（文本响应）
        // 前端收到的是一个交织但不乱序的时间线：
        //   tool_call → tool_result → ... → token → done
        //
        // flatMapMany: 把 Mono<Flux<T>> 展开为 Flux<T>
        return Flux.merge(toolEvents, Flux.from(agentCall.flatMapMany(f -> f)));
    }

    /**
     * 获取当前 (userId, agentId) 对应的 sessionKey。
     *
     * <p>这个接口被前端在页面加载时调用，用于判断是否需要拉取历史对话：
     * <ul>
     *   <li>exists=true  → 之前聊过 → 前端去 /api/agents/{agentId}/sessions 拿历史</li>
     *   <li>exists=false → 全新对话 → 前端跳过历史拉取，直接展示空白聊天界面</li>
     * </ul>
     *
     * <p><b>举例</b>：
     * <pre>
     *   bob 是第一次打开 assistant → session 未创建 → {sessionKey: null, exists: false}
     *   bob 发了 "hello" 后再次刷新 → session 已存在 → {sessionKey: "agent:...", exists: true}
     * </pre>
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable("agentId") String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        return Mono.fromCallable(
                () -> {
                    String gateKey = resolveGateKey(userId, agentId);
                    if (gateKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String sessionKey = findSessionKeyByGate(userId, gateKey);
                    if (sessionKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    return new CurrentSessionResponse(sessionKey, true);
                });
    }

    /**
     * 同步聊天（非流式）。
     *
     * <p>与 /stream 的区别：
     * <ul>
     *   <li>/stream: SSE 流式返回，前端可以实时看到 tool_call 和文本输出</li>
     *   <li>/send: 普通 HTTP POST，阻塞等待 Agent 执行完毕，一次性返回文本结果</li>
     * </ul>
     *
     * <p><b>适用场景</b>：API 调用（非浏览器）、自动化脚本、简单的问答场景。
     */
    @PostMapping("/send")
    public Mono<ChatResponse> send(
            @PathVariable("agentId") String agentId,
            @RequestBody ChatRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        recordRunSession(def, agentId, userId);

        CommandResult cmd = handleSlashCommand(userId, agentId, req.message());
        if (cmd != null) {
            return Mono.just(new ChatResponse(cmd.message, null));
        }

        String gateKey = resolveGateKey(userId, agentId);
        return executeChat(userId, agentId, req.message())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            String sessionKey = findSessionKeyByGate(userId, gateKey);
                            return new ChatResponse(text, sessionKey);
                        });
    }

    // ========================================================================
    //  Internal helpers（内部辅助方法）
    //  下面的方法都是 private，只在 ChatController 内部使用。
    //  按功能分为：SSE 转换、活动记录、key 解析、斜杠命令、核心派发
    // ========================================================================

    // ── SSE 转换 ────────────────────────────────────────────────

    /**
     * 将 ToolEventBus 的 ToolEvent 转为 SSE 格式。
     *
     * <p>ToolEvent 有两大类：
     * <ul>
     *   <li>TOOL_CALL: Agent 决定调用工具 → 前端显示 "正在调用 xxx..."</li>
     *   <li>TOOL_RESULT: 工具执行完成 → 前端显示执行结果</li>
     * </ul>
     *
     * <p><b>举例</b>：
     * <pre>
     *   输入: ToolEvent(type="TOOL_CALL", toolName="read_file", data={path:"config.json"})
     *   输出: SSE event=tool_call  data={"type":"tool_call","toolName":"read_file","toolInput":"{\"path\":\"config.json\"}"}
     *
     *   输入: ToolEvent(type="TOOL_RESULT", toolName="read_file", data={output:"file content"})
     *   输出: SSE event=tool_result data={"type":"tool_result","toolName":"read_file","toolResult":"\"file content\""}
     * </pre>
     */
    private ServerSentEvent<String> toToolFrame(ToolEventBus.ToolEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean isResult = "TOOL_RESULT".equalsIgnoreCase(e.eventType());
        data.put("type", isResult ? "tool_result" : "tool_call");
        data.put("toolName", e.toolName());
        if (e.data() != null) {
            String payload;
            try {
                payload = MAPPER.writeValueAsString(e.data());
            } catch (JsonProcessingException ex) {
                payload = String.valueOf(e.data());
            }
            data.put(isResult ? "toolResult" : "toolInput", payload);
        }
        return sse(isResult ? "tool_result" : "tool_call", data);
    }

    /**
     * 构造一条 SSE 事件。
     *
     * <p>SSE（Server-Sent Events）格式：
     * <pre>
     *   event: tool_call
     *   data: {"type":"tool_call","toolName":"write_file",...}
     *
     *   (空行)
     * </pre>
     *
     * @param eventType SSE 事件名（前端通过 event.type 读取）
     * @param data      事件携带的 JSON 数据
     */
    private ServerSentEvent<String> sse(String eventType, Object data) {
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"" + eventType + "\"}";
        }
        return ServerSentEvent.<String>builder().event(eventType).data(json).build();
    }

    // ── 活动记录 ────────────────────────────────────────────────

    /**
     * 记录 RUN_SESSION 活动事件（整个 session 只记录一次）。
     *
     * <p>去重逻辑：
     * <pre>
     *   bob 对 assistant 第一次发消息 → startedSessions 不包含 gateKey → 记录 RUN_SESSION
     *   bob 对 assistant 第二次发消息 → startedSessions 已包含 gateKey → 跳过
     *   bob 执行 /reset → startedSessions 移除 gateKey → 下次发消息会重新记录
     * </pre>
     *
     * <p>目的是让活动日志显示 «bob ran assistant 3 sessions» 而不是 «bob sent 50 messages»。
     */
    private void recordRunSession(AgentDefinition def, String agentId, String userId) {
        if (def == null || def.ownerId() == null) {
            // 全局 Agent（没有 owner）不记录活动日志
            return;
        }
        // 用 gateKey 去重而不是 sessionKey：
        // 因为第一次发消息时 session 还没创建，sessionKey 还不存在
        String dedupeKey = resolveGateKey(userId, agentId);
        if (dedupeKey == null) return;
        if (!startedSessions.add(dedupeKey)) return; // 已存在，跳过
        activity.record(
                def.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.RUN_SESSION,
                dedupeKey,
                null);
    }

    // ── Key 解析（gateKey ↔ sessionKey）──────────────────────────

    /**
     * 计算 (userId, agentId) 对应的网关路由 key（gateKey）。
     *
     * <p>这个 key 是 MsgContext.canonicalKey() 的返回值，
     * 是「路由层面的标识」，不是「存储层面的 sessionKey」。
     *
     * <h2>为什么要通过 ChatUiChannel.previewRoute 来计算？</h2>
     * <p>因为 gateKey 的计算涉及 ChannelRouter 的路由逻辑（dmScope、binding 匹配等），
     * 不能简单地用 "userId+agentId" 拼接。必须通过真正的 Channel 路由来算，
     * 保证这里得出的 gateKey 和后面 executeChat 时 Gateway 实际使用的 gateKey 一致。
     *
     * <p><b>举例</b>：
     * <pre>
     *   输入: userId="bob", agentId="assistant"
     *   过程:
     *     1. catalogService 解析 agentId → gatewayAgentId="assistant"
     *     2. 构造 InboundMessage.dmFor(chatui, "bob", "assistant", [])
     *     3. chatUiChannel.previewRoute(probe) 模拟路由
     *     4. 返回 canonicalKey = "chatui:bob:assistant"
     *   输出: "chatui:bob:assistant"  ← 这就是 gateKey
     * </pre>
     */
    private String resolveGateKey(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            // 将 Catalog 层的逻辑 agentId 转换为 Gateway 层的实际注册 ID
            // 为什么需要这一步？因为 agentId 只是逻辑标识：
            //   - 全局 Agent：agentId == gatewayAgentId（如 "assistant" → "assistant"）
            //   - UCA：需要加 owner 前缀（如 "my-agent" → "uca-bob-my-agent"）
            // 后续路由和 Gateway 查找都依赖这个实际的 gatewayAgentId
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            // 构造一个空消息的 InboundMessage，仅用于「预览路由」——不会真的发出去
            InboundMessage probe =
                    InboundMessage.dmFor(
                            ChatUiChannel.CHANNEL_ID, userId, gatewayAgentId, List.of());
            return chatUiChannel.previewRoute(probe).context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据 gateKey 找到对应的「存储层面 sessionKey」。
     *
     * <p>遍历所有 MAIN session，找到 gateKey 匹配的那个。
     * 如果没有找到（首次对话）返回 null。
     *
     * <h2>为什么需要这个方法？</h2>
     * <p>gateKey 是路由用的（稳定不变），sessionKey 是存储用的（每次 /reset 会变）。
     * 从 gateKey 翻译到 sessionKey 是必要的，因为 ToolEventBus 的订阅、
     * SessionAgentManager 的操作都需要真正的 sessionKey。
     *
     * <p><b>举例</b>：
     * <pre>
     *   gateKey = "chatui:bob:assistant"
     *   遍历所有 session → 找到:
     *     SessionEntry{
     *       sessionKey="agent:assistant:main:a1b2c3d4",
     *       gateKey="chatui:bob:assistant",
     *       userId="bob"
     *     }
     *   返回: "agent:assistant:main:a1b2c3d4"
     * </pre>
     */
    private String findSessionKeyByGate(String userId, String gateKey) {
        if (gateKey == null) return null;
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            // 只看 MAIN 类型（SUBAGENT 是子 Agent 内部用的）
            if (e.kind() != SessionKind.MAIN) continue;
            // gateKey 必须匹配
            if (!Objects.equals(gateKey, e.gateKey())) continue;
            // userId 也必须匹配（多租户隔离）
            if (userId != null && !Objects.equals(userId, e.userId())) continue;
            return e.sessionKey();
        }
        return null;
    }

    // ── 斜杠命令 ────────────────────────────────────────────────

    /**
     * 处理斜杠命令。如果是普通消息返回 null，让调用方走正常 Agent 流程。
     *
     * <h2>支持的斜杠命令</h2>
     * <table>
     *   <tr><th>命令</th><th>作用</th><th>举例</th></tr>
     *   <tr><td>/new 或 /reset</td><td>重置当前 session，清除对话历史</td><td>/reset</td></tr>
     *   <tr><td>/identity</td><td>查看用户绑定的跨平台身份</td><td>/identity</td></tr>
     *   <tr><td>/dock_&lt;channel&gt; &lt;id&gt;</td><td>绑定跨平台身份</td><td>/dock_slack U7F9LZK1A</td></tr>
     * </table>
     *
     * <p><b>/reset 为什么不通过 Agent 执行？</b>
     * <p>因为 reset 是「清除历史」操作，如果让 Agent 来执行，Agent 要先收到消息→推理→调工具，
     * 但此时 session 里的历史消息还在，reset 后下一轮还是带着旧历史。直接在这里重置 session
     * 更干净、更快、不消耗 LLM token。
     *
     * @return 命令的响应文本，如果不是命令则返回 null
     */
    private CommandResult handleSlashCommand(String userId, String agentId, String message) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null; // 不是斜杠命令
        String[] parts = m.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/new":
            case "/reset":
                {
                    String gateKey = resolveGateKey(userId, agentId);
                    if (gateKey == null) {
                        return new CommandResult("No active session to reset.");
                    }
                    String sessionKey = findSessionKeyByGate(userId, gateKey);
                    if (sessionKey == null) {
                        // session 还没创建 —— 从用户角度看，已经是「全新」的了
                        // 清除去重标记，这样下次发消息时会记录新的 RUN_SESSION
                        //
                        // 举例：bob 打开 assistant 后直接输入 /reset（还没发过任何消息）
                        //   → session 不存在 → 告诉用户 "No active session yet"
                        //   → 清除 startedSessions 中的 gateKey
                        //   → 下次发 "hello" 时会记录 RUN_SESSION
                        startedSessions.remove(gateKey);
                        return new CommandResult(
                                "No active session yet — your next message will start a fresh"
                                        + " conversation.");
                    }
                    // 真正执行 reset：分配新的 sessionId + sessionFilePath
                    // 注意：旧的 session 文件保留在磁盘（由 maintenance 任务异步清理）
                    boolean ok = sessionAgentManager.resetSession(sessionKey);
                    // 清除去重标记，下次发消息时记录 RUN_SESSION
                    startedSessions.remove(gateKey);
                    return new CommandResult(
                            ok
                                    ? "Session reset. Conversation history cleared; the next"
                                            + " message starts a fresh turn."
                                    : "No matching session found for reset.");
                }

            case "/identity":
                {
                    // 查看用户绑定的跨平台身份
                    // 举例：用户绑定了 Slack U7F9LZK1A 和 GitHub bob-dev
                    //   返回: "Your identity links:\n  - slack -> U7F9LZK1A\n  - github -> bob-dev"
                    Map<String, String> links = identityLinks.linksFor(userId);
                    if (links.isEmpty()) {
                        return new CommandResult(
                                "No identity links yet. Use `/dock_<channel> <externalId>` to add"
                                        + " one — e.g. `/dock_slack U7F9LZK1A`.");
                    }
                    StringBuilder sb = new StringBuilder("Your identity links:\n");
                    links.forEach(
                            (ch, id) ->
                                    sb.append("  - ")
                                            .append(ch)
                                            .append(" -> ")
                                            .append(id)
                                            .append('\n'));
                    return new CommandResult(sb.toString());
                }

            default:
                // 匹配 /dock_<channel> 命令
                // 举例：/dock_slack U7F9LZK1A → 把 bob 的 Web 身份和 Slack U7F9LZK1A 绑定
                // 这样通过 Slack 发消息到 Agent 时，Agent 能识别出"这个人就是 bob"
                if (cmd.startsWith("/dock_")) {
                    String channel = cmd.substring("/dock_".length());
                    if (channel.isBlank() || arg.isBlank()) {
                        return new CommandResult(
                                "Usage: `/dock_<channel> <externalId>` — e.g."
                                        + " `/dock_slack U7F9LZK1A`.");
                    }
                    identityLinks.link(userId, channel, arg);
                    return new CommandResult(
                            "Linked your identity on `" + channel + "` to `" + arg + "`.");
                }
                // 不认识的命令 → 返回 null，让 Agent 来处理（可能有自定义的 tool 叫这个名字）
                return null;
        }
    }

    /** 斜杠命令结果的内部载体。就是一个包装了 String 的 record。 */
    private record CommandResult(String message) {}

    // ── 核心派发 ────────────────────────────────────────────────

    /**
     * 核心消息派发逻辑 —— 所有聊天请求的必经之路。
     *
     * <h2>完整调用链</h2>
     * <pre>
     *   executeChat(userId, agentId, message)
     *     ├── 1. 构造 Msg("hello")→ 包装为 InboundMessage
     *     │      如果用户指定了 agentId → dmFor（preferredAgentId 设为指定值）
     *     │      如果没指定 agentId    → dm（纯文本，让 ChannelRouter 决定路由）
     *     │
     *     ├── 2. chatUiChannel.dispatch(inbound)
     *     │      ├── ChannelRouter.resolveRoute()   ← 解析目标 Agent + 构造 MsgContext
     *     │      └── HarnessGateway.run()           ← 查找/创建 session → 调用 Agent
     *     │            ├── resolveOrCreateMainSession()  ← gateKey → sessionKey
     *     │            ├── 设置 RuntimeContext(session, sessionKey)
     *     │            └── HarnessAgent.call(userMsg)
     *     │                  └── ReActAgent.call()
     *     │                        ├── memory.loadFrom()          ← 恢复对话历史
     *     │                        ├── ReAct 循环（推理→工具→观察...）
     *     │                        └── SessionPersistenceHook     ← 自动保存 memory
     *     │
     *     └── 3. doOnSuccess: 记录本次调用的耗时到 UsageStore
     * </pre>
     *
     * <h2>为什么一定要走 ChannelRouter？</h2>
     * <p>即使 URL 已经指定了 agentId（如 /api/agents/assistant/chat/stream），
     * 仍然走 ChannelRouter，原因是：
     * <ul>
     *   <li>ChannelRouter 负责计算 sessionScope（dmScope），决定同一个用户的多次对话
     *       是共享一个 session 还是各自独立</li>
     *   <li>ChannelRouter 负责计算 outbound 地址（Agent 回复消息发到哪里）</li>
     *   <li>如果跳过 Router 直接调 Agent，这些设置都会丢失</li>
     * </ul>
     *
     * @param userId  当前登录用户的 ID（从 JWT 中提取）
     * @param agentId URL 路径中指定的 Agent ID
     * @param message 用户输入的文本
     * @return Agent 执行完毕后返回的 Msg（包含文本回复）
     */
    private Mono<Msg> executeChat(String userId, String agentId, String message) {
        // 构造 AgentScope 的消息对象
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(message).build();
        long startMs = System.currentTimeMillis();

        // 构造 InboundMessage：把 Msg 包装为 Channel 能理解的格式
        // dmFor vs dm 的区别：
        //   dmFor: 明确指定目标 Agent → ChannelRouter 的 "explicit" tier 直接匹配
        //   dm:    不指定 Agent  → ChannelRouter 按 binding 优先级匹配
        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.of(userMsg));
        } else {
            // 将 Catalog 层的逻辑 agentId 转换为 Gateway 层的实际注册 ID
            // 为什么需要这一步？因为 agentId 只是逻辑标识：
            //   - 全局 Agent：agentId == gatewayAgentId（如 "assistant" → "assistant"）
            //   - UCA：需要加 owner 前缀（如 "my-agent" → "uca-bob-my-agent"）
            // 后续路由和 Gateway 查找都依赖这个实际的 gatewayAgentId
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            inbound =
                    InboundMessage.dmFor(
                            ChatUiChannel.CHANNEL_ID, userId, gatewayAgentId, List.of(userMsg));
        }

        // 通过 ChatUiChannel 派发 → Gateway → Agent
        Mono<Msg> call = chatUiChannel.dispatch(inbound);

        // Agent 执行成功后，记录本次调用的耗时（用于用量统计）
        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return call.doOnSuccess(
                reply ->
                        usageStore.record(
                                userId, recordedAgentId, System.currentTimeMillis() - startMs));
    }
}

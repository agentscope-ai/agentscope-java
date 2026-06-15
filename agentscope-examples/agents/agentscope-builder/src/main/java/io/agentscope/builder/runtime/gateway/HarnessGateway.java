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
package io.agentscope.builder.runtime.gateway;

import io.agentscope.builder.runtime.channel.OutboundAddress;
import io.agentscope.builder.runtime.session.PendingCompletion;
import io.agentscope.builder.runtime.session.SessionAgentManager;
import io.agentscope.builder.runtime.session.SessionConstants;
import io.agentscope.builder.runtime.session.SessionEntry;
import io.agentscope.builder.runtime.session.SessionFreshness;
import io.agentscope.builder.runtime.session.SessionFreshnessEvaluator;
import io.agentscope.builder.runtime.session.SessionKind;
import io.agentscope.builder.runtime.session.SessionResetPolicy;
import io.agentscope.builder.runtime.session.SessionView;
import io.agentscope.builder.runtime.session.SpawnResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 网关（Gateway）—— builder 项目的核心路由枢纽。
 *
 * <p>一句话职责：把「谁能访问哪个 Agent + 用哪个 session + 消息发到哪里」串起来。
 *
 * <h2>核心数据流</h2>
 * <pre>
 *   外部消息入口（飞书/GitHub/Web/钉钉）
 *       │
 *       ▼
 *   CallbackController → Channel.mapper() → InboundMessage（统一格式）
 *       │
 *       ▼
 *   ChatUiChannel.dispatch() / FeishuChannel.dispatch()
 *       │
 *       ▼
 *   ChannelRouter.resolve()  ← 8 级 binding 匹配，找到目标 agentId
 *       │
 *       ▼
 *   ChannelRouter.buildContext()  ← 构造 MsgContext（决定 session 隔离粒度）
 *       │
 *       ▼
 *   ★ HarnessGateway.run()  ← 就是这里，路由 + session 管理 + Agent 调用
 *       │
 *       ▼
 *   HarnessAgent.call() → ReActAgent 执行
 *       │
 *       ▼
 *   Agent 响应 → ChannelManager.deliver() → 飞书/钉钉/GitHub/Web 推送回复
 * </pre>
 *
 * <h2>关键设计</h2>
 * <h3>1. Session 路由</h3>
 * <p>每条入站消息带一个 {@link MsgContext#canonicalKey()}（由 channel + group + room + threadId
 * 组合而成），同一个 canonicalKey 对应同一个 MAIN session。
 * 首次访问时自动创建，后续复用，实现「同一用户的同一段对话，历史连续」。
 *
 * <h3>2. 四张路由映射表</h3>
 * <pre>
 * ┌───────────────────────────────────┬──────────────────────────────────────────────────┐
 * │ 映射表                             │ 作用                                             │
 * ├───────────────────────────────────┼──────────────────────────────────────────────────┤
 * │ contextKeyToSessionKey            │ gateKey → sessionKey（核心！决定对话复用哪个 session）│
 * │ sessionKeyToGateKey               │ 反向查找：子 Agent 完成后找到父 session 的 gate    │
 * │ sessionKeyToAgentId               │ session → agentId（announce 时路由回同一个 Agent）  │
 * │ lastRouteBySessionKey             │ session → 最近一次出站地址（主动推送消息到哪里）      │
 * └───────────────────────────────────┴──────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>3. per-key 串行化（SessionTurnGate）</h3>
 * <p>同一个 canonicalKey 同一时刻只允许一个 Agent call 在执行（通过 {@link SessionTurnGate} 公平锁），
 * 防止同一用户的并发请求交错执行导致状态混乱。
 *
 * <h3>4. 子 Agent announce（完成通知）</h3>
 * <p>当子 Agent 执行完毕，通过 {@link #tryDispatchAnnounce} 把结果回送给父 Agent，
 * 再触发一轮新的 agent call，最终通过 ChannelManager 推送到原渠道。
 *
 * @see MsgContext          路由上下文（canonicalKey = session 路由 key）
 * @see ChannelRouter       消息入站路由（8 级 binding 匹配）
 * @see SessionAgentManager session 生命周期管理（创建/销毁/持久化）
 * @see SessionTurnGate     per-key 并发控制（同一 key 串行执行）
 */
public final class HarnessGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private final SessionAgentManager sessionAgentManager;
    private final ChannelManager channelManager;

    /**
     * 主 Agent 实例（默认 Agent），通过 {@link #bindMainAgent} 设置。
     *
     * <p>当请求没有显式指定 agentId，或指定的 agentId 在 agentRegistry 中不存在时，
     * fallback 到这个主 Agent。使用 AtomicReference 保证线程安全的读写。
     */
    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();

    /**
     * agentId → HarnessAgent 注册表。
     *
     * <p>通过 {@link #bindMainAgent} 注册主 Agent，通过 {@link #registerAgent} 注册其他 Agent。
     * ChannelRouter 路由完成后，通过 ctx.extra.get("agentId") 查此表找到目标 Agent 实例。
     */
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();

    /**
     * 最近一次 {@link #bindMainAgent} 调用时的 agentId。
     * 作为最后的 fallback，当 mainAgent 引用为空时通过此 ID 从 agentRegistry 取。
     */
    private volatile String defaultAgentId = null;

    /**
     * 核心路由表 ①：gateKey → sessionKey。
     *
     * <p>gateKey = MsgContext.canonicalKey()，如 "chatui|r:bob"、"feishu-rd|g:org-a|r:oc_123"。
     * sessionKey = SessionAgentManager 中的 MAIN session 唯一标识。
     *
     * <p>作用：同一个 canonicalKey 的请求，复用同一个 MAIN session，
     * 确保 bob 连续发的两条消息会进同一个对话历史。
     *
     * <p>典型例子：
     * <pre>
     *   第一次请求: "chatui|r:bob" → compute() 发现 key 不存在 → 创建 sessionKey = "sess_001"
     *   第二次请求: "chatui|r:bob" → compute() 发现已存在 → isSessionFresh=true → 复用 "sess_001"
     *   alice 请求: "chatui|r:alice" → compute() 发现 key 不存在 → 创建 sessionKey = "sess_002"
     * </pre>
     */
    private final ConcurrentHashMap<String, String> contextKeyToSessionKey =
            new ConcurrentHashMap<>();

    /**
     * 核心路由表 ②：sessionKey → gateKey（反向索引）。
     *
     * <p>子 Agent 完成任务后，需要找到父 session 对应的 gateKey，才能在正确的渠道 gate 上触发
     * announce（完成通知），所以需要这个反向映射。
     *
     * <p>使用场景：{@link #tryDispatchAnnounce} 通过 requesterSessionKey 找到 gateKey，
     * 再用 gateKey 找到 SessionTurnGate，在正确的渠道上串行执行 announce agent call。
     */
    private final ConcurrentHashMap<String, String> sessionKeyToGateKey = new ConcurrentHashMap<>();

    /**
     * 核心路由表 ③：sessionKey → agentId。
     *
     * <p>子 Agent announce 回来时，需要知道用哪个 Agent 来处理完成通知。
     * 通过此表把 session 映射回创建它的 agentId，保证 announce 由同一个 Agent 处理。
     */
    private final ConcurrentHashMap<String, String> sessionKeyToAgentId = new ConcurrentHashMap<>();

    /**
     * 核心路由表 ④：sessionKey → 最近一次出站地址（OutboundAddress）。
     *
     * <p>每条入站消息的 {@link #run()} 调用都会更新此表，记录「这次消息是从哪个渠道/哪个群来的」。
     * 子 Agent 完成后，通过此表把结果推送到正确的渠道和会话。
     *
     * <p>例子：bob 在飞书群 oc_123 发消息触发子 Agent → 子 Agent 完成后
     * 通过 lastRouteBySessionKey 知道应该把回复发到 feishu-rd:oc_123。
     */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySessionKey =
            new ConcurrentHashMap<>();

    /**
     * 文件系统用户身份解析器（用于共享 Agent 场景）。
     *
     * <p>问题背景：
     * <pre>
     *   bob 访问共享 Agent "deploy-bot"，如果直接用 bob 的身份做文件系统命名空间，
     *   bob 的对话历史会混入 deploy-bot 的文件系统，干扰 deploy-bot 所有者（owner）的数据。
     * </pre>
     * 解决方案：安装一个解析器，把 (callerUserId, agentId) → ownerUserId，
     * 让所有用户共享同一个文件系统 namespace，但各自保持独立的对话 session。
     *
     * <p>关键区分：
     * <pre>
     *   session 路由 key：从 MsgContext.userId() 派生 → 每个用户独立 session
     *   文件系统 userId：从 fsUserIdResolver 解析 → 可能映射到同一个 owner
     * </pre>
     *
     * <p>默认行为：直接返回 callerUserId，不做映射（无共享 Agent 需求）。
     */
    private volatile BiFunction<String, String, String> fsUserIdResolver =
            (callerUserId, agentId) -> callerUserId;

    /**
     * per-key 并发锁，确保同一个 canonicalKey 同一时刻只执行一个 agent call。
     *
     * <p>场景：同一个用户在同一渠道同一会话中连续发了两条消息，
     * 第一条还没处理完第二条就到了，通过此锁串行化执行，防止对话状态混乱。
     */
    private final SessionTurnGate sessionTurnGate = new SessionTurnGate();

    private HarnessGateway(SessionAgentManager sessionAgentManager, ChannelManager channelManager) {
        this.sessionAgentManager = Objects.requireNonNull(sessionAgentManager);
        this.channelManager = channelManager;
    }

    /**
     * Creates a gateway wired to the given {@link SessionAgentManager} and {@link ChannelManager}.
     * Sets up the announce dispatcher and spawn interceptor on the session manager, and restores
     * persisted MAIN session routing maps from the session store (if available).
     *
     * @param sessionAgentManager session and agent lifecycle manager
     * @param channelManager channel registry for outbound delivery; may be null if outbound
     *     delivery is not needed
     */
    public static HarnessGateway create(
            SessionAgentManager sessionAgentManager, ChannelManager channelManager) {
        Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        HarnessGateway gateway = new HarnessGateway(sessionAgentManager, channelManager);
        sessionAgentManager.setAnnounceDispatcher(gateway::tryDispatchAnnounce);
        sessionAgentManager.setSpawnInterceptor(gateway::onSpawn);
        gateway.restorePersistedMainSessions();
        return gateway;
    }

    /** Creates a gateway without a channel manager (no outbound delivery support). */
    public static HarnessGateway create(SessionAgentManager sessionAgentManager) {
        return create(sessionAgentManager, null);
    }

    /** The session agent manager used by this gateway. */
    public SessionAgentManager sessionAgentManager() {
        return sessionAgentManager;
    }

    /** The channel manager for outbound delivery, or null if not configured. */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 从持久化存储中恢复 MAIN session 的路由映射表。
     *
     * <p>在 Gateway 创建时调用，将上一次运行中已有的 MAIN session 恢复到内存映射表中，
     * 确保应用重启后已有对话能够继续。
     *
     * <p>过滤规则：
     * <pre>
     *   ① 只恢复 MAIN 类型（跳过 SUBAGENT）
     *   ② gateKey 非空的（必须能确定路由 key）
     *   ③ 符合 SessionResetPolicy 的新鲜度要求的（过期的跳过）
     * </pre>
     */
    private void restorePersistedMainSessions() {
        SessionResetPolicy policy = sessionAgentManager.getConfig().sessionResetPolicy();
        long now = System.currentTimeMillis();
        int restored = 0;
        for (SessionEntry entry : sessionAgentManager.allSessions()) {
            if (entry.kind() != SessionKind.MAIN) {
                continue;
            }
            String gateKey = entry.gateKey();
            if (gateKey == null || gateKey.isBlank()) {
                continue;
            }
            if (policy.mode() != SessionResetPolicy.ResetMode.NEVER) {
                SessionFreshness freshness =
                        SessionFreshnessEvaluator.evaluate(entry.lastActivityMs(), now, policy);
                if (!freshness.fresh()) {
                    log.debug(
                            "Skipping stale persisted session: sessionKey={}, gateKey={}",
                            entry.sessionKey(),
                            gateKey);
                    continue;
                }
            }
            contextKeyToSessionKey.putIfAbsent(gateKey, entry.sessionKey());
            sessionKeyToGateKey.putIfAbsent(entry.sessionKey(), gateKey);
            sessionKeyToAgentId.putIfAbsent(entry.sessionKey(), entry.agentId());
            restored++;
        }
        if (restored > 0) {
            log.info("Restored {} persisted MAIN session routing maps", restored);
        }
    }

    /**
     * Binds the primary harness agent. Also registers it under its {@link
     * HarnessAgent#getAgentId()} for routing.
     */
    @Override
    public void bindMainAgent(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        mainAgent.set(agent);
        String id = resolveAgentId(agent);
        agentRegistry.put(id, agent);
        defaultAgentId = id;
    }

    @Override
    public void registerAgent(String agentId, HarnessAgent agent) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(agent, "agent");
        agentRegistry.put(agentId, agent);
    }

    /**
     * Returns the {@link HarnessAgent} registered under {@code gatewayId} (typically either a
     * global agent id or a {@code uca-{userId}-{agentId}} namespaced id), or {@code null} if no
     * agent is currently registered for that id. Exposed so platform controllers can introspect
     * a built-and-registered agent (e.g. enumerate its skill repositories) without going through
     * the routing path.
     */
    public HarnessAgent findAgent(String gatewayId) {
        if (gatewayId == null) return null;
        return agentRegistry.get(gatewayId);
    }

    /**
     * Installs the resolver that maps {@code (callerUserId, agentId)} to the user id the gateway
     * should attach to outgoing {@link RuntimeContext#getUserId()}. See the field-level javadoc
     * on {@link #fsUserIdResolver} for the rationale; passing {@code null} restores the default
     * identity resolver.
     */
    public void setFilesystemUserIdResolver(BiFunction<String, String, String> resolver) {
        this.fsUserIdResolver =
                resolver != null ? resolver : (callerUserId, agentId) -> callerUserId;
    }

    /**
     * Applies {@link #fsUserIdResolver} defensively: any null/blank/exception return falls back
     * to {@code callerUserId} so a misbehaving resolver cannot break the chat path.
     */
    private String resolveFsUserId(String callerUserId, String agentId) {
        BiFunction<String, String, String> resolver = this.fsUserIdResolver;
        if (resolver == null) {
            return callerUserId;
        }
        try {
            String resolved = resolver.apply(callerUserId, agentId);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        } catch (Exception e) {
            log.warn(
                    "fsUserIdResolver failed for caller={} agentId={}: {}; falling back to caller",
                    callerUserId,
                    agentId,
                    e.getMessage());
        }
        return callerUserId;
    }

    /**
     * Direct or channel-originated turn. Resolves or creates a MAIN session keyed by {@link
     * MsgContext#canonicalKey()}, routes to the appropriate agent, and runs the turn under the
     * per-key {@link SessionTurnGate}.
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    /**
     * 【核心方法】入口 turn 处理 —— 处理一条来自外部渠道的入站消息。
     *
     * <p>完整调用链：
     * <pre>
     *   Channel.dispatch(inboundMessage)
     *     → ChannelRouter.resolve(inbound)          // 8 级 binding 匹配，找到 agentId
     *     → ChannelRouter.buildContext(inbound, dmScope, agentId, senderId)  // 构造 MsgContext
     *     → ★ HarnessGateway.run(ctx, messages, outboundAddress)  ← 就是这里
     *         → resolveAgent(requestedAgentId)      // 从注册表找 Agent 实例
     *         → resolveOrCreateMainSession(gateKey) // 复用或创建 MAIN session
     *         → withGatedTurn(gateKey, ha.call())   // per-key 串行锁 + Agent 执行
     * </pre>
     *
     * <h3>MsgContext 的 key 维度由 ChannelRouter.buildDmContext() 控制</h3>
     * <pre>
     *   MsgContext.canonicalKey() = channel [|g:group] [|r:room] [|t:threadId]
     *
     *   群聊:  "feishu-rd|g:org-a|r:oc_123"      ← group + room
     *   DM:    "chatui|r:bob"                    ← room（DmScope = PER_PEER）
     *   Thread: "discord|r:ch_789|t:thread_456"  ← room + threadId
     * </pre>
     *
     * <h3>RuntimeContext 的 fsUserId 说明</h3>
     * <pre>
     *   session 路由 key：从 MsgContext.userId() 派生，每个用户独立 session
     *   文件系统 userId：从 fsUserIdResolver 解析，共享 Agent 场景下可能映射到同一个 owner
     * </pre>
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();

        // 1. 计算 session 路由 key（canonicalKey）
        // 例如 "chatui|r:bob"（PER_PEER 私聊）或 "feishu-rd|g:org-a|r:oc_123"（群聊）
        String gateKey = ctx.canonicalKey();

        // 2. 从 MsgContext.extra 中取出 ChannelRouter 预设的 agentId，找到对应 Agent 实例
        // 如果没有指定或找不到，fallback 到 mainAgent / defaultAgentId
        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Mono.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before run(...)"));
        }

        // 3. 复用或创建 MAIN session（原子操作，高并发安全）
        // contextKeyToSessionKey.compute(gateKey, ...) 保证同一 gateKey 只会创建一个 session
        String sessionKey = resolveOrCreateMainSession(gateKey, ha, ctx.userId());
        // sessionId 是对话历史的存储标识，sessionKey 是路由映射的标识，通常相等
        String sessionId =
                sessionAgentManager
                        .viewSession(sessionKey)
                        .map(SessionView::sessionId)
                        .orElse(sessionKey);

        // 4. 记录出站地址（供子 Agent announce 时回送结果到正确的渠道）
        if (outboundAddress != null) {
            lastRouteBySessionKey.put(sessionKey, outboundAddress);
        }

        // 5. 构造 RuntimeContext（Agent 执行时的上下文信息）
        // sessionId    → 对话历史的存储 key
        // sessionKey   → 路由映射的 key（可能和 sessionId 相同）
        // msgContext   → 原始路由上下文（包含 channel、guild、room 等）
        // userId       → 文件系统命名空间用的用户 ID（共享 Agent 场景下可能不同于实际发送者）
        String routedAgentId = resolveAgentId(ha);
        String fsUserId = resolveFsUserId(ctx.userId(), routedAgentId);
        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("sessionKey", sessionKey);
        if (fsUserId != null && !fsUserId.isBlank()) {
            rtcBuilder.userId(fsUserId);
        }
        RuntimeContext runtimeContext = rtcBuilder.build();

        // 6. 在 per-key 串行锁保护下执行 Agent call
        // withGatedTurn 保证同一个 gateKey 同一时刻只有一个 turn 在执行
        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    /**
     * 子 Agent 创建拦截器 —— 当 {@link SessionAgentManager} 创建子 Agent session 时自动调用。
     *
     * <p>目的：把子 session 的三张路由映射表都「继承」父 session 的映射，
     * 确保子 Agent 完成后 announce 能沿着正确的路径回送。
     *
     * <p>继承关系：
     * <pre>
     *   父 session (sess_main_001):
     *     gateKey    = "feishu-rd|r:oc_123"
     *     agentId    = "assistant"
     *     outAddr    = feishu-rd:oc_123
     *
     *   子 session (sess_sub_001) 创建时：
     *     gateKey    ← 继承父的 "feishu-rd|r:oc_123"
     *     agentId    ← 继承父的 "assistant"
     *     outAddr    ← 继承父的 feishu-rd:oc_123
     *
     *   子 Agent 完成后 → tryDispatchAnnounce() 通过这些映射
     *   找到正确的 gate、Agent、出站地址，把结果推送回飞书群 oc_123
     * </pre>
     */
    private void onSpawn(SpawnResult result, String parentSessionKey) {
        if (parentSessionKey != null) {
            String gateKey =
                    sessionKeyToGateKey.getOrDefault(
                            parentSessionKey, MsgContext.defaultContext().canonicalKey());
            sessionKeyToGateKey.put(result.sessionKey(), gateKey);
            String parentAgentId = sessionKeyToAgentId.get(parentSessionKey);
            if (parentAgentId != null) {
                sessionKeyToAgentId.put(result.sessionKey(), parentAgentId);
            }
            OutboundAddress parentRoute = lastRouteBySessionKey.get(parentSessionKey);
            if (parentRoute != null) {
                lastRouteBySessionKey.put(result.sessionKey(), parentRoute);
            }
        }
    }

    /**
     * 子 Agent 完成后的 announce 分发 —— Gateway 的「后向推送」机制。
     *
     * <p>场景：主 Agent 通过 {@code #run()} 创建子 Agent 并执行工具，子 Agent 完成后，
     * SessionAgentManager 调用此方法把结果回送给父 Agent，再触发一轮 Agent call。
     *
     * <h3>完整流程</h3>
     * <pre>
     * 父 Agent (sess_main_001) 调用了 PlanNotebook 工具 → 创建子 Agent (sess_sub_001)
     *   → SessionAgentManager 创建子 session
     *   → ★ onSpawn() 拦截：把子 session 的 gateKey/agentId/lastRoute 都继承父 session
     *   → 子 Agent 执行完成
     *   → SessionAgentManager ★ tryDispatchAnnounce() ← 就是这里
     *       → sessionKeyToGateKey.get(requesterKey)    // 找到父 session 的 gateKey
     *       → sessionKeyToAgentId.get(requesterKey)    // 找到父 session 的 agentId
     *       → resolveOrCreateMainSession()             // 复用父 session
     *       → withGatedTurn(gateKey, ha.call(announce)) // 父 Agent 收到完成通知，继续执行
     *       → deliverAnnounceReply(deliveryTarget, reply) // 把最终回复推送到渠道
     * </pre>
     *
     * <h3>关键数据源</h3>
     * <pre>
     *   completion.requesterSessionKey()  // 谁请求的子 Agent
     *   completion.announceText()         // 子 Agent 完成通知内容
     *   completion.childSessionKey()      // 子 session 的 key
     * </pre>
     *
     * <h3>ROOT_REQUESTER 特殊处理</h3>
     * <p>当 requesterKey 是 SessionConstants.ROOT_REQUESTER_SESSION_KEY 时，
     * 表示请求是从 Root Agent 直接发起的子 Agent（而非中间 Agent），gateKey 不存在
     * 时使用默认 MsgContext 的 canonicalKey。
     *
     * @return true 如果成功处理了 announce 并启动了新的 agent turn
     */
    boolean tryDispatchAnnounce(PendingCompletion completion) {
        String requesterKey = completion.requesterSessionKey();
        String gateKey = sessionKeyToGateKey.get(requesterKey);
        String requesterAgentId = sessionKeyToAgentId.get(requesterKey);

        boolean isRootRequester = SessionConstants.ROOT_REQUESTER_SESSION_KEY.equals(requesterKey);
        if (gateKey == null && !isRootRequester) {
            return false;
        }
        if (gateKey == null) {
            gateKey = MsgContext.defaultContext().canonicalKey();
        }

        HarnessAgent ha = resolveAgent(requesterAgentId);
        if (ha == null) {
            log.debug(
                    "Announce skipped: no agent found for requesterKey={} agentId={}",
                    requesterKey,
                    requesterAgentId);
            return false;
        }

        Msg m =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("subagent_announce")
                        .textContent(completion.announceText())
                        .build();

        // Retrieve the userId stored on the requester session for namespace continuity
        String sessionUserId =
                sessionAgentManager.getSession(requesterKey).map(SessionEntry::userId).orElse(null);

        String sessionKey = resolveOrCreateMainSession(gateKey, ha, sessionUserId);
        String sessionId =
                sessionAgentManager
                        .viewSession(sessionKey)
                        .map(SessionView::sessionId)
                        .orElse(sessionKey);

        // Mirror the run() path: session is per-caller, filesystem namespace is owner-pinned
        // for shared agents via the installed resolver.
        String routedAgentId = resolveAgentId(ha);
        String fsUserId = resolveFsUserId(sessionUserId, routedAgentId);
        RuntimeContext.Builder ctxBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("announce", Boolean.TRUE)
                        .put("childRunId", completion.runId())
                        .put("childSessionKey", completion.childSessionKey());
        if (fsUserId != null && !fsUserId.isBlank()) {
            ctxBuilder.userId(fsUserId);
        }
        RuntimeContext ctx = ctxBuilder.build();

        OutboundAddress lastRoute = lastRouteBySessionKey.get(requesterKey);
        if (lastRoute == null) {
            lastRoute = lastRouteBySessionKey.get(sessionKey);
        }
        final OutboundAddress deliveryTarget = lastRoute;

        final String resolvedGateKey = gateKey;
        withGatedTurn(resolvedGateKey, () -> ha.call(List.of(m), ctx))
                .subscribe(
                        reply -> deliverAnnounceReply(deliveryTarget, reply),
                        err -> log.warn("Announce agent run failed: {}", err.toString()));
        return true;
    }

    /**
     * announce 回复消息推送 —— 通过 {@link ChannelManager} 把子 Agent 完成通知的结果
     * 发回原始渠道。
     *
     * <p>流程：
     * <pre>
     *   子 Agent 完成 → 返回回复消息 → ★ deliverAnnounceReply()
     *       → check reply == null → 直接返回
     *       → check "NO_REPLY" → 跳过推送
     *       → lastRouteBySessionKey.get(requesterKey) → 找到目标渠道
     *       → channelManager.deliver(target, reply) → 推送到飞书/钉钉/GitHub
     * </pre>
     *
     * <p>容错：如果找不到出站地址或 channelManager 为 null，仅打日志不抛异常。
     */
    private void deliverAnnounceReply(OutboundAddress target, Msg reply) {
        if (reply == null) {
            return;
        }
        String text = reply.getTextContent();
        if (text != null && text.strip().equalsIgnoreCase("NO_REPLY")) {
            return;
        }
        if (target == null || channelManager == null) {
            log.debug("Announce reply not delivered (no route or no channel manager)");
            return;
        }
        try {
            channelManager.deliver(target, List.of(reply));
        } catch (Exception e) {
            log.warn(
                    "Failed to deliver announce reply: channel={}, to={}",
                    target.channelId(),
                    target.to(),
                    e);
        }
    }

    // -----------------------------------------------------------------
    //  Internal helpers（内部工具方法）
    // -----------------------------------------------------------------

    /**
     * 三级 fallback 的 Agent 解析策略：
     * <pre>
     *   ① agentId 在 agentRegistry 中存在 → 返回对应 Agent
     *   ② mainAgent 引用不为空 → 返回主 Agent
     *   ③ defaultAgentId 在 registry 中存在 → 返回默认 Agent
     *   ④ 都找不到 → 返回 null（调用方会返回 Mono.error）
     * </pre>
     */
    private HarnessAgent resolveAgent(String agentId) {
        if (agentId != null && agentRegistry.containsKey(agentId)) {
            return agentRegistry.get(agentId);
        }
        HarnessAgent def = mainAgent.get();
        if (def != null) {
            return def;
        }
        return defaultAgentId != null ? agentRegistry.get(defaultAgentId) : null;
    }

    /**
     * 复用或创建 MAIN session —— 核心原子操作。
     *
     * <p>通过 {@link ConcurrentHashMap#compute()} 实现并发安全的"读-算-写"：
     * <pre>
     *   场景 ①：首次访问
     *     gateKey = "chatui|r:bob"
     *     → existingSessionKey = null
     *     → registerMainSession("assistant", null, "chatui|r:bob", "bob")
     *     → 回写 sessionKeyToGateKey / sessionKeyToAgentId 反向索引
     *     → 返回新 sessionKey
     *
     *   场景 ②：已存在且 session 还新鲜
     *     gateKey = "chatui|r:bob"
     *     → existingSessionKey = "sess_001"
     *     → isSessionFresh("sess_001") = true
     *     → 直接返回 "sess_001"
     *
     *   场景 ③：已存在但 session 过期
     *     gateKey = "chatui|r:bob"
     *     → existingSessionKey = "sess_001"
     *     → isSessionFresh("sess_001") = false
     *     → log.info("Session stale, rolling over...")
     *     → registerMainSession 创建新 session
     *     → 覆盖 contextKeyToSessionKey["chatui|r:bob"]
     *     → 回写反向索引
     *     → 返回新 sessionKey
     * </pre>
     *
     * @param gateKey 消息路由 key（canonicalKey）
     * @param ha 当前处理消息的 HarnessAgent
     * @param userId 发送者身份（用于 session 隔离）
     * @return sessionKey（MAIN session 的唯一标识）
     */
    private String resolveOrCreateMainSession(String gateKey, HarnessAgent ha, String userId) {
        return contextKeyToSessionKey.compute(
                gateKey,
                (k, existingSessionKey) -> {
                    if (existingSessionKey != null) {
                        if (isSessionFresh(existingSessionKey)) {
                            return existingSessionKey;
                        }
                        log.info(
                                "Session stale, rolling over: gateKey={}, oldSessionKey={}",
                                gateKey,
                                existingSessionKey);
                    }
                    String aid = resolveAgentId(ha);
                    SpawnResult r =
                            sessionAgentManager.registerMainSession(aid, null, gateKey, userId);
                    sessionKeyToGateKey.put(r.sessionKey(), gateKey);
                    sessionKeyToAgentId.put(r.sessionKey(), aid);
                    return r.sessionKey();
                });
    }

    /**
     * 检查 session 是否仍然有效。
     *
     * <p>判断依据：
     * <pre>
     *   ① SessionResetPolicy.mode == NEVER
     *     → 不过期，永远返回 true
     *   ② 其他模式（1_MINUTE / 1_DAY / 1_HOUR / 1_WEEK / 30_MINUTE）
     *     → SessionFreshnessEvaluator.evaluate(lastActivityMs, now, policy)
     *     → 判断距离上次活动时间是否超过阈值
     *   ③ session 不存在
     *     → return false
     * </pre>
     */
    private boolean isSessionFresh(String sessionKey) {
        SessionResetPolicy policy = sessionAgentManager.getConfig().sessionResetPolicy();
        if (policy.mode() == SessionResetPolicy.ResetMode.NEVER) {
            return true;
        }
        return sessionAgentManager
                .getSession(sessionKey)
                .map(
                        entry -> {
                            SessionFreshness f =
                                    SessionFreshnessEvaluator.evaluate(
                                            entry.lastActivityMs(),
                                            System.currentTimeMillis(),
                                            policy);
                            return f.fresh();
                        })
                .orElse(false);
    }

    /**
     * 从 HarnessAgent 实例解析 agentId，取不到时默认为 "main"。
     */
    private static String resolveAgentId(HarnessAgent ha) {
        String id = ha != null ? ha.getAgentId() : null;
        return (id != null && !id.isBlank()) ? id : "main";
    }

    private Mono<Msg> withGatedTurn(String gateKey, Supplier<Mono<Msg>> turn) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        return Mono.defer(turn::get)
                .doOnSubscribe(
                        s -> {
                            try {
                                sessionTurnGate.acquire(gateKey);
                                acquired.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        })
                .doFinally(
                        sig -> {
                            if (acquired.get()) {
                                sessionTurnGate.release(gateKey);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

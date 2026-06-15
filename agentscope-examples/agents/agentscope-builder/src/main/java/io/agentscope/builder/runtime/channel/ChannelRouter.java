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
package io.agentscope.builder.runtime.channel;

import io.agentscope.builder.runtime.gateway.MsgContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the target {@code agentId} and stable {@link MsgContext} for an {@link InboundMessage}
 * by evaluating {@link ChannelBinding} rules in deterministic priority order — mirroring OpenClaw's
 * {@code resolveAgentRoute} binding tiers.
 *
 * <h2>Binding evaluation tiers (highest → lowest priority)</h2>
 *
 * <ol>
 *   <li><b>explicit</b> — {@link InboundMessage#preferredAgentId()} short-circuit when the caller
 *       has already nominated a specific agent (e.g. a path-mapped Web UI). Bindings are still
 *       consulted to determine {@code sessionScope} and outbound addressing.
 *   <li><b>peer</b> — exact {@link Peer#key()} match (e.g. {@code "direct:u_42"})
 *   <li><b>peer.parent</b> — exact match on the thread-parent peer's key
 *   <li><b>guild + roles</b> — guild id matches AND sender holds at least one of the binding's
 *       roles
 *   <li><b>guild</b> — guild id matches (no role constraint)
 *   <li><b>team</b> — team id matches
 *   <li><b>account</b> — account id matches
 *   <li><b>channel</b> — channel id matches
 *   <li><b>default</b> — {@link ChannelConfig#defaultAgentId()} or {@code globalDefaultAgentId}
 * </ol>
 *
 * <p>Within each tier the first binding in {@link ChannelConfig#bindings()} list order that
 * matches wins.
 *
 * <h2>Session key construction ({@link MsgContext})</h2>
 *
 * After resolving {@code agentId}, {@link ChannelRouter} builds a {@link MsgContext} whose {@link
 * MsgContext#canonicalKey()} produces a stable session key:
 *
 * <ul>
 *   <li>DM + {@link DmScope#MAIN} → {@code channel} field only (no room/peer); all DMs share one
 *       session
 *   <li>DM + other scopes → room = peerId (optionally group = accountId)
 *   <li>Thread → room = parentPeer id, threadId = peer id
 *   <li>Non-DM channel/group → room = peer id, group = guild
 * </ul>
 *
 * The resolved {@code agentId} is always included in {@link MsgContext#extra()} under key {@code
 * "agentId"} so that {@link io.agentscope.builder.runtime.gateway.HarnessGateway#run} can pick the correct
 * agent from its registry.
 */
public final class ChannelRouter {

    private final String globalDefaultAgentId;

    /**
     * @param globalDefaultAgentId fallback agent id when no binding and no channel-level default
     *     match; typically the id of the agent registered via {@link
     *     io.agentscope.builder.runtime.gateway.Gateway#bindMainAgent}
     */
    public ChannelRouter(String globalDefaultAgentId) {
        this.globalDefaultAgentId = globalDefaultAgentId != null ? globalDefaultAgentId : "main";
    }

    /**
     * Evaluates bindings in priority order and returns a {@link RouteResult} ready for {@link
     * io.agentscope.builder.runtime.gateway.Gateway#run}.
     *
     * @param config channel-level routing config (bindings + dmScope + channel default agent)
     * @param msg normalized inbound message
     */
    public RouteResult resolveRoute(ChannelConfig config, InboundMessage msg) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(msg, "msg");

        String agentId;
        String matchedBy;
        // Tier 0: explicit override — caller pinned the agent (e.g. path-mapped Web UI). We still
        // evaluate bindings below to derive the effective sessionScope/outbound address; the
        // override only displaces the agentId selection.
        String explicit = msg.preferredAgentId();
        ChannelBinding matchedBinding = findMatchedBinding(config.bindings(), msg);

        if (explicit != null && !explicit.isBlank()) {
            agentId = explicit;
            matchedBy = "explicit";
        } else if (matchedBinding != null) {
            agentId = matchedBinding.agentId();
            matchedBy = detectMatchedTier(config.bindings(), msg);
        } else if (config.defaultAgentId() != null) {
            agentId = config.defaultAgentId();
            matchedBy = "channel-default";
        } else {
            agentId = globalDefaultAgentId;
            matchedBy = "global-default";
        }

        // Binding-level sessionScope overrides channel-level dmScope (mirrors OpenClaw behaviour)
        DmScope effectiveScope =
                matchedBinding != null && matchedBinding.sessionScope() != null
                        ? matchedBinding.sessionScope()
                        : config.dmScope();

        // Derive userId from senderId; fall back to peer.id() in DM context where they are equal
        String userId =
                msg.senderId() != null
                        ? msg.senderId()
                        : (msg.peer().kind().isDirect() ? msg.peer().id() : null);

        MsgContext context = buildContext(msg, effectiveScope, agentId, userId);
        OutboundAddress outbound = buildOutboundAddress(msg);
        return new RouteResult(agentId, context, matchedBy, outbound);
    }

    // -----------------------------------------------------------------
    //  Binding evaluation - 8 tiers
    // -----------------------------------------------------------------

    private static final List<String> TIERS =
            List.of("peer", "parentPeer", "guildRoles", "guild", "team", "account", "channel");

    /** Returns the first matching binding across all tiers, or null. */
    private ChannelBinding findMatchedBinding(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            ChannelBinding b = findFirstBinding(bindings, msg, tier);
            if (b != null) return b;
        }
        return null;
    }

    private String detectMatchedTier(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            if (findFirstBinding(bindings, msg, tier) != null) {
                return tier.equals("guildRoles") ? "guild+roles" : tier;
            }
        }
        return "none";
    }

    private ChannelBinding findFirstBinding(
            List<ChannelBinding> bindings, InboundMessage msg, String tier) {
        for (ChannelBinding b : bindings) {
            if (matches(b, msg, tier)) {
                return b;
            }
        }
        return null;
    }

    private boolean matches(ChannelBinding b, InboundMessage msg, String tier) {
        return switch (tier) {
            case "peer" -> b.peer() != null && b.peer().equals(msg.peer().key());
            case "parentPeer" ->
                    b.parentPeer() != null
                            && msg.parentPeer() != null
                            && b.parentPeer().equals(msg.parentPeer().key());
            case "guildRoles" ->
                    b.guild() != null
                            && !b.roles().isEmpty()
                            && b.guild().equals(msg.guild())
                            && !b.roles().isEmpty()
                            && msg.roles().stream().anyMatch(b.roles()::contains);
            case "guild" ->
                    b.guild() != null && b.roles().isEmpty() && b.guild().equals(msg.guild());
            case "team" -> b.team() != null && b.team().equals(msg.team());
            case "account" -> b.account() != null && b.account().equals(msg.accountId());
            case "channel" -> b.channel() != null && b.channel().equals(msg.channelId());
            default -> false;
        };
    }

    // -----------------------------------------------------------------
    //  MsgContext construction from routing result + dmScope
    // -----------------------------------------------------------------

    /**
     * 根据消息的会话类型（Thread / DM / Channel）构造 MsgContext。
     *
     * <p>这是 ChannelRouter 路由结果的「落地」步骤 —— 路由找到目标 Agent 后，
     * 通过 MsgContext 决定用哪个 session 来承接这次对话。
     *
     * <h3>三种会话类型的 MsgContext 构造逻辑</h3>
     * <pre>
     * ┌──────────────┬──────────────────────────────────────────────────────────┐
     * │ 会话类型     │ MsgContext 构造方式                                       │
     * ├──────────────┼──────────────────────────────────────────────────────────┤
     * │ Thread（帖子）│ parentPeer → room, peer → threadId                        │
     * │              │ → canonicalKey = "discord|r:#general|t:thread_456"        │
     * │              │ → 每个 Thread 独立 session                                │
     * ├──────────────┼──────────────────────────────────────────────────────────┤
     * │ DM（私聊）   │ 由 DmScope 决定 room/group 填充策略                       │
     * │              │ → 详见 buildDmContext()                                  │
     * ├──────────────┼──────────────────────────────────────────────────────────┤
     * │ Channel/群聊 │ room = peer.id, group = guild                            │
     * │              │ → canonicalKey = "feishu-rd|g:org-a|r:oc_123"            │
     * │              │ → 同一个群的所有人共享 session（senderId 通过 userId 区分）│
     * └──────────────┴──────────────────────────────────────────────────────────┘
     * </pre>
     *
     * <p>最后通过 {@code ctx.withUserId(userId)} 把发送者身份挂上去。
     * userId 不参与 canonicalKey 计算，只用于 HarnessAgent 内部做 namespace 隔离。
     */
    private MsgContext buildContext(
            InboundMessage msg, DmScope dmScope, String agentId, String userId) {
        Map<String, String> extra = Map.of("agentId", agentId);
        String channel = msg.channelId();
        Peer peer = msg.peer();

        MsgContext ctx;
        if (peer.kind().isThread()) {
            // Thread（主题帖）场景：父级频道是 room，Thread 自身是 threadId
            // 例如 Discord #general 频道下的某个 Thread:
            //   parentPeer = CHANNEL("ch_789")  → room = "ch_789"
            //   peer       = THREAD("thread_456") → threadId = "thread_456"
            //   → canonicalKey = "discord|r:ch_789|t:thread_456"
            String parentRoom = msg.parentPeer() != null ? msg.parentPeer().id() : null;
            ctx = new MsgContext(channel, msg.guild(), parentRoom, peer.id(), null, extra);
        } else if (peer.kind().isDirect()) {
            // DM（私聊）场景：由 DmScope 决定隔离粒度
            ctx = buildDmContext(channel, peer.id(), msg.accountId(), dmScope, extra);
        } else {
            // Channel/群聊 场景：群/频道 ID 作为 room，组织作为 group
            // 例如飞书群 "oc_123" 在组织 "org-a" 下:
            //   → canonicalKey = "feishu-rd|g:org-a|r:oc_123"
            // 同群内 bob 和 alice 共用同一个 session，
            // 但 HarnessAgent 通过 userId 知道具体是谁在说话
            ctx = new MsgContext(channel, msg.guild(), peer.id(), null, null, extra);
        }

        // 把发送者身份挂到 MsgContext 上（不参与 canonicalKey 计算）
        return userId != null ? ctx.withUserId(userId) : ctx;
    }

    /**
     * 根据 {@link DmScope} 构造 DM（私聊）场景下的 MsgContext。
     *
     * <p>核心逻辑：通过控制 MsgContext 中 room / group 字段的填充，
     * 间接控制 {@link MsgContext#canonicalKey()} 的输出，从而决定 session 隔离粒度。
     *
     * <h3>canonicalKey 生成规则（MsgContext 中定义）</h3>
     * <pre>
     *   canonicalKey = channel
     *                + "|g:" + group   （group 不为空时）
     *                + "|r:" + room    （room 不为空时）
     * </pre>
     *
     * <h3>四种 DmScope → MsgContext → canonicalKey → 效果对照</h3>
     * <pre>
     * ┌─────────────────────────┬──────────────────────────────────────┬─────────────────────────────────┐
     * │ DmScope                 │ MsgContext(group, room)              │ canonicalKey                    │
     * ├─────────────────────────┼──────────────────────────────────────┼─────────────────────────────────┤
     * │ MAIN                    │ (null, null)                         │ "chatui"                        │
     * │ PER_PEER ★最常用        │ (null, "bob")                        │ "chatui|r:bob"                  │
     * │ PER_CHANNEL_PEER        │ (null, "bob") ★注意和上面一样         │ "chatui|r:bob" ← 但 channel 不同│
     * │ PER_ACCOUNT_CHANNEL_PEER│ ("tenantA", "bob")                   │ "chatui|g:tenantA|r:bob"        │
     * └─────────────────────────┴──────────────────────────────────────┴─────────────────────────────────┘
     * </pre>
     *
     * <h3>具体例子：bob 和 alice 都通过 ChatUi 私聊同一个 Agent</h3>
     * <pre>
     *   MAIN:
     *     bob   → canonicalKey = "chatui"
     *     alice → canonicalKey = "chatui"  ← 同一个！两人共享对话历史
     *
     *   PER_PEER:
     *     bob   → canonicalKey = "chatui|r:bob"
     *     alice → canonicalKey = "chatui|r:alice"  ← 不同！各自独立
     *
     *   PER_CHANNEL_PEER:
     *     bob 在 ChatUi → canonicalKey = "chatui|r:bob"
     *     bob 在 飞书   → canonicalKey = "feishu-rd|r:bob"  ← channel 不同，session 不同
     *     → 同一人不同平台入口，对话历史也分开
     *
     *   PER_ACCOUNT_CHANNEL_PEER:
     *     bob(租户A)在 ChatUi → canonicalKey = "chatui|g:tenantA|r:bob"
     *     bob(租户B)在 ChatUi → canonicalKey = "chatui|g:tenantB|r:bob"  ← group 不同，session 不同
     *     → 多租户 SaaS 场景，同一平台不同账号彻底隔离
     * </pre>
     *
     * <p>注意  {@code PER_PEER} 和 {@code PER_CHANNEL_PEER} 在这里构造出的 MsgContext
     * 字段完全一样（都是 room=peerId），区别在于  {@code channel} 参数的值不同：
     * PER_PEER 模式下 channel 通常固定（如 "chatui"），而 PER_CHANNEL_PEER 模式下
     * channel 来自不同的平台实例（如 "chatui" vs "feishu-rd"）。
     */
    private MsgContext buildDmContext(
            String channel,
            String peerId,
            String accountId,
            DmScope dmScope,
            Map<String, String> extra) {
        return switch (dmScope) {
            case MAIN ->
                    // 所有人共享一个 session
                    // channel = "chatui", group = null, room = null
                    // → canonicalKey = "chatui"
                    // → bob 和 alice 的对话混在同一个 session 里
                    new MsgContext(channel, null, null, null, null, extra);
            case PER_PEER ->
                    // 每个人独立 session（★ 最常用）
                    // channel = "chatui", group = null, room = peerId（如 "bob"）
                    // → canonicalKey = "chatui|r:bob"
                    // → 不同用户互不干扰
                    new MsgContext(channel, null, peerId, null, null, extra);
            case PER_CHANNEL_PEER ->
                    // 同一个人，不同平台入口，session 也分开
                    // MsgContext 字段和 PER_PEER 一样（room=peerId）
                    // 但 channel 来自不同平台实例（"chatui" vs "feishu-rd"），
                    // canonicalKey 自然不同，实现了跨平台隔离
                    new MsgContext(channel, null, peerId, null, null, extra);
            case PER_ACCOUNT_CHANNEL_PEER ->
                    // 最细粒度：多租户维度隔离
                    // channel = "chatui", group = accountId（如 "tenantA"）, room = peerId
                    // → canonicalKey = "chatui|g:tenantA|r:bob"
                    // → 同一平台的不同租户，同一租户的不同用户，全部隔离
                    new MsgContext(channel, accountId, peerId, null, null, extra);
        };
    }

    // -----------------------------------------------------------------
    //  OutboundAddress construction
    // -----------------------------------------------------------------

    private OutboundAddress buildOutboundAddress(InboundMessage msg) {
        String to = msg.channelId() + ":" + msg.peer().id();
        String threadId = msg.peer().kind().isThread() ? msg.peer().id() : null;
        return new OutboundAddress(msg.channelId(), msg.accountId(), to, threadId);
    }
}

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

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 跨平台统一入站消息 —— 各平台的消息经过 Mapper 转换后，统一为此结构。
 *
 * <h2>设计目的</h2>
 * 无论消息来自飞书、钉钉、GitHub 还是 Web 页面，最终都归一化为 InboundMessage，
 * 后续的 ChannelRouter → Gateway → Agent 链路不需要关心原始平台差异。
 *
 * <h2>字段速览</h2>
 * <pre>
 * ┌─ 必填 ─────────────────────────────────────────────────────┐
 * │ channelId      消息来自哪个渠道实例，如 "chatui"、"feishu-rd"│
 * │ peer           会话标识（私聊的人？群？频道？Thread？）       │
 * │ messages       实际消息内容列表                              │
 * ├─ 身份 ─────────────────────────────────────────────────────┤
 * │ senderId       发送者 ID。私聊时 = peer.id，群聊时 = 群成员  │
 * ├─ 组织层级（从大到小）───────────────────────────────────────┤
 * │ guild          组织/公司/服务器（Discord Guild, GitHub Org） │
 * │ team           团队/部门（Slack Team, MS Teams）            │
 * │ roles          发送者在该组织内的角色集合，如 ["admin"]       │
 * ├─ 会话嵌套 ─────────────────────────────────────────────────┤
 * │ parentPeer     Thread 的父级会话（Discord Thread 的父频道）  │
 * ├─ 多租户 ───────────────────────────────────────────────────┤
 * │ accountId      多租户标识。同一飞书应用服务多个企业时区分用   │
 * ├─ 路由控制 ─────────────────────────────────────────────────┤
 * │ preferredAgentId  显式指定目标 Agent，跳过 binding 匹配     │
 * └────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>peer 与 senderId 的区别 —— 最重要的一对概念</h2>
 * <pre>
 *   私聊: alice 对 bot 说 "hello"
 *     peer = DIRECT("alice")
 *     senderId = "alice"
 *     → 两者相同
 *
 *   群聊: alice 在飞书群 "oc_group" 里说 "@bot 帮我看看"
 *     peer = GROUP("oc_group")   ← 决定 session 归属
 *     senderId = "ou_alice"      ← 决定身份（Agent 知道是谁在说话）
 *     → 两者不同！
 * </pre>
 *
 * <h2>完整示例：飞书群聊消息</h2>
 * <pre>
 * InboundMessage {
 *     channelId    = "feishu-rd",            // 飞书研发部的 bot 实例
 *     accountId    = "tenant-acme",          // 飞书租户
 *     peer         = Peer(GROUP, "oc_123"),  // 飞书群 ID
 *     senderId     = "ou_alice",             // alice 的飞书 open_id
 *     parentPeer   = null,                   // 不是 Thread
 *     guild        = "acme-corp",            // 所属组织
 *     team         = "backend",              // 所属团队
 *     roles        = ["admin"],              // alice 是管理员
 *     messages     = [Msg("帮我部署到 staging")],
 *     preferredAgentId = null               // 让 ChannelRouter 按 binding 规则匹配
 * }
 * </pre>
 */
public record InboundMessage(

        /**
         * 消息来自哪个渠道实例。
         *
         * <p>这是用户给渠道配置起的名字，不是平台名：
         * <ul>
         *   <li>{@code "chatui"} — Web 页面（唯一硬编码的）</li>
         *   <li>{@code "feishu-rd"} — 飞书研发部 bot</li>
         *   <li>{@code "feishu-support"} — 飞书客服部 bot（同一个平台类型，不同的实例）</li>
         *   <li>{@code "github-org-a"} — GitHub org-a 的 bot</li>
         * </ul>
         *
         * <p>决定 gateKey 的第一部分，用于：
         * <ol>
         *   <li>构造 session 路由 key（MsgContext.canonicalKey）</li>
         *   <li>CallbackController 通过 URL 中的 channelId 找到对应的 Channel 实例</li>
         * </ol>
         */
        String channelId,

        /**
         * 多租户标识。同一个飞书/钉钉应用可能被多个企业安装，此字段用于区分。
         *
         * <p>各平台的对应：
         * <ul>
         *   <li>飞书: {@code header.tenant_key}</li>
         *   <li>钉钉: {@code corpId}</li>
         *   <li>Slack: app installation id</li>
         *   <li>ChatUi / GitHub: 都是 {@code null}（单租户）</li>
         * </ul>
         *
         * <p>参与 session key 构造，确保不同租户的数据完全隔离。
         */
        String accountId,

        /**
         * 会话标识 —— 这条消息属于「哪个对话」。
         *
         * <p>四种会话类型：
         * <ul>
         *   <li>{@code Peer(DIRECT, "alice")} — 和 alice 的一对一私聊</li>
         *   <li>{@code Peer(GROUP, "oc_123")} — 某个群聊</li>
         *   <li>{@code Peer(CHANNEL, "#general")} — 某个频道</li>
         *   <li>{@code Peer(THREAD, "thread_456")} — 某个主题帖</li>
         * </ul>
         *
         * <p><b>决定 session 归属</b> —— 同一个人在不同群里的对话，peer 不同，session 不同，互不干扰。
         */
        Peer peer,

        /**
         * 消息发送者的身份标识。
         *
         * <p>两种典型场景：
         * <pre>
         *   私聊 (DM):
         *     alice 和 bot 一对一聊天
         *     → peer = DIRECT("alice"), senderId = "alice"  ← 两者相等
         *
         *   群聊 (Group/Channel):
         *     alice 在群里 @bot
         *     → peer = GROUP("oc_123"), senderId = "ou_alice"  ← 两者不同！
         *     → bob 也在群里 @bot
         *     → peer = GROUP("oc_123"), senderId = "ou_bob"    ← peer 一样，senderId 不同
         * </pre>
         *
         * <p>用于 Agent 执行时的 userId 隔离 —— Agent 知道「是谁在跟我说话」。
         */
        String senderId,

        /**
         * Thread（主题帖）的父级会话。
         *
         * <p>举个 Discord 例子：
         * <pre>
         *   #general 频道（CHANNEL "ch_789"）
         *     ├── alice 的消息
         *     ├── bob 在 alice 的消息下开了一个 Thread
         *     │     ├── alice 在 Thread 里回复 → parentPeer = CHANNEL("ch_789")
         *     │     └── carol 在 Thread 里回复 → parentPeer = CHANNEL("ch_789")
         *     └── ...
         * </pre>
         *
         * <p>ChannelRouter TIERS 中的 {@code parentPeer} 匹配层级就是用它来判断的。
         * 非 Thread 消息此字段为 {@code null}。
         */
        Peer parentPeer,

        /**
         * 组织/公司标识（Discord 叫 Guild，就是服务器）。
         *
         * <p>跨平台映射：
         * <ul>
         *   <li>Discord: Guild / Server ID</li>
         *   <li>GitHub: Organization name</li>
         *   <li>飞书: 企业/租户 ID</li>
         *   <li>Slack: Workspace ID</li>
         *   <li>ChatUi: {@code null}（没有组织概念）</li>
         * </ul>
         *
         * <p>结合 {@code roles} 可以做权限控制：
         * <pre>
         *   {guild: "org-a", roles: ["admin"], agentId: "deploy-bot"}
         *   → 只有 org-a 的管理员才能触发 deploy-bot
         * </pre>
         */
        String guild,

        /**
         * 团队/部门标识（guild 下面的子层级）。
         *
         * <p>跨平台映射：
         * <ul>
         *   <li>Slack: Team ID（注意 Slack 中 Workspace 包含多个 Team）</li>
         *   <li>Microsoft Teams: Team ID</li>
         *   <li>大多平台中为 {@code null}</li>
         * </ul>
         */
        String team,

        /**
         * 发送者在 {@link #guild} 组织内的角色集合。
         *
         * <p>用于 ChannelRouter 的 {@code guildRoles} 匹配层级：
         * <pre>
         *   alice 在 org-a 里是 admin → roles = ["admin", "member"]
         *   bob 在 org-a 里是普通成员 → roles = ["member"]
         *
         *   ChannelConfig:
         *     {guild: "org-a", roles: ["admin"], agentId: "deploy-bot"}
         *
         *   结果: alice 的消息 → 匹配 → 走 deploy-bot
         *        bob 的消息 → 不匹配 → 往下继续匹配其他规则
         * </pre>
         */
        Set<String> roles,

        /**
         * 实际要发给 Agent 的消息内容。
         *
         * <p>List 而不是单个 Msg 是因为有些平台支持一次发送多条消息（如图片+文字）。
         * 大多数场景下这个列表只有一个元素。
         */
        List<Msg> messages,

        /**
         * 显式指定目标 Agent ID。
         *
         * <p>如果设置了此字段，ChannelRouter 会跳过 8 级 binding 匹配，
         * 直接使用这里指定的 Agent。
         *
         * <p>典型使用场景：
         * <pre>
         *   Web 页面: 用户点了 "assistant" 的聊天按钮
         *     URL = /api/agents/assistant/chat/stream
         *     → preferredAgentId = "assistant"
         *     → 直接路由到 assistant，不查 binding
         *
         *   飞书群聊: 用户只是 @bot
         *     → preferredAgentId = null
         *     → ChannelRouter 按 binding 配置决定用哪个 Agent
         * </pre>
         */
        String preferredAgentId) {

    public InboundMessage {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(messages, "messages");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    // -----------------------------------------------------------------
    //  Factories（工厂方法）
    //  以下工厂方法是常见场景的快捷构造方式，
    //  不填的字段用 null/空集合占位，不参与路由决策
    // -----------------------------------------------------------------

    /**
     * 构造一条私聊消息，不指定 Agent（让 ChannelRouter 按 binding 规则决定）。
     *
     * <p>适用场景：飞书/钉钉私聊，用户只是 @bot，没有明确偏好哪个 Agent。
     *
     * <p>内部等价于：
     * <pre>
     *   InboundMessage {
     *       channelId = channelId,
     *       peer      = Peer(DIRECT, peerId),
     *       senderId  = peerId,          // DM 场景下 senderId == peerId
     *       messages  = messages,
     *       guild/team/roles = null/空,
     *       preferredAgentId = null       // 不指定，走 binding 匹配
     *   }
     * </pre>
     */
    public static InboundMessage dm(String channelId, String peerId, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                null);
    }

    /**
     * 构造一条私聊消息，并显式指定目标 Agent。
     *
     * <p>适用场景：Web 页面用户点击了特定 Agent 的聊天按钮，
     * URL 中已经包含了 agentId，不需要再走 binding 匹配。
     *
     * <p>内部等价于：
     * <pre>
     *   InboundMessage {
     *       ...
     *       preferredAgentId = "assistant"  ← 直接路由，跳过 binding
     *   }
     * </pre>
     */
    public static InboundMessage dmFor(
            String channelId, String peerId, String agentId, List<Msg> messages) {
        Objects.requireNonNull(agentId, "agentId");
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                agentId);
    }

    /**
     * 构造一条频道/群聊消息，发送者和会话分离。
     *
     * <p>适用场景：群聊中有人 @bot，需要区分「哪个群」(roomId) 和「谁发的」(senderId)。
     *
     * <p>内部等价于：
     * <pre>
     *   InboundMessage {
     *       peer     = Peer(CHANNEL, roomId),  // 群/频道
     *       senderId = "ou_alice",             // 群里发消息的人
     *       guild    = "org-a",                // 所属组织（可选）
     *       preferredAgentId = null             // 走 binding 匹配
     *   }
     * </pre>
     */
    public static InboundMessage channel(
            String channelId, String roomId, String senderId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                senderId,
                null,
                guild,
                null,
                Set.of(),
                List.copyOf(messages),
                null);
    }

    /**
     * 构造一条频道消息，不区分发送者（senderId 留空）。
     *
     * <p>适用于无法获取发送者身份的频道消息（如 GitHub issue comment
     * 通过 webhook 推送时可能拿不到精确的 sender 映射）。
     * 此时 senderId 回退为 peer 的 id 做隔离。
     */
    public static InboundMessage channel(
            String channelId, String roomId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                null,
                null,
                guild,
                null,
                Set.of(),
                List.copyOf(messages),
                null);
    }

    // -----------------------------------------------------------------
    //  Convenience（便捷判断方法）
    // -----------------------------------------------------------------

    /** 是否来自私聊（Direct Message）。 */
    public boolean isDm() {
        return peer.kind().isDirect();
    }

    /** 是否来自主题帖（Thread）回复。 */
    public boolean isThread() {
        return peer.kind().isThread();
    }

    // -----------------------------------------------------------------
    //  Builder（复杂场景用 Builder 逐字段构造）
    //  工厂方法覆盖不了的场景（比如同时有 guild + team + roles + parentPeer）
    //  就用 Builder 来拼装
    // -----------------------------------------------------------------

    /** 创建一个 Builder，用于逐字段构造 InboundMessage。 */
    public static Builder builder(String channelId, Peer peer, List<Msg> messages) {
        return new Builder(channelId, peer, messages);
    }

    public static final class Builder {
        private final String channelId;
        private final Peer peer;
        private final List<Msg> messages;
        private String accountId;
        private String senderId;
        private Peer parentPeer;
        private String guild;
        private String team;
        private Set<String> roles = Set.of();
        private String preferredAgentId;

        private Builder(String channelId, Peer peer, List<Msg> messages) {
            this.channelId = channelId;
            this.peer = peer;
            this.messages = messages;
        }

        /** 设置多租户标识（飞书 tenant_key、钉钉 corpId 等）。 */
        public Builder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        /**
         * 设置发送者身份。
         * DM 场景通常等于 {@code peer.id()}；群聊场景是群里具体的人。
         */
        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        /** 设置 Thread 的父级会话。非 Thread 消息无需设置。 */
        public Builder parentPeer(Peer parentPeer) {
            this.parentPeer = parentPeer;
            return this;
        }

        /** 设置组织/公司标识（Discord Guild、GitHub Org、飞书租户）。 */
        public Builder guild(String guild) {
            this.guild = guild;
            return this;
        }

        /** 设置团队/部门标识。 */
        public Builder team(String team) {
            this.team = team;
            return this;
        }

        /** 设置发送者的角色集合，用于 guild+roles 路由匹配。 */
        public Builder roles(Set<String> roles) {
            this.roles = roles != null ? roles : Set.of();
            return this;
        }

        /**
         * 显式指定目标 Agent，跳过 binding 匹配。
         * Web 页面场景使用（用户点击了特定 Agent）。
         */
        public Builder preferredAgentId(String preferredAgentId) {
            this.preferredAgentId = preferredAgentId;
            return this;
        }

        public InboundMessage build() {
            return new InboundMessage(
                    channelId,
                    accountId,
                    peer,
                    senderId,
                    parentPeer,
                    guild,
                    team,
                    roles,
                    messages,
                    preferredAgentId);
        }
    }
}

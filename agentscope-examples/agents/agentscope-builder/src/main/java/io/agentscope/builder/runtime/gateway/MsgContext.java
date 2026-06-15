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

import io.agentscope.core.agent.RuntimeContext;
import java.util.Map;
import java.util.Objects;

/**
 * Routing context for inbound turns (direct API, channel adapter, group/room/thread). Used by
 * {@link HarnessGateway} to map stable conversation keys to {@link RuntimeContext} session ids.
 *
 * <p>The {@link #userId} field carries the message sender's identity for multi-tenant namespace
 * isolation in {@link io.agentscope.harness.agent.HarnessAgent}. It is derived from
 * {@link io.agentscope.builder.runtime.channel.InboundMessage#senderId()} and does <em>not</em>
 * participate in {@link #canonicalKey()} computation — the same user's conversations always map to
 * the same session key regardless of how userId is set.
 *
 * @param channel logical channel name (e.g. slack, discord, web)
 * @param group optional group / team / workspace id
 * @param room optional room / channel id
 * @param threadId optional thread / topic id
 * @param threadTs optional provider-specific thread timestamp or message anchor
 * @param extra additional key/value pairs for adapters
 * @param userId optional authenticated user identity for HarnessAgent namespace isolation; derived
 *     from {@code InboundMessage.senderId()}
 */
public record MsgContext(
        // 渠道标识，如 "chatui", "feishu", "slack"
        String channel,
        // 群组/团队/租户 ID，多租户场景用于隔离不同 workspace
        String group,
        // 房间/频道 ID 或私聊场景下的对端用户 ID
        String room,
        // 线程/话题 ID，用于区分同一房间下的不同讨论线程
        String threadId,
        // 平台相关的线程时间戳或消息锚点（如 Slack thread_ts）
        String threadTs,
        // 扩展键值对，适配器用于传递额外路由信息（通常包含 "agentId"）
        Map<String, String> extra,
        // 消息发送者的用户身份，用于 HarnessAgent 多租户命名空间隔离
        // 注意：userId 不参与 canonicalKey() 计算，不影响 session 路由
        String userId) {

    /** 规范构造函数：确保 extra 不可为 null 且不可变 */
    public MsgContext {
        extra = extra != null ? Map.copyOf(extra) : Map.of();
    }

    /**
     * 不带 userId 的便捷构造函数（兼容旧调用方）。
     */
    public MsgContext(
            String channel,
            String group,
            String room,
            String threadId,
            String threadTs,
            Map<String, String> extra) {
        this(channel, group, room, threadId, threadTs, extra, null);
    }

    /** 默认的单会话上下文（无渠道元数据，无用户身份）。 */
    public static MsgContext defaultContext() {
        return new MsgContext("default", null, null, null, null, Map.of(), null);
    }

    /** 返回设置了指定 userId 的副本（不可变对象，返回新实例）。 */
    public MsgContext withUserId(String userId) {
        return new MsgContext(channel, group, room, threadId, threadTs, extra, userId);
    }

    /**
     * 生成稳定的会话路由键，同一逻辑对话始终映射到同一 Session。
     *
     * <p>格式示例：
     * <pre>
     *   "chatui"                                     — 所有人共享一个 session
     *   "chatui|r:bob"                               — bob 的独立 session
     *   "chatui|g:tenantA|r:bob"                     — 多租户下 bob 的隔离 session
     *   "feishu|r:room_42|t:thread_7"                — 飞书房间中的线程 session
     * </pre>
     *
     * <p>userId 不参与此 key 的计算，确保同一用户的对话始终路由到同一 session。
     */
    public String canonicalKey() {
        StringBuilder sb = new StringBuilder(64);
        // channel 兜底为 "default"
        sb.append(Objects.requireNonNullElse(channel, "default"));
        // group → "|g:xxx"
        if (group != null && !group.isBlank()) {
            sb.append("|g:").append(group.trim());
        }
        // room → "|r:xxx"
        if (room != null && !room.isBlank()) {
            sb.append("|r:").append(room.trim());
        }
        // threadId → "|t:xxx"
        if (threadId != null && !threadId.isBlank()) {
            sb.append("|t:").append(threadId.trim());
        }
        // threadTs → "|ts:xxx"
        if (threadTs != null && !threadTs.isBlank()) {
            sb.append("|ts:").append(threadTs.trim());
        }
        // extra 按 key 排序后追加 → "|x:k1=v1|x:k2=v2"
        if (!extra.isEmpty()) {
            extra.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    sb.append("|x:")
                                            .append(e.getKey())
                                            .append('=')
                                            .append(e.getValue()));
        }
        return sb.toString();
    }
}

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

/**
 * 私聊（DM = Direct Message）场景下，session 的隔离粒度。
 *
 * <h2>一句话解释</h2>
 * 同一个 Agent，bob 和 alice 都来私聊 —— 他们是共用一个 session（共享对话历史）
 * 还是各自独立 session（互不干扰）？DmScope 就是用来控制这个的。
 *
 * <h2>四种粒度对比</h2>
 * <pre>
 * ┌──────────────────────────┬─────────────────────────────────────────────┐
 * │ DmScope                  │ 效果                                        │
 * ├──────────────────────────┼─────────────────────────────────────────────┤
 * │ MAIN                     │ 所有人共享一个 session                       │
 * │ PER_PEER                 │ 每个人独立 session ★ 最常用                  │
 * │ PER_CHANNEL_PEER         │ 同一个人，不同平台，session 也分开            │
 * │ PER_ACCOUNT_CHANNEL_PEER │ 再加上多租户维度，最细粒度                    │
 * └──────────────────────────┴─────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>具体例子</h2>
 * <pre>
 * Agent "assistant" 通过 ChatUi 提供服务，bob 和 alice 都来聊天：
 *
 *   MAIN:
 *     bob:   "hello"      → session: agent:assistant:main
 *     alice: "你好"       → session: agent:assistant:main   ← 同一个！
 *     → bob 和 alice 的对话混在一起，互相能看到对方的上下文
 *     → 适合：单人使用的内部工具
 *
 *   PER_PEER:
 *     bob:   "hello"      → session: ...chatui:direct:bob
 *     alice: "你好"       → session: ...chatui:direct:alice  ← 不同的！
 *     → bob 和 alice 各自独立，互不干扰
 *     → 适合：多人使用的 SaaS 平台
 *
 *   PER_CHANNEL_PEER:
 *     bob 在 Web 上聊    → session: ...chatui:direct:bob
 *     bob 在飞书上聊     → session: ...feishu:direct:bob
 *     → 同一人，不同平台入口，session 也分开
 *     → 适合：希望各平台对话历史独立的场景
 *
 *   PER_ACCOUNT_CHANNEL_PEER:
 *     bob(tenant-A)在飞书 → session: ...tenantA:feishu:direct:bob
 *     bob(tenant-B)在飞书 → session: ...tenantB:feishu:direct:bob
 *     → 多租户场景，同一平台不同账号，彻底隔离
 *     → 适合：SaaS 多租户平台
 * </pre>
 *
 * <p>本质就是控制 gateKey（MsgContext.canonicalKey）的精细度 ——
 * 参与构造的维度越多，隔离越细。
 */
public enum DmScope {

    /**
     * 所有人共享一个 session。
     *
     * <p>gateKey 仅由 agent 决定，不包含用户维度。
     * 适用单人内部工具或共享助手场景。
     */
    MAIN,

    /**
     * 每个人独立 session ★ 最常用。
     *
     * <p>gateKey 编码了 {@code channel + "direct" + peerId}。
     * 适合多用户 Web 平台。
     */
    PER_PEER,

    /**
     * 同一个人不同渠道也分开。
     *
     * <p>gateKey 额外包含 channel 名称。
     * bob 在 ChatUi 和飞书上的对话完全独立。
     */
    PER_CHANNEL_PEER,

    /**
     * 最细粒度 —— 加上多租户维度。
     *
     * <p>gateKey 包含 accountId + channel + peerId。
     * 同一平台有多个 bot 实例（多租户）时使用。
     */
    PER_ACCOUNT_CHANNEL_PEER;

    /** 未配置 DmScope 时的默认值。 */
    public static DmScope defaultScope() {
        return MAIN;
    }
}

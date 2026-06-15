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
package io.agentscope.builder.web.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.web.share.AgentShareGrant;
import java.util.List;

/**
 * Agent 定义的 API 表示，描述一个对当前用户可见的 Agent。
 *
 * <h2>Catalog 中的两种 scope（作用域）</h2>
 * <ul>
 *   <li>{@link #SCOPE_GLOBAL} — 定义在 {@code agentscope.json} 中，所有用户可见。
 *       不可修改或删除，每个用户的对话通过独立 Session 隔离。
 *   <li>{@link #SCOPE_USER} — 由特定用户创建，存储在
 *       {@code .agentscope/users/{userId}/agents.json}。
 *       只有 owner 可以创建、编辑、删除。可通过 {@code shares} 分享给其他用户。
 * </ul>
 *
 * <h2>分享模型</h2>
 * <ul>
 *   <li>{@link #shares} — Agent 级别的分享授权列表（{@link AgentShareGrant}）
 *   <li>{@link #tierForCurrentUser} — 当前调用者对该 Agent 的有效权限等级
 *       （CLONE/RUN/EDIT），仅在认证用户的读路径上填充，不持久化
 *   <li>{@link #runAs} — 执行身份：{@link #RUN_AS_INVOKER}（默认，以调用者身份运行）
 *       或 {@link #RUN_AS_OWNER}（以 owner 身份运行，预留）
 * </ul>
 *
 * @param id Agent 标识符（在 scope 内唯一）
 * @param name 可读的显示名称
 * @param description 可选的简短描述
 * @param sysPrompt 系统提示词（全局 Agent 可能为 null/hidden）
 * @param model 可选的模型 ID 覆盖
 * @param maxIters 最大推理迭代次数（null = 使用默认值）
 * @param tools 内置工具名称列表
 * @param toolsAllow 工具白名单
 * @param toolsDeny 工具黑名单
 * @param identityName 显示名称覆盖
 * @param identityEmoji emoji 简写
 * @param groupChatMentionPatterns 群聊提及模式
 * @param groupChatRequireMention 群聊中是否需要 @提及
 * @param skillsAllow 技能白名单
 * @param skillsDeny 技能黑名单
 * @param scope {@link #SCOPE_GLOBAL} 或 {@link #SCOPE_USER}
 * @param ownerId 创建者的 userId；全局 Agent 为 null
 *        <b>重要</b>：当 Agent 被分享给其他用户时，ownerId 始终是创建者的 ID，
 *        所有被分享者共享 owner 的技能/子 Agent 命名空间
 * @param createdAt 创建时间戳
 * @param updatedAt 更新时间戳
 * @param shares Agent 分享授权列表（null/empty 表示未分享）
 * @param runAs 执行身份：INVOKER（默认）或 OWNER（预留）
 * @param forkOf 如果是通过 clone 创建的，记录源 agentId；否则为 null
 * @param workspacePath 用户指定的工作空间路径
 * @param sandboxMode 执行隔离模式（"local" 或 "sandbox"）
 * @param sandboxScope 沙箱共享范围（SESSION/USER/AGENT/GLOBAL）
 * @param tierForCurrentUser <b>瞬态字段</b>：当前调用者的有效权限等级
 *       （CLONE/RUN/EDIT），仅在读路径上填充，不持久化。
 *       由 {@link io.agentscope.builder.web.share.AgentAclService} 计算
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinition(
        String id,
        String name,
        String description,
        String sysPrompt,
        String model,
        Integer maxIters,
        List<String> tools,
        List<String> toolsAllow,
        List<String> toolsDeny,
        String identityName,
        String identityEmoji,
        List<String> groupChatMentionPatterns,
        Boolean groupChatRequireMention,
        List<String> skillsAllow,
        List<String> skillsDeny,
        String scope,
        String ownerId,
        long createdAt,
        long updatedAt,
        List<AgentShareGrant> shares,
        String runAs,
        String forkOf,
        String workspacePath,
        String sandboxMode,
        String sandboxScope,
        String tierForCurrentUser) {

    /** 全局 Agent：定义在 agentscope.json，所有用户可见 */
    public static final String SCOPE_GLOBAL = "global";

    /** 用户自定义 Agent (UCA)：由用户创建，可分享给其他用户 */
    public static final String SCOPE_USER = "user";

    public static final String RUN_AS_INVOKER = "INVOKER";
    public static final String RUN_AS_OWNER = "OWNER";

    /** Returns a copy with {@code tierForCurrentUser} replaced. */
    public AgentDefinition withTierForCurrentUser(String tier) {
        return new AgentDefinition(
                id,
                name,
                description,
                sysPrompt,
                model,
                maxIters,
                tools,
                toolsAllow,
                toolsDeny,
                identityName,
                identityEmoji,
                groupChatMentionPatterns,
                groupChatRequireMention,
                skillsAllow,
                skillsDeny,
                scope,
                ownerId,
                createdAt,
                updatedAt,
                shares,
                runAs,
                forkOf,
                workspacePath,
                sandboxMode,
                sandboxScope,
                tier);
    }
}

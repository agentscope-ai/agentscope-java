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
package io.agentscope.builder.web.share;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Agent 的分享授权记录，描述「分享给谁」+「给多大权限」两个维度。
 *
 * <h2>分享给谁（granteeType）</h2>
 * <ul>
 *   <li>{@link #GRANTEE_USER} — 指定用户，{@code granteeId} 为具体 userId（如 "alice"）
 *   <li>{@link #GRANTEE_WORKSPACE} — 所有已登录用户，{@code granteeId} 固定为 {@link #WORKSPACE_ID}（"*"）
 * </ul>
 *
 * <h2>给多大权限（tier）—— 向上包含的权限等级</h2>
 * <ul>
 *   <li>{@link #TIER_CLONE} — 可以 fork 一份自己的副本
 *   <li>{@link #TIER_RUN} — 可以跑对话（隐含 CLONE 权限）
 *   <li>{@link #TIER_EDIT} — 可以修改 prompt/工具/模型等设置（隐含 RUN + CLONE）
 * </ul>
 *
 * <p>权限层级：{@code EDIT > RUN > CLONE}，高 tier 自动包含低 tier 的所有能力。
 * 之所以叫 tier（层级/档位）而不是 role 或 level，是因为它表达的是「向上包含」的语义
 * —— 类比产品定价中的 Tier（Free/Pro/Enterprise）或支持等级（Tier 1/2/3）。
 *
 * <p>授权记录存储在 {@link io.agentscope.builder.web.catalog.AgentDefinition#shares()}
 * 中，由 {@link AgentAclService} 负责评估。{@code createdAt}/{@code createdBy} 仅用于审计
 * 日志，不参与权限判断。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentShareGrant(
        // 被授权者类型：USER 或 WORKSPACE
        String granteeType,
        // 被授权者 ID：具体 userId（USER 类型）或 "*"（WORKSPACE 类型）
        String granteeId,
        // 权限等级：CLONE / RUN / EDIT，向上包含
        String tier,
        // 授权创建时间，仅用于审计日志
        long createdAt,
        // 授权创建者，仅用于审计日志
        String createdBy) {

    /** 授权类型：指定用户 */
    public static final String GRANTEE_USER = "USER";

    /** 授权类型：所有已登录用户 */
    public static final String GRANTEE_WORKSPACE = "WORKSPACE";

    /** WORKSPACE 类型的通配 granteeId */
    public static final String WORKSPACE_ID = "*";

    /** 权限等级：可以 fork 副本 */
    public static final String TIER_CLONE = "CLONE";

    /** 权限等级：可以跑对话（含 CLONE） */
    public static final String TIER_RUN = "RUN";

    /** 权限等级：可以修改设置（含 RUN + CLONE） */
    public static final String TIER_EDIT = "EDIT";
}

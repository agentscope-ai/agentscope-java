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
package io.agentscope.dataagent.web.share;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single sharing grant on an agent.
 *
 * <p>Two dimensions:
 *
 * <ul>
 *   <li>Who — a specific user ({@code granteeType=USER, granteeId=<userId>}) or every logged-in
 *       user in this install ({@code granteeType=WORKSPACE, granteeId="*"}).
 *   <li>What — a tier ({@code CLONE}, {@code RUN}, or {@code EDIT}). Tiers are inclusive:
 *       {@code EDIT > RUN > CLONE}, so a {@code RUN} grant implies {@code CLONE}.
 * </ul>
 *
 * <p>Grants live on the agent definition (see
 * {@link io.agentscope.dataagent.web.catalog.AgentDefinition#shares()}) and are evaluated by
 * {@link AgentAclService}. The {@code createdAt}/{@code createdBy} fields exist for the activity
 * log; they are never used in permission decisions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentShareGrant(
        String granteeType, String granteeId, String tier, long createdAt, String createdBy) {

    public static final String GRANTEE_USER = "USER";
    public static final String GRANTEE_WORKSPACE = "WORKSPACE";
    public static final String WORKSPACE_ID = "*";

    public static final String TIER_CLONE = "CLONE";
    public static final String TIER_RUN = "RUN";
    public static final String TIER_EDIT = "EDIT";
}

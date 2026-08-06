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

/**
 * Request body for granting a {@link ResourceAccessService} share on a non-agent managed
 * resource (environment / memory store / vault).
 *
 * @param granteeType {@link AgentShareGrant#GRANTEE_USER} or {@link
 *     AgentShareGrant#GRANTEE_WORKSPACE}
 * @param granteeId the target user id, or {@link AgentShareGrant#WORKSPACE_ID} for a workspace
 *     grant
 * @param tier one of {@code CLONE}, {@code RUN}, {@code EDIT} (see {@link AgentAclService.Tier})
 */
public record ShareRequest(String granteeType, String granteeId, String tier) {}

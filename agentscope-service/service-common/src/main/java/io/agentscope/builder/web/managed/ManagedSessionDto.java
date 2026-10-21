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
package io.agentscope.builder.web.managed;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** API representation of a managed agent session. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagedSessionDto(
        String id,
        String ownerId,
        String agentId,
        String agentOwnerId,
        Integer agentVersion,
        String agentRefType,
        String agentOverridesJson,
        String environmentId,
        /**
         * Stable identity of the conversation this session was created for, or {@code null} for
         * plain chat-UI / API sessions. Team member sessions are allocated by the control plane
         * with a {@code team|{namespace}/{team}|{member}} key.
         */
        String externalKey,
        List<String> memoryStoreIds,
        List<String> vaultIds,
        List<Map<String, Object>> resources,
        String status,
        Map<String, Object> stopReason,
        long createdAt,
        long updatedAt,
        Long archivedAt) {}

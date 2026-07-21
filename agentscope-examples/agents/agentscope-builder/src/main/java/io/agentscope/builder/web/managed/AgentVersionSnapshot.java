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
import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of versioned agent configuration fields persisted in {@link
 * io.agentscope.builder.web.persistence.jpa.AgentVersionEntity}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentVersionSnapshot(
        String name,
        String description,
        String sysPrompt,
        String model,
        Integer maxIters,
        List<String> toolsAllow,
        List<String> toolsDeny,
        String identityName,
        String identityEmoji,
        List<String> groupChatMentionPatterns,
        Boolean groupChatRequireMention,
        List<String> skillsAllow,
        List<String> skillsDeny,
        List<SkillRepositoryConfigEntry> skillRepositories,
        String sandboxMode,
        String sandboxScope,
        Map<String, String> permissionPolicies) {}

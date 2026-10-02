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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.MultiagentSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import java.util.List;

/**
 * Immutable snapshot of versioned agent configuration fields persisted in {@link
 * io.agentscope.builder.web.persistence.jpa.AgentVersionEntity}.
 *
 * <p>Unknown properties are ignored: the control-plane ({@code aistiod}) snapshot payload is a
 * superset carrying envelope fields such as {@code id} / {@code ownerId} / {@code workspacePath} /
 * {@code version} that are resolved separately.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentVersionSnapshot(
        String name,
        String description,
        String system,
        String model,
        Integer maxIters,
        List<AgentToolset> tools,
        List<McpServerSpec> mcpServers,
        List<SkillRef> skills,
        MultiagentSpec multiagent,
        String identityName,
        String identityEmoji,
        List<String> groupChatMentionPatterns,
        Boolean groupChatRequireMention,
        List<SkillRepositoryConfigEntry> skillRepositories,
        String sandboxMode,
        String sandboxScope) {}

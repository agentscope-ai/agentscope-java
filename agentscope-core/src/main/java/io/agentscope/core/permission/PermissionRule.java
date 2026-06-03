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
package io.agentscope.core.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * A single permission rule pinned to a tool name.
 *
 * <p>{@code ruleContent} is interpreted by the owning tool's {@code matchRule} method. When it is
 * {@code null}, the rule applies to every invocation of the named tool (tool-name-level rule).
 *
 * @param toolName the tool this rule targets (never {@code null})
 * @param ruleContent optional tool-specific matcher pattern (nullable)
 * @param behavior the resulting behavior when the rule matches (never {@code null})
 * @param source where the rule originated (e.g. {@code "userSettings"}, {@code "suggested"})
 */
public record PermissionRule(
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("rule_content") String ruleContent,
        @JsonProperty("behavior") PermissionBehavior behavior,
        @JsonProperty("source") String source) {

    @JsonCreator
    public PermissionRule {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(behavior, "behavior must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}

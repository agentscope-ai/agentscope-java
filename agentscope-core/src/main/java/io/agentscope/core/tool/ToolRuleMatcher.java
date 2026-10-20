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
package io.agentscope.core.tool;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Map;

/**
 * Optional rule matching for objects registered through {@link Tool}-annotated methods.
 * The reflective tool delegates non-empty permission rule content to this interface.
 * Implementations must be side-effect free and must not retain the per-call context.
 */
@FunctionalInterface
public interface ToolRuleMatcher {

    /**
     * Tests a tool-specific pattern against the effective input and the current call context.
     *
     * @param ruleContent non-empty rule pattern
     * @param toolInput arguments after registered preset parameters have been applied
     * @param runtimeContext current invocation context, never null
     * @return whether the rule applies
     */
    boolean matchRule(
            String ruleContent, Map<String, Object> toolInput, RuntimeContext runtimeContext);
}

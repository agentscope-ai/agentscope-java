/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.model;

/**
 * Enum defining how tools from the frontend should be merged with agent's toolkit.
 *
 * <p>The AG-UI protocol allows tools to be defined from the frontend. This enum
 * determines how those frontend-provided tools interact with the agent's existing toolkit.
 */
public enum ToolMergeMode {

    /**
     * Use only tools provided from the frontend.
     *
     * <p>The agent's existing toolkit is ignored completely.
     * Only tools passed in {@link RunAgentInput#getTools()} will be available.
     */
    FRONTEND_ONLY,

    /**
     * Use only the agent's existing toolkit.
     *
     * <p>Tools provided from the frontend are ignored.
     * Only tools registered in the agent's toolkit will be available.
     */
    AGENT_ONLY,

    /**
     * Merge frontend tools with agent's toolkit, frontend takes priority.
     *
     * <p>Both frontend tools and agent toolkit tools are available.
     * If there are name conflicts, frontend tools override agent tools.
     */
    MERGE_FRONTEND_PRIORITY
}

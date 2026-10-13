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

/**
 * Enum defining how externally injected tools (e.g. from a frontend) should be merged with an
 * agent's shared toolkit.
 *
 * <p>An external tool is by definition schema-only (no local implementation). This enum controls
 * how a per-call {@link ToolRequestConfig} is composed with the shared, stateless {@link Toolkit}
 * to produce the tool surface for one call.
 */
public enum ToolMergeMode {

    /**
     * Use only externally injected tools.
     *
     * <p>The agent's shared toolkit is ignored completely. Only the tools carried in the per-call
     * request config will be available.
     */
    EXTERNAL_ONLY,

    /**
     * Use only the agent's shared toolkit.
     *
     * <p>Externally injected tools are ignored. Only tools registered in the agent's toolkit will
     * be available.
     */
    AGENT_ONLY,

    /**
     * Merge external tools with the agent's toolkit, external takes priority.
     *
     * <p>Both external tools and agent toolkit tools are available. If there are name conflicts,
     * external tools override agent tools.
     */
    MERGE_EXTERNAL_PRIORITY
}

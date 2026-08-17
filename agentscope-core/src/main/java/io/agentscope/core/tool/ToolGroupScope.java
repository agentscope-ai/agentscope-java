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
 * Defines who manages a {@link ToolGroup}'s activation lifecycle.
 *
 * <ul>
 *   <li>{@link #META} — managed by the agent via the {@code reset_equipped_tools} meta tool.
 *       These groups appear in the meta tool's parameter enum and follow replacement semantics:
 *       only groups explicitly listed in the {@code to_activate} parameter remain active.</li>
 *   <li>{@link #EXTERNAL} — managed by developer code (e.g., programmatic activation/deactivation).
 *       These groups are invisible to the meta tool and unaffected by its calls.</li>
 * </ul>
 */
public enum ToolGroupScope {

    /**
     * The group is managed by the meta tool ({@code reset_equipped_tools}).
     * The agent can activate/deactivate it at runtime.
     */
    META,

    /**
     * The group is managed externally by developer code.
     * The meta tool cannot see or modify it.
     */
    EXTERNAL
}

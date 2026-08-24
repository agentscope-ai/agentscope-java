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

import io.agentscope.builder.web.catalog.AgentDefinition;
import java.util.Optional;

/**
 * Resolves whether an agent is visible to a user and returns its definition. Implemented by the
 * control plane's catalog service (authoritative, file + JPA backed) and by the data plane with a
 * JPA-only read view over the shared database, so both planes can apply {@link AgentAccessGuard}
 * without a cross-plane dependency.
 */
public interface AgentVisibilityResolver {

    /**
     * Returns the definition of {@code agentId} when visible to {@code userId} (global scope,
     * owned by the user, or shared in via grants), otherwise {@link Optional#empty()}.
     */
    Optional<AgentDefinition> findVisible(String userId, String agentId);
}

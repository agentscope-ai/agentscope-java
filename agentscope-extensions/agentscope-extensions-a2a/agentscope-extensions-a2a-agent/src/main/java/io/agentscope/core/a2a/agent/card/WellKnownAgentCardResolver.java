/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.core.a2a.agent.card;

import io.a2a.A2A;
import io.a2a.spec.AgentCard;
import java.util.Map;

/**
 * Agent Card Producer from well known url.
 *
 * @author xiweng.yy
 */
public class WellKnownAgentCardResolver implements AgentCardResolver {

    private final String wellKnownUrl;

    private final String relativeCardPath;

    private final Map<String, String> authHeaders;

    public WellKnownAgentCardResolver(
            String wellKnownUrl, String relativeCardPath, Map<String, String> authHeaders) {
        this.wellKnownUrl = wellKnownUrl;
        this.relativeCardPath = relativeCardPath;
        this.authHeaders = authHeaders;
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        return A2A.getAgentCard(wellKnownUrl, relativeCardPath, authHeaders);
    }
}

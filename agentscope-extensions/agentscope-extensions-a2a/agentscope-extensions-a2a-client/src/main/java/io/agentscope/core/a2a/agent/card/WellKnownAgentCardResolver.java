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

package io.agentscope.core.a2a.agent.card;

import io.a2a.A2A;
import io.a2a.spec.AgentCard;
import java.util.Map;

/**
 * Agent Card Producer from well known url.
 *
 * <p>Following <a href="https://a2a-protocol.org/latest/topics/agent-discovery/#1-well-known-uri">Well-Known URI</a> define.
 * And use {@link A2A#getAgentCard(String, String, Map)} to get {@link AgentCard}.
 *
 * <ul>
 *     <li>{@link #baseUrl} should be {@code schema://domain:port[/rootPath]}</li>
 *     <li>{@link #relativeCardPath} should be the subPath of well-known URI, default {@code /.well-known/agent-card.json}</li>
 *     <li>{@link #authHeaders} should be the authenticate headers to access agents server to get AgentCard</li>
 * </ul>
 *
 * <p> The whole well-known URI should be combined by {@link #baseUrl} and {@link #relativeCardPath}
 */
public class WellKnownAgentCardResolver implements AgentCardResolver {

    private final String baseUrl;

    private final String relativeCardPath;

    private final Map<String, String> authHeaders;

    private WellKnownAgentCardResolver(
            String baseUrl, String relativeCardPath, Map<String, String> authHeaders) {
        this.baseUrl = baseUrl;
        this.relativeCardPath = relativeCardPath;
        this.authHeaders = authHeaders;
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        return A2A.getAgentCard(baseUrl, relativeCardPath, authHeaders);
    }

    /**
     * Create a new {@link Builder} instance for {@link WellKnownAgentCardResolver}.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String baseUrl;

        private String relativeCardPath;

        private Map<String, String> authHeaders;

        private Builder() {
            this.relativeCardPath = "/.well-known/agent-card.json";
            this.authHeaders = Map.of();
        }

        /**
         * Set the base URL for the agent card resolver.
         *
         * @param baseUrl the base URL to set
         * @return the current Builder instance for method chaining
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Set the relative card path for the agent card resolver.
         *
         * @param relativeCardPath the relative card path to set
         * @return the current Builder instance for method chaining
         */
        public Builder relativeCardPath(String relativeCardPath) {
            this.relativeCardPath = relativeCardPath;
            return this;
        }

        /**
         * Set the authentication headers for the agent card resolver.
         *
         * @param authHeaders the authentication headers to set
         * @return the current Builder instance for method chaining
         */
        public Builder authHeaders(Map<String, String> authHeaders) {
            this.authHeaders = authHeaders;
            return this;
        }

        /**
         * Build the WellKnownAgentCardResolver instance.
         *
         * @return the built WellKnownAgentCardResolver instance
         */
        public WellKnownAgentCardResolver build() {
            return new WellKnownAgentCardResolver(baseUrl, relativeCardPath, authHeaders);
        }
    }
}

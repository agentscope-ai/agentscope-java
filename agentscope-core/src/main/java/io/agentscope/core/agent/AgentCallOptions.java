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
package io.agentscope.core.agent;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable configuration applied to one agent invocation through {@link RuntimeContext}.
 *
 * <p>Call-scoped middleware and external tool schemas are merged with the agent's configured
 * middleware and toolkit without modifying their shared collections. This allows a singleton agent
 * to serve requests with different instructions and tool declarations safely. Calls whose {@link
 * RuntimeContext} does not contain this type retain the agent's existing execution behavior.
 */
public final class AgentCallOptions {

    private final List<MiddlewareBase> middlewares;
    private final List<ToolSchema> externalToolSchemas;
    private final boolean includeConfiguredTools;
    private final boolean stateless;

    private AgentCallOptions(Builder builder) {
        this.middlewares = List.copyOf(builder.middlewares);
        this.externalToolSchemas = List.copyOf(builder.externalToolSchemas);
        this.includeConfiguredTools = builder.includeConfiguredTools;
        this.stateless = builder.stateless;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<MiddlewareBase> getMiddlewares() {
        return middlewares;
    }

    public List<ToolSchema> getExternalToolSchemas() {
        return externalToolSchemas;
    }

    /** Whether tools configured on the agent should also be available during this invocation. */
    public boolean isIncludeConfiguredTools() {
        return includeConfiguredTools;
    }

    /** Whether the agent should discard this invocation's session state after completion. */
    public boolean isStateless() {
        return stateless;
    }

    /** Builder for {@link AgentCallOptions}. */
    public static final class Builder {

        private final List<MiddlewareBase> middlewares = new ArrayList<>();
        private final List<ToolSchema> externalToolSchemas = new ArrayList<>();
        private boolean includeConfiguredTools = true;
        private boolean stateless;

        public Builder middleware(MiddlewareBase middleware) {
            if (middleware != null) {
                middlewares.add(middleware);
            }
            return this;
        }

        public Builder middlewares(List<? extends MiddlewareBase> middlewares) {
            if (middlewares != null) {
                middlewares.forEach(this::middleware);
            }
            return this;
        }

        public Builder externalToolSchema(ToolSchema schema) {
            if (schema != null) {
                externalToolSchemas.add(schema);
            }
            return this;
        }

        public Builder externalToolSchemas(List<ToolSchema> schemas) {
            if (schemas != null) {
                schemas.forEach(this::externalToolSchema);
            }
            return this;
        }

        /**
         * Configure whether the agent's configured tools are merged with request tools.
         *
         * @param includeConfiguredTools {@code false} to expose only external schemas supplied for
         *     this invocation
         * @return this builder
         */
        public Builder includeConfiguredTools(boolean includeConfiguredTools) {
            this.includeConfiguredTools = includeConfiguredTools;
            return this;
        }

        /**
         * Configure whether session state created for this invocation is transient.
         *
         * @param stateless {@code true} to use fresh transient state without reading, replacing, or
         *     persisting an existing session state
         * @return this builder
         */
        public Builder stateless(boolean stateless) {
            this.stateless = stateless;
            return this;
        }

        public AgentCallOptions build() {
            return new AgentCallOptions(this);
        }
    }
}

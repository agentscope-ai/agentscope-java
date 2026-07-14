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
package io.agentscope.harness.agent.sandbox;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;

/**
 * Base class for sandbox client configuration options.
 *
 * <p>Each concrete subclass describes a specific sandbox backend (e.g. Docker) and can
 * self-instantiate the corresponding {@link SandboxClient} via {@link #createClient()}.
 * This allows callers to configure only the options object and rely on
 * {@link io.agentscope.harness.agent.HarnessAgent.Builder} to derive the client automatically.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DockerSandboxClientOptions.class, name = "docker"),
})
public abstract class SandboxClientOptions {

    /**
     * Returns the type discriminator used in JSON serialization.
     *
     * @return type string (e.g. "docker")
     */
    public abstract String getType();

    /**
     * Creates the {@link SandboxClient} implementation that corresponds to these options.
     *
     * <p>Called by {@link io.agentscope.harness.agent.HarnessAgent.Builder} when no explicit
     * client has been provided, so callers only need to configure the options object.
     *
     * @return a new client instance ready for use
     */
    public abstract SandboxClient<? extends SandboxClientOptions> createClient();
}

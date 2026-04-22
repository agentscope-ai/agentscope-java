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
package io.agentscope.micronaut.properties;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for the default ReActAgent bean.
 *
 * <p>Typical {@code application.yml} configuration:
 *
 * <pre>{@code
 * agentscope:
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *     max-iters: 10
 * }</pre>
 */
@ConfigurationProperties("agentscope.agent")
public class AgentProperties {

    /**
     * Whether to create the default ReActAgent bean.
     */
    private boolean enabled = true;

    /**
     * Default agent name.
     */
    private String name = "Assistant";

    /**
     * Default system prompt used by the agent.
     */
    private String sysPrompt = "You are a helpful AI assistant.";

    /**
     * Maximum number of ReAct iterations for a single request.
     */
    private int maxIters = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }
}

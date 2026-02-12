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

package io.agentscope.spring.boot.nacos.properties;

import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentScope Nacos Prompt integration.
 *
 * <p>This configuration allows users to drive the Agent's system prompt from Nacos. The
 * Nacos-stored prompt will take precedence over the YAML-defined {@code agentscope.agent.sys-prompt},
 * while the YAML value can still be used as a default fallback.
 *
 * <p>Example configuration:
 * <pre>{@code
 * agentscope:
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *
 *   nacos:
 *     server-addr: 127.0.0.1:8848
 *     namespace: public
 *
 *     prompt:
 *       enabled: true
 *       sys-prompt-key: agent-main
 *       variables:
 *         env: prod
 *         app: order-service
 * }</pre>
 */
@ConfigurationProperties(prefix = NacosConstants.NACOS_PROMPT_PREFIX)
public class AgentScopeNacosPromptProperties extends BaseNacosProperties {

    /**
     * Whether Nacos prompt integration is enabled.
     */
    private boolean enabled = true;

    /**
     * The promptKey used to locate the system prompt in Nacos.
     * <p>The actual Nacos dataId will typically be {@code promptKey + ".json"}.
     */
    private String sysPromptKey;

    /**
     * Template variables used to render the Nacos prompt with {{}} placeholders.
     */
    private Map<String, String> variables = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSysPromptKey() {
        return sysPromptKey;
    }

    public void setSysPromptKey(String sysPromptKey) {
        this.sysPromptKey = sysPromptKey;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables;
    }
}

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
 *       version: "1.0.0"
 *       label: "prod"
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
     */
    private String sysPromptKey;

    /**
     * Target prompt version (e.g. "1.0.0"). Mutually exclusive with {@link #label}.
     * When both are null, the latest version is used.
     */
    private String version;

    /**
     * Target prompt label (e.g. "prod", "staging"). Mutually exclusive with {@link #version}.
     * When both are null, the latest version is used.
     */
    private String label;

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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables;
    }
}

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

package io.agentscope.spring.boot.a2a.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2A Server configuration for deployment.
 */
@ConfigurationProperties(Constants.A2A_SERVER_PREFIX)
public class A2aCommonProperties {

    private boolean enabled = true;

    private Integer agentCompletionTimeoutSeconds;

    private Integer consumptionCompletionTimeoutSeconds;

    public A2aCommonProperties() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getAgentCompletionTimeoutSeconds() {
        return agentCompletionTimeoutSeconds;
    }

    public void setAgentCompletionTimeoutSeconds(Integer agentCompletionTimeoutSeconds) {
        this.agentCompletionTimeoutSeconds = agentCompletionTimeoutSeconds;
    }

    public Integer getConsumptionCompletionTimeoutSeconds() {
        return consumptionCompletionTimeoutSeconds;
    }

    public void setConsumptionCompletionTimeoutSeconds(
            Integer consumptionCompletionTimeoutSeconds) {
        this.consumptionCompletionTimeoutSeconds = consumptionCompletionTimeoutSeconds;
    }
}

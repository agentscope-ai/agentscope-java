/*
 * Copyright 2024-2026 the original author or authors.
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

package io.agentscope.spring.boot.nacos.properties.a2a;

/**
 * A2a properties for discovering from Nacos A2A Discovery.
 *
 * <p>This class contains the sub properties for discovering Agents from Nacos A2A Registry. To help developers and users
 * control the discover behaviors according to their requests.
 *
 * <p>Example with yaml:
 * <pre>{@code
 * agentscope:
 *   a2a:
 *     nacos:
 *       discovery:
 *         enabled: true
 * }</pre>
 *
 * <p>The registry properties see {@link NacosA2aRegistryProperties}.
 */
public class NacosA2aDiscoveryProperties {

    /**
     * Whether to enable Nacos A2A Discovery.
     *
     * <p>Default is {@code true}, which means the program will query Nacos for agent discovery metadata, such as
     * AgentCard and A2A transport information, in order to discover other agents for agent-to-agent communication.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

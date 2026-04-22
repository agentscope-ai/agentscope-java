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

import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.nacos.properties.BaseNacosProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for AgentScope A2A Nacos integration.
 *
 * <p>This class extends {@link BaseNacosProperties} to provide Nacos configuration
 * properties specifically for AgentScope applications. It uses the Nacos prefix from
 * {@link NacosConstants#A2A_NACOS_PREFIX} for property binding.
 *
 * <p>Example with yaml following:
 * <pre>{@code
 * agentscope:
 *   a2a:
 *     nacos:
 *       server-addr: 127.0.0.1:8848
 *       namespace: public
 *       username: nacos
 *       password: nacos
 *       properties:
 *         logAllProperties: true
 * }</pre>
 *
 * <p>The priority of this class is higher than {@link AgentScopeNacosProperties} for these
 * properties defined in {@link BaseNacosProperties}. For example, configuration like following:
 * <pre>{@code
 * agentscope:
 *   nacos:
 *     server-addr: nacos.a.host:8848
 *     username: nacos
 *     password: nacos
 *   a2a:
 *     nacos:
 *       server-addr: nacos.b.host:8848
 *       password: nacos@b
 * }</pre>
 * <p> The actual server address of connecting Nacos will be {@code nacos.b.host:8848} with username {@code nacos} and
 * password {@code nacos@b}.
 */
@ConfigurationProperties(prefix = NacosConstants.A2A_NACOS_PREFIX)
public class AgentScopeA2aNacosProperties extends BaseNacosProperties {

    /**
     * Some sub properties for nacos a2a registry.
     *
     * <p>These sub properties can control the behavior for registering agent or agent endpoint to Nacos. Detail see
     * {@link NacosA2aRegistryProperties}.
     */
    private NacosA2aRegistryProperties registry = new NacosA2aRegistryProperties();

    /**
     * Some sub properties for nacos a2a discovery.
     *
     * <p>These sub properties can control the behavior for discovering agent or agent endpoint from Nacos. Detail see
     * {@link NacosA2aDiscoveryProperties}.
     */
    private NacosA2aDiscoveryProperties discovery = new NacosA2aDiscoveryProperties();

    public NacosA2aRegistryProperties getRegistry() {
        return registry;
    }

    public void setRegistry(NacosA2aRegistryProperties registry) {
        this.registry = registry;
    }

    public NacosA2aDiscoveryProperties getDiscovery() {
        return discovery;
    }

    public void setDiscovery(NacosA2aDiscoveryProperties discovery) {
        this.discovery = discovery;
    }
}

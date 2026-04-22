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

package io.agentscope.examples.bobatea.business.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nacos MCP Service Configuration.
 * Provides AiService bean for MCP server registration to Nacos.
 *
 */
@Configuration
@ConditionalOnProperty(
        name = "agentscope.mcp.nacos.register.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NacosMcpServiceConfig {

    private static final Logger logger = LoggerFactory.getLogger(NacosMcpServiceConfig.class);

    @Value("${agentscope.mcp.nacos.server-addr}")
    private String serverAddress;

    @Value("${agentscope.mcp.nacos.namespace}")
    private String namespace;

    @Bean
    public AiService aiService() throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddress);
        properties.put(PropertyKeyConst.NAMESPACE, namespace);
        logger.info("Nacos MCP Service Config: {}", properties);
        return AiFactory.createAiService(properties);
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}

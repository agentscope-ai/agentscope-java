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

package io.agentscope.examples.a2a;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.a2a.tools.ExampleTools;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * A2A(Agent2Agent) Protocol Example Application.
 *
 * <p>This application demonstrates how to expose AgentScope agents via the A2A protocol
 * using Spring Web.
 *
 * <p><b>Usage:</b>
 * <ol>
 *   <li>Set the DASHSCOPE_API_KEY environment variable</li>
 *   <li>Run this application</li>
 *   <li>Use curl: {@code curl -X GET http://localhost:8888/.well-known/agent-card.json} to get AgentCard.</li>
 *   <li>Use curl: {@code curl --location --request POST 'http://localhost:8888' \
 *   --header 'Content-Type: application/json' \
 *   --data-raw '{
 *     "method": "message/stream",
 *     "id": "2d2b4dc8-8ea2-437b-888d-3aaf3a8239dc",
 *     "jsonrpc": "2.0",
 *     "params": {
 *       "message": {
 *         "role": "user",
 *         "kind": "message",
 *         "contextId": "aa9c67d2-c6fc-42d4-9c9e-b67d69f77cfa",
 *         "metadata": {
 *           "userId": "me",
 *           "sessionId": "test12"
 *         },
 *         "parts": [
 *           {
 *             "kind": "text",
 *             "text": "Hello, please calculate the 346 * 47"
 *           }
 *         ],
 *         "messageId": "c4911b64c8404b7a8bf7200dd225b152"
 *       }
 *     }
 *   }'}</li>
 *   <li>Or run {@link SimpleA2aAgentExample#main(String[])} and input question like: {@code Hello, please calculate the 346 * 47}</li>
 * </ol>
 *
 * <p>This example application also demonstrates how to register agents to Nacos A2A registry.
 *
 * <p><b>Usage: </b>
 * <ol>
 *     <li>Set the DASHSCOPE_API_KEY environment variable.</li>
 *     <li>Enabled Nacos Registry by set Nacos environment variable: {@code NACOS_ENABLED=true}.</li>
 *     <li>Start Nacos Server according to <a href="https://nacos.io/en/docs/latest/quickstart/quick-start-docker">documents</a>.</li>
 *     <li>Set more Nacos environment variables: NACOS_SERVER_ADDR, NACOS_USERNAME and NACOS_PASSWORD if Nacos Server is not in local and enabled authentication.</li>
 *     <li>Run this application</li>
 *     <li>Run Example {@link NacosA2aAgentExample#main(String[])} and input question like: {@code Hello, please calculate the 346 * 47}</li>
 * </ol>
 */
@SpringBootApplication
public class A2aExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(A2aExampleApplication.class, args);
    }

    /**
     * Optional, if you want to register tools for the agent.
     */
    @Bean
    public Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());
        return toolkit;
    }
}

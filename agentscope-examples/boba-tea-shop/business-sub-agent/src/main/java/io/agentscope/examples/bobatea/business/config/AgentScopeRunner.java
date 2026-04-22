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

import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Event;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.bobatea.business.utils.MonitoringHook;
import io.agentscope.extensions.nacos.mcp.NacosMcpServerManager;
import io.agentscope.extensions.nacos.mcp.client.NacosMcpClientBuilder;
import io.agentscope.extensions.nacos.mcp.client.NacosMcpClientWrapper;
import io.agentscope.extensions.nacos.mcp.tool.NacosToolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

@Configuration
public class AgentScopeRunner {

    @Value("${agentscope.dashscope.api-key}")
    String apiKey;

    @Value("${agentscope.dashscope.model-name}")
    String modelName;

    private static final Logger logger = LoggerFactory.getLogger(AgentScopeRunner.class);

    @Bean
    public AgentRunner agentRunner(
            AgentPromptConfig promptConfig, AiService aiService, Model model) {

        Toolkit toolkit = new NacosToolkit();

        AutoContextConfig autoContextConfig =
                AutoContextConfig.builder().tokenRatio(0.4).lastKeep(10).build();
        // Use AutoContextMemory, support context auto compression
        AutoContextMemory memory = new AutoContextMemory(autoContextConfig, model);

        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("business_agent")
                        .sysPrompt(promptConfig.getBusinessAgentInstruction())
                        .memory(memory)
                        .hooks(List.of(new MonitoringHook()))
                        .model(model)
                        .toolkit(toolkit);

        return new CustomAgentRunner(builder, aiService, toolkit);
    }

    private static class CustomAgentRunner implements AgentRunner {

        private static final Pattern USER_ID_PATTERN = Pattern.compile("<userId>(.+?)</userId>");

        private final ReActAgent.Builder agentBuilder;

        private final AiService aiService;

        private final Toolkit toolkit;

        private final Map<String, ReActAgent> agentCache;

        private volatile boolean mcpInitialized = false;

        private CustomAgentRunner(
                ReActAgent.Builder agentBuilder, AiService aiService, Toolkit toolkit) {
            this.agentBuilder = agentBuilder;
            this.aiService = aiService;
            this.toolkit = toolkit;
            this.agentCache = new ConcurrentHashMap<>();
        }

        private ReActAgent buildReActAgent() {
            return agentBuilder.build();
        }

        private ReActAgent buildReActAgent(String userId) {
            initializeMcpOnce();
            Mem0LongTermMemory longTermMemory =
                    Mem0LongTermMemory.builder()
                            .agentName("BusinessAgent")
                            .userId(userId)
                            .apiBaseUrl("https://api.mem0.ai")
                            .apiKey(System.getenv("MEM0_API_KEY"))
                            .build();
            return agentBuilder.longTermMemory(longTermMemory).build();
        }

        private void initializeMcpOnce() {
            if (!mcpInitialized) {
                synchronized (this) {
                    if (!mcpInitialized) {
                        try {
                            NacosMcpServerManager mcpServerManager =
                                    new NacosMcpServerManager(aiService);
                            NacosMcpClientWrapper mcpClientWrapper =
                                    NacosMcpClientBuilder.create(
                                                    "business-mcp-server", mcpServerManager)
                                            .build();
                            toolkit.registerMcpClient(mcpClientWrapper).block();
                            mcpInitialized = true;
                        } catch (Exception e) {
                            // Log the error and continue without MCP tools
                            logger.warn(
                                    "Failed to initialize MCP client: "
                                            + e.getMessage()
                                            + " , will try later.");
                        }
                    }
                }
            }
        }

        @Override
        public String getAgentName() {
            return buildReActAgent().getName();
        }

        @Override
        public String getAgentDescription() {
            return buildReActAgent().getDescription();
        }

        @Override
        public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
            if (agentCache.containsKey(options.getTaskId())) {
                throw new IllegalStateException(
                        "Agent already exists for taskId: " + options.getTaskId());
            }
            // Since A2A extension's metadata support is not yet complete, temporarily pass userId
            // through the msg itself
            String userId = parseUserIdFromMessages(requestMessages);
            ReActAgent agent = buildReActAgent(userId);
            agentCache.put(options.getTaskId(), agent);
            agent.getMemory()
                    .addMessage(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(
                                            TextBlock.builder()
                                                    .text("<userId>" + userId + "</userId>")
                                                    .build())
                                    .build());
            return agent.stream(requestMessages)
                    .doFinally(signal -> agentCache.remove(options.getTaskId()));
        }

        private String parseUserIdFromMessages(List<Msg> requestMessages) {
            for (Msg msg : requestMessages) {
                if (msg.getContent() == null) {
                    continue;
                }
                for (var block : msg.getContent()) {
                    if (block instanceof TextBlock textBlock) {
                        String text = textBlock.getText();
                        if (text != null) {
                            Matcher matcher = USER_ID_PATTERN.matcher(text);
                            if (matcher.find()) {
                                return matcher.group(1).trim();
                            }
                        }
                    }
                }
            }
            return "default_userId";
        }

        @Override
        public void stop(String taskId) {
            ReActAgent agent = agentCache.remove(taskId);
            if (null != agent) {
                agent.interrupt();
            }
        }
    }
}

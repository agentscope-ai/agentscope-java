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

package io.agentscope.examples.web;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class SpringWebExample {
    private static final Logger log = LoggerFactory.getLogger(SpringWebExample.class);

    @RestController
    public static class WebController implements InitializingBean {
        private String dashApiKey;

        @Override
        public void afterPropertiesSet() throws Exception {
            dashApiKey = System.getenv("DASHSCOPE_API_KEY");
            if (dashApiKey == null || dashApiKey.isEmpty()) {
                log.warn("No API key found. Please set DASHSCOPE_API_KEY.");
                throw new IllegalArgumentException("No API key found. Please set DASHSCOPE_API_KEY.");
            }
        }

        @RequestMapping(path = "/chat", produces = "text/event-stream")
        public Flux<Msg> chat(@RequestParam(value = "sessionId", defaultValue = "default_session") String sessionId, @RequestParam("message") String message) {
            Toolkit toolkit = new Toolkit();
            InMemoryMemory memory = new InMemoryMemory();
            JsonSession session = new JsonSession(
                    Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions"));

            ReActAgent.Builder builder = ReActAgent.builder()
                    .name("Friday")
                    .sysPrompt("You are a helpful assistant named Friday.")
                    .toolkit(toolkit)
                    .memory(memory)
                    .model(DashScopeChatModel.builder()
                            .apiKey(dashApiKey)
                            .modelName("qwen-max")
                            .stream(true)
                            .enableThinking(false)
                            .defaultOptions(new GenerateOptions())
                            .build())
                    .formatter(new DashScopeChatFormatter());

            ReActAgent agent = builder.build();

            // Create state modules map for session management
            Map<String, StateModule> stateModules = new HashMap<>();
            stateModules.put("agent", agent);
            stateModules.put("memory", memory);

            // Load existing session if it exists
            if (session.sessionExists(sessionId)) {
                session.loadSessionState(sessionId, stateModules);
                log.info("Session loaded: {} ({} messages)", sessionId, memory.getMessages().size());
            } else {
                log.info("New session: {}", sessionId);
            }
            return agent.stream(Msg.builder().role(MsgRole.USER).textContent(message).build())
                    .doFinally(__ -> session.saveSessionState(sessionId, stateModules));
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(SpringWebExample.class, args);
    }
}

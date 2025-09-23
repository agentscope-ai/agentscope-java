/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * A minimal ReAct agent example mirroring the Python react_agent example.
 * <p>
 * - Uses DashScope by default if DASHSCOPE_API_KEY is set; otherwise OpenAI if OPENAI_API_KEY is set.
 * - Reads user input from terminal; type "exit" to quit.
 */
class ReActAgentStreamExample {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentStreamExample.class);

    public static void main(String[] args) throws Exception {
        String dashApiKey = System.getenv("DASHSCOPE_API_KEY");

        if (dashApiKey == null || dashApiKey.isEmpty()) {
            log.warn("No API key found. Please set DASHSCOPE_API_KEY.");
            return;
        }

        Toolkit toolkit = new Toolkit();
        InMemoryMemory memory = new InMemoryMemory();

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

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("User> ");
            String line = reader.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.trim())) {
                break;
            }
            Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(line).build();
            Flux<Msg> stream = agent.stream(userMsg);
            stream.subscribe(msg -> System.out.println("Friday> " + msg.getContentAsText()));
            stream.blockLast();
        }

        log.info("Bye.");
    }
}



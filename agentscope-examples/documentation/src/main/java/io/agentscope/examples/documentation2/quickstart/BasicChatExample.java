/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * BasicChatExample - The simplest Agent conversation example.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Creating an agent with {@code model("dashscope:qwen-plus")} (ModelRegistry auto-resolves
 *       the provider and reads API key from env)</li>
 *   <li>Interactive streaming chat via {@code streamEvents()}</li>
 *   <li>Incremental text output using {@link TextBlockDeltaEvent}</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.quickstart.BasicChatExample
 * </pre>
 */
public class BasicChatExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Get your API key from: https://dashscope.aliyun.com");
            System.err.println("Then set it with: export DASHSCOPE_API_KEY=your_api_key");
            System.exit(1);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Basic Chat Example");
        System.out.println("=".repeat(60));
        System.out.println("A simple interactive chat with streaming output.");
        System.out.println("Type 'exit' to quit.\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .model("dashscope:qwen-plus")
                        .toolkit(new Toolkit())
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();

            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }

            Msg userMsg = new UserMessage(input.trim());

            System.out.print("\nAssistant: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }
}

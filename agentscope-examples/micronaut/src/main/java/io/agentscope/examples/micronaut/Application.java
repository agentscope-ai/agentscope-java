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

package io.agentscope.examples.micronaut;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.micronaut.context.ApplicationContext;

/**
 * Micronaut Integration Example - demonstrates AgentScope with Micronaut dependency injection.
 *
 * <p>This example shows how to:
 * <ul>
 *   <li>Use Micronaut ApplicationContext for dependency injection
 *   <li>Configure AgentScope via application.yml
 *   <li>Inject ReActAgent from Micronaut factory
 * </ul>
 *
 * <p>Run with:
 * <pre>
 * DASHSCOPE_API_KEY=your-key mvn exec:java
 * </pre>
 */
public class Application {

    public static void main(String[] args) {
        System.out.println("\n=== AgentScope Micronaut Example ===\n");

        // Start Micronaut ApplicationContext
        try (ApplicationContext context = ApplicationContext.run()) {
            // Get ReActAgent from Micronaut - configured via application.yml
            ReActAgent agent = context.getBean(ReActAgent.class);

            System.out.println("✓ Agent created via Micronaut dependency injection");
            System.out.println("✓ Configuration loaded from application.yml");
            System.out.println("\nStarting chat...\n");

            // Simple chat example
            String[] questions = {
                "What is AgentScope?", "What programming languages does it support?", "Thank you!"
            };

            for (String question : questions) {
                System.out.println("User: " + question);

                // Create user message
                Msg userMsg =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(question).build())
                                .build();

                // Call agent and get response
                Msg response = agent.call(userMsg).block();
                System.out.println("Agent: " + response.getContent());
                System.out.println();
            }

            System.out.println("=== Example completed ===\n");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

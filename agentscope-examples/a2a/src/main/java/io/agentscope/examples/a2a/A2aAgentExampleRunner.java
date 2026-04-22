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

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import reactor.core.publisher.Flux;

/**
 * Example runner for A2aAgent usage.
 *
 * <p>This example demonstrates how to request remote agent via the A2A protocol
 * using AgentScope A2aAgent.
 */
public class A2aAgentExampleRunner {

    private static final String USER_INPUT_PREFIX = "\u001B[34mYou>\u001B[0m ";

    private static final String AGENT_RESPONSE_PREFIX = "\u001B[32mAgent>\u001B[0m ";

    private final A2aAgent agent;

    public A2aAgentExampleRunner(A2aAgent agent) {
        this.agent = agent;
    }

    /**
     * Start to run the example for A2aAgent.
     */
    public void startExample() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                // User Input Hint.
                System.out.print(USER_INPUT_PREFIX);
                String input = reader.readLine();

                // Exit example.
                if (input == null
                        || input.trim().equalsIgnoreCase("exit")
                        || input.trim().equalsIgnoreCase("quit")) {
                    System.out.println(AGENT_RESPONSE_PREFIX + "Bye!");
                    break;
                }

                System.out.println(
                        AGENT_RESPONSE_PREFIX + "I have received your question: " + input);
                System.out.print(AGENT_RESPONSE_PREFIX);

                // Handle user input and get response.
                processInput(agent, input).doOnNext(System.out::print).then().block();

                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("input error: " + e.getMessage());
        }
    }

    private Flux<String> processInput(A2aAgent agent, String input) {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(input).build())
                        .build();
        return agent.stream(msg)
                .map(
                        event -> {
                            if (event.isLast()) {
                                // The last message is whole artifact message result, which has been
                                // processed and printed in the previous event handling.
                                return "";
                            }
                            Msg message = event.getMessage();
                            StringBuilder partText = new StringBuilder();
                            message.getContent().stream()
                                    .filter(block -> block instanceof TextBlock)
                                    .map(block -> (TextBlock) block)
                                    .forEach(block -> partText.append(block.getText()));
                            return partText.toString();
                        });
    }
}

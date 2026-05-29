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

package io.agentscope.examples.a2a.rocketmq.client;

import io.a2a.client.http.JdkA2AHttpClient;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.agentscope.core.a2a.agent.A2aAgentConfig.A2aAgentConfigBuilder;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import org.apache.rocketmq.a2a.transport.config.RocketMQTransportConfig;
import org.apache.rocketmq.a2a.transport.impl.RocketMQTransport;
import reactor.core.publisher.Flux;

/**
 * A command-line example demonstrating how to interact with an A2A agent via RocketMQ.
 * Supports streaming responses and runs as an interactive chat interface.
 */
public class A2aAgentCallerExample {
    /**
     * ANSI color codes for terminal output
     */
    private static final String USER_INPUT_PREFIX =
            "\u001B[34mYou>\u001B[0m "; // Blue prefix for user input

    private static final String AGENT_RESPONSE_PREFIX =
            "\u001B[32mAgent>\u001B[0m "; // Green prefix for agent response
    private static final String TARGET_SERVER_BASE_URL = "http://localhost:8080";

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK");

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    private static final String SECRET_KEY = System.getProperty("rocketMQSK");

    /**
     * The dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     */
    private static final String WORK_AGENT_RESPONSE_TOPIC =
            System.getProperty("workAgentResponseTopic");

    /**
     * The consumer group ID used when subscribing to the {@link #WORK_AGENT_RESPONSE_TOPIC}.
     */
    private static final String WORK_AGENT_RESPONSE_GROUP_ID =
            System.getProperty("workAgentResponseGroupID");

    /**
     * The namespace used for logical isolation of RocketMQ resources.
     */
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace");

    /**
     * Logical name of this agent instance.
     */
    private static final String AGENT_NAME = "agentscope-a2a-rocketmq-example-agent";

    /**
     * Main entry point.
     * Sets up the agent with RocketMQ-based transport and starts the interactive loop.
     */
    public static void main(String[] args) {
        RocketMQTransportConfig rocketMQTransportConfig = new RocketMQTransportConfig();
        rocketMQTransportConfig.setAccessKey(ACCESS_KEY);
        rocketMQTransportConfig.setSecretKey(SECRET_KEY);
        rocketMQTransportConfig.setWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
        rocketMQTransportConfig.setWorkAgentResponseGroupID(WORK_AGENT_RESPONSE_GROUP_ID);
        rocketMQTransportConfig.setNamespace(ROCKETMQ_NAMESPACE);
        rocketMQTransportConfig.setHttpClient(new JdkA2AHttpClient());
        A2aAgentConfig a2aAgentConfig =
                new A2aAgentConfigBuilder()
                        .withTransport(RocketMQTransport.class, rocketMQTransportConfig)
                        .build();
        A2aAgent agent =
                A2aAgent.builder()
                        .a2aAgentConfig(a2aAgentConfig)
                        .name(AGENT_NAME)
                        .agentCardResolver(
                                WellKnownAgentCardResolver.builder()
                                        .baseUrl(TARGET_SERVER_BASE_URL)
                                        .build())
                        .build();
        startExample(agent);
    }

    /**
     * Starts an interactive CLI loop where the user can send messages to the agent.
     * Type 'exit' or 'quit' to terminate.
     *
     * @param agent the A2A agent to communicate with
     */
    private static void startExample(A2aAgent agent) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print(USER_INPUT_PREFIX);
                String input = reader.readLine();
                if (input == null
                        || input.trim().equalsIgnoreCase("exit")
                        || input.trim().equalsIgnoreCase("quit")) {
                    System.out.println(AGENT_RESPONSE_PREFIX + "Bye！");
                    break;
                }
                System.out.println(
                        AGENT_RESPONSE_PREFIX + "I have received your question: " + input);
                System.out.print(AGENT_RESPONSE_PREFIX);
                processInput(agent, input).doOnNext(System.out::print).then().block();
                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("input error: " + e.getMessage());
        }
    }

    /**
     * Sends the user input to the agent and returns a reactive stream of response parts.
     * Filters only text content from the streaming events.
     *
     * @param agent the agent to call.
     * @param input the user's input message.
     * @return a Flux emitting incremental string parts of the response.
     */
    private static Flux<String> processInput(A2aAgent agent, String input) {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(input).build())
                        .build();
        return agent.stream(msg)
                .map(
                        event -> {
                            if (event.isLast()) {
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

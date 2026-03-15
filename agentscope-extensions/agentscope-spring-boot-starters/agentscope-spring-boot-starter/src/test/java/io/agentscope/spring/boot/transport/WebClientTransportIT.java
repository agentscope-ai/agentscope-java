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
package io.agentscope.spring.boot.transport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.spring.boot.e2e.E2ETestCondition;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * E2E tests for {@link WebClientTransport}.
 *
 * <p>Tagged as "e2e" - these tests make real API calls and may incur costs.
 */
@Tag("e2e")
@ExtendWith(E2ETestCondition.class)
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class WebClientTransportIT {

    private WebClientTransport transport;
    private String apiKey;

    @BeforeEach
    void setUp() {
        transport = WebClientTransport.builder().build();
        apiKey = System.getenv("DASHSCOPE_API_KEY");
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    @DisplayName("Should call successful with WebClientTransport for DashScopeModel")
    void testDashScopeModelStreamUseWebClientTransport() {
        DashScopeChatModel model = createDashScopeChatModel();

        Flux<ChatResponse> responseFlux =
                model.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("What is AgentScope?")
                                        .build()),
                        null,
                        null);
        StepVerifier.create(responseFlux)
                .thenConsumeWhile(
                        Objects::nonNull,
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            System.out.println(extractTextContent(response.getContent()));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should successful with WebClientTransport for ReactAgent call")
    void testReactAgentCallUseWebClientTransport() {
        DashScopeChatModel model = createDashScopeChatModel();

        ReActAgent agent =
                ReActAgent.builder()
                        .model(model)
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .maxIters(3)
                        .build();

        Mono<Msg> msgMono =
                agent.call(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("What is AgentScope?")
                                        .build()));
        StepVerifier.create(msgMono)
                .assertNext(
                        msg -> {
                            assertNotNull(msg);
                            assertNotNull(msg.getContent());
                            System.out.println(msg.getTextContent());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should successful with WebClientTransport for ReactAgent stream")
    void testReactAgentStreamUseWebClientTransport() {
        DashScopeChatModel model = createDashScopeChatModel();

        ReActAgent agent =
                ReActAgent.builder()
                        .model(model)
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .maxIters(3)
                        .build();

        Flux<Event> eventFlux =
                agent.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("What is AgentScope?")
                                        .build()));
        StepVerifier.create(eventFlux)
                .thenConsumeWhile(
                        Objects::nonNull,
                        event -> {
                            assertNotNull(event);
                            Msg msg = event.getMessage();
                            assertNotNull(msg);
                            assertNotNull(msg.getContent());
                            System.out.println(extractTextContent(msg.getContent()));
                        })
                .verifyComplete();
    }

    private String extractTextContent(List<ContentBlock> content) {
        return content.stream()
                .map(
                        cb -> {
                            if (cb instanceof ThinkingBlock tb) {
                                return tb.getThinking();
                            }
                            if (cb instanceof TextBlock tb) {
                                return tb.getText();
                            }
                            return "";
                        })
                .collect(Collectors.joining("\n"));
    }

    private DashScopeChatModel createDashScopeChatModel() {
        return DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen3.5-plus").stream(true)
                .enableThinking(true)
                .formatter(new DashScopeChatFormatter())
                .httpTransport(transport)
                .build();
    }
}

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
package io.agentscope.spring.boot.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.a2a.client.config.ClientConfig;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * End-to-end tests that run an {@link A2aAgent} against the starter's HTTP endpoints.
 *
 * <p>The server side uses a scripted {@link AgentRunner}, so the tests exercise card discovery,
 * JSON-RPC, SSE and the message conversion on both sides without a model.
 */
@SpringBootTest(
        classes = A2aHttpEndToEndTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class A2aHttpEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // The agent card URL is built from server.port when the server bean is created, so the port
    // has to be known up front instead of using RANDOM_PORT.
    private static final int PORT = findFreePort();

    private static final String BASE_URL = "http://127.0.0.1:" + PORT;

    @DynamicPropertySource
    static void serverProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("agentscope.a2a.server.require-inner-message", () -> true);
    }

    @Autowired private ScriptedAgentRunner runner;

    @BeforeEach
    void resetRunner() {
        runner.reset();
    }

    @Test
    void discoversAgentCardOverHttp() {
        AgentCard card = cardResolver().getAgentCard("remote");

        assertThat(card.name()).isEqualTo(ScriptedAgentRunner.NAME);
        assertThat(card.description()).isEqualTo(ScriptedAgentRunner.DESCRIPTION);
        assertThat(card.url()).contains("127.0.0.1:" + PORT);
        assertThat(card.capabilities().streaming()).isTrue();
    }

    @Test
    void streamingCallReturnsAgentReply() {
        runner.script(messages -> reply("Hello, ", "world"));

        Msg result = newAgent(null).call(userMsg("hi")).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.getTextContent()).isEqualTo("Hello, world");
        assertThat(runner.requests()).hasSize(1);
        assertThat(texts(runner.requests().get(0))).containsExactly("hi");
    }

    @Test
    void blockingCallReturnsAgentReply() {
        runner.script(messages -> reply("Hello, ", "world"));
        ClientConfig blocking = new ClientConfig.Builder().setStreaming(false).build();

        Msg result = newAgent(blocking).call(userMsg("hi")).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.getTextContent()).isEqualTo("Hello, world");
    }

    @Test
    void streamingCallKeepsThinkingBlocks() {
        runner.script(
                messages ->
                        Flux.just(
                                new ThinkingBlockDeltaEvent("reply-1", "think-1", "let me think"),
                                new TextBlockDeltaEvent("reply-1", "text-1", "done"),
                                new AgentResultEvent(assistantMsg("done"))));

        Msg result = newAgent(null).call(userMsg("hi")).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.getContentBlocks(ThinkingBlock.class))
                .extracting(ThinkingBlock::getThinking)
                .containsExactly("let me think");
        assertThat(result.getTextContent()).isEqualTo("done");
    }

    @Test
    @Disabled(
            "SDK 0.3: DataPartParser cannot rebuild tool results after the round trip (null"
                    + " metadata values, then #1336); re-enable when migrating to SDK 1.x")
    void streamingCallDeliversToolResults() {
        runner.script(
                messages ->
                        Flux.just(
                                new ToolResultTextDeltaEvent(
                                        "reply-1", "call-1", "get_weather", "sunny"),
                                new TextBlockDeltaEvent("reply-1", "text-1", "It is sunny"),
                                new AgentResultEvent(assistantMsg("It is sunny"))));

        Msg result = newAgent(null).call(userMsg("weather?")).block(TIMEOUT);

        assertThat(result).isNotNull();
        List<ToolResultBlock> toolResults = result.getContentBlocks(ToolResultBlock.class);
        assertThat(toolResults).hasSize(1);
        ToolResultBlock toolResult = toolResults.get(0);
        assertThat(toolResult.getId()).isEqualTo("call-1");
        assertThat(toolResult.getName()).isEqualTo("get_weather");
        assertThat(toolResult.getOutput())
                .singleElement()
                .isInstanceOfSatisfying(
                        TextBlock.class, text -> assertThat(text.getText()).isEqualTo("sunny"));
        assertThat(result.getTextContent()).contains("It is sunny");
    }

    @Test
    void remoteFailureIsReportedToCaller() {
        runner.script(messages -> Flux.error(new IllegalStateException("boom")));

        Msg result = newAgent(null).call(userMsg("hi")).block(TIMEOUT);

        assertThat(result).isNotNull();
        // SDK 0.3 renders the status message with Object#toString, so only the state survives.
        assertThat(result.getTextContent()).contains("FAILED");
    }

    @Test
    void secondCallSendsConversationHistory() {
        runner.script(messages -> reply("first answer"));
        A2aAgent agent = newAgent(null);
        agent.call(userMsg("first question")).block(TIMEOUT);

        runner.script(messages -> reply("second answer"));
        Msg result = agent.call(userMsg("second question")).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.getTextContent()).isEqualTo("second answer");
        assertThat(runner.requests()).hasSize(2);
        assertThat(String.join("\n", texts(runner.requests().get(1))))
                .contains("first question", "first answer", "second question");
    }

    @Test
    void interruptCancelsRemoteTask() throws InterruptedException {
        Sinks.Empty<Void> release = Sinks.empty();
        runner.script(
                messages ->
                        Flux.<AgentEvent>just(new TextBlockDeltaEvent("reply-1", "text-1", "..."))
                                .concatWith(
                                        Flux.<AgentEvent>never().takeUntilOther(release.asMono())));
        // Interrupt only after the client has seen the task, otherwise there is nothing to cancel.
        CountDownLatch chunkReceived = new CountDownLatch(1);
        A2aAgent agent =
                A2aAgent.builder()
                        .name("remote")
                        .agentCardResolver(cardResolver())
                        .hook(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof ReasoningChunkEvent) {
                                            chunkReceived.countDown();
                                        }
                                        return Mono.just(event);
                                    }
                                })
                        .build();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            agent.call(userMsg("long task")).doFinally(signal -> finished.countDown()).subscribe();
            assertThat(chunkReceived.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            agent.interrupt();

            assertThat(runner.awaitStop(TIMEOUT)).isTrue();
            assertThat(runner.stoppedTaskIds()).hasSize(1);
            assertThat(finished.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } finally {
            release.tryEmitEmpty();
        }
    }

    private static A2aAgent newAgent(ClientConfig clientConfig) {
        A2aAgent.Builder builder =
                A2aAgent.builder().name("remote").agentCardResolver(cardResolver());
        if (clientConfig != null) {
            builder.a2aAgentConfig(A2aAgentConfig.builder().clientConfig(clientConfig).build());
        }
        return builder.build();
    }

    private static WellKnownAgentCardResolver cardResolver() {
        return WellKnownAgentCardResolver.builder().baseUrl(BASE_URL).build();
    }

    private static Flux<AgentEvent> reply(String... chunks) {
        List<AgentEvent> events = new ArrayList<>();
        for (String chunk : chunks) {
            events.add(new TextBlockDeltaEvent("reply-1", "text-1", chunk));
        }
        events.add(new AgentResultEvent(assistantMsg(String.join("", chunks))));
        return Flux.fromIterable(events);
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistantMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static List<String> texts(List<Msg> messages) {
        return messages.stream().map(Msg::getTextContent).toList();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port available", e);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        ScriptedAgentRunner scriptedAgentRunner() {
            return new ScriptedAgentRunner();
        }
    }

    static class ScriptedAgentRunner implements AgentRunner {

        static final String NAME = "scripted-agent";

        static final String DESCRIPTION = "Agent with scripted replies for A2A tests";

        private final List<List<Msg>> requests = new CopyOnWriteArrayList<>();

        private final List<String> stoppedTaskIds = new CopyOnWriteArrayList<>();

        private volatile Function<List<Msg>, Flux<AgentEvent>> script = messages -> Flux.empty();

        private volatile CountDownLatch stopped = new CountDownLatch(1);

        void script(Function<List<Msg>, Flux<AgentEvent>> script) {
            this.script = script;
        }

        void reset() {
            requests.clear();
            stoppedTaskIds.clear();
            stopped = new CountDownLatch(1);
            script = messages -> Flux.empty();
        }

        List<List<Msg>> requests() {
            return requests;
        }

        List<String> stoppedTaskIds() {
            return stoppedTaskIds;
        }

        boolean awaitStop(Duration timeout) throws InterruptedException {
            return stopped.await(timeout.toSeconds(), TimeUnit.SECONDS);
        }

        @Override
        public String getAgentName() {
            return NAME;
        }

        @Override
        public String getAgentDescription() {
            return DESCRIPTION;
        }

        @Override
        public Flux<AgentEvent> streamEvents(
                List<Msg> requestMessages, AgentRequestOptions options) {
            requests.add(List.copyOf(requestMessages));
            return script.apply(requestMessages);
        }

        @Override
        public void stop(String taskId) {
            stoppedTaskIds.add(taskId);
            stopped.countDown();
        }
    }
}

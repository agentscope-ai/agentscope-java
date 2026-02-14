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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.live.LiveAgentState;
import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.model.LiveModel;
import io.agentscope.core.live.session.ConnectionStateEvent;
import io.agentscope.core.live.session.LiveSession;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Unit tests for LiveAgent. */
@DisplayName("LiveAgent Tests")
class LiveAgentTest {

    private LiveModel mockModel;
    private LiveSession mockSession;

    @BeforeEach
    void setUp() {
        mockModel = mock(LiveModel.class);
        mockSession = mock(LiveSession.class);

        when(mockModel.getProviderName()).thenReturn("test");
        when(mockModel.supportsNativeRecovery()).thenReturn(false);
        when(mockSession.isActive()).thenReturn(true);
        when(mockSession.getProviderName()).thenReturn("test");
        when(mockSession.send(any(Msg.class))).thenReturn(Mono.empty());
        when(mockSession.close()).thenReturn(Mono.empty());
        when(mockSession.connectionStateChanges()).thenReturn(Flux.empty());
    }

    @Test
    @DisplayName("Should build agent with required parameters")
    void shouldBuildAgentWithRequiredParameters() {
        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        assertEquals("test-agent", agent.getName());
        assertEquals(LiveAgentState.DISCONNECTED, agent.getState());
    }

    @Test
    @DisplayName("Should build agent with all parameters")
    void shouldBuildAgentWithAllParameters() {
        LiveAgent agent =
                LiveAgent.builder()
                        .name("test-agent")
                        .description("A test agent")
                        .systemPrompt("You are a helpful assistant.")
                        .liveModel(mockModel)
                        .build();

        assertEquals("test-agent", agent.getName());
        assertEquals("A test agent", agent.getDescription());
    }

    @Test
    @DisplayName("Should throw when name is missing")
    void shouldThrowWhenNameIsMissing() {
        assertThrows(
                NullPointerException.class, () -> LiveAgent.builder().liveModel(mockModel).build());
    }

    @Test
    @DisplayName("Should throw when name is blank")
    void shouldThrowWhenNameIsBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LiveAgent.builder().name("   ").liveModel(mockModel).build());
    }

    @Test
    @DisplayName("Should throw when liveModel is missing")
    void shouldThrowWhenLiveModelIsMissing() {
        assertThrows(
                NullPointerException.class, () -> LiveAgent.builder().name("test-agent").build());
    }

    @Test
    @DisplayName("Should connect and receive events")
    void shouldConnectAndReceiveEvents() {
        // Setup mock to return event stream
        when(mockModel.connect(any(LiveConfig.class), any())).thenReturn(Mono.just(mockSession));
        when(mockSession.receive())
                .thenReturn(
                        Flux.just(
                                LiveEvent.sessionCreated(null),
                                LiveEvent.textDelta(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(TextBlock.builder().text("Hello").build())
                                                .build(),
                                        false),
                                LiveEvent.turnComplete(null)));

        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        Flux<Msg> input = Flux.empty();

        // Filter to only session events (not agent state events)
        StepVerifier.create(
                        agent.live(input, LiveConfig.defaults())
                                .filter(event -> event.type() != LiveEventType.CONNECTION_STATE)
                                .take(3))
                .expectNextMatches(event -> event.type() == LiveEventType.SESSION_CREATED)
                .expectNextMatches(event -> event.type() == LiveEventType.TEXT_DELTA)
                .expectNextMatches(event -> event.type() == LiveEventType.TURN_COMPLETE)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should send messages to session")
    void shouldSendMessagesToSession() {
        when(mockModel.connect(any(LiveConfig.class), any())).thenReturn(Mono.just(mockSession));
        when(mockSession.receive()).thenReturn(Flux.never());

        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        Msg testMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        Flux<Msg> input = Flux.just(testMsg);

        // Subscribe and wait a bit for the message to be sent
        agent.live(input, LiveConfig.defaults()).take(Duration.ofMillis(100)).blockLast();

        verify(mockSession).send(any(Msg.class));
    }

    @Test
    @DisplayName("Should emit state changes")
    void shouldEmitStateChanges() {
        // Create a sink to simulate connection state changes from session
        Sinks.Many<ConnectionStateEvent> connectionSink =
                Sinks.many().multicast().onBackpressureBuffer();

        when(mockModel.connect(any(LiveConfig.class), any())).thenReturn(Mono.just(mockSession));
        when(mockSession.receive()).thenReturn(Flux.empty());
        when(mockSession.connectionStateChanges()).thenReturn(connectionSink.asFlux());

        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        // Expect CONNECTING first, then CONNECTED after handshake completes
        StepVerifier.create(agent.stateChanges().take(2))
                .then(() -> agent.live(Flux.empty(), LiveConfig.defaults()).subscribe())
                .expectNextMatches(event -> event.currentState() == LiveAgentState.CONNECTING)
                .then(() -> connectionSink.tryEmitNext(ConnectionStateEvent.connected()))
                .expectNextMatches(event -> event.currentState() == LiveAgentState.CONNECTED)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should use system prompt from builder")
    void shouldUseSystemPromptFromBuilder() {
        when(mockModel.connect(any(LiveConfig.class), any())).thenReturn(Mono.just(mockSession));
        when(mockSession.receive()).thenReturn(Flux.empty());

        LiveAgent agent =
                LiveAgent.builder()
                        .name("test-agent")
                        .systemPrompt("You are a helpful assistant.")
                        .liveModel(mockModel)
                        .build();

        agent.live(Flux.empty(), LiveConfig.defaults()).subscribe();

        // Verify connect was called with config containing instructions
        verify(mockModel)
                .connect(
                        argThat(
                                config ->
                                        "You are a helpful assistant."
                                                .equals(config.getInstructions())),
                        any());
    }

    @Test
    @DisplayName("Should handle connection lost for non-recoverable provider")
    void shouldHandleConnectionLostForNonRecoverableProvider() {
        Sinks.Many<ConnectionStateEvent> connectionSink =
                Sinks.many().multicast().onBackpressureBuffer();

        when(mockModel.connect(any(LiveConfig.class), any())).thenReturn(Mono.just(mockSession));
        when(mockModel.supportsNativeRecovery()).thenReturn(false);
        when(mockSession.receive()).thenReturn(Flux.never());
        when(mockSession.connectionStateChanges()).thenReturn(connectionSink.asFlux());

        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        // Test that SESSION_ENDED is emitted after disconnection for non-recoverable provider
        StepVerifier.create(
                        agent.live(Flux.empty(), LiveConfig.defaults())
                                .filter(event -> event.type() == LiveEventType.SESSION_ENDED)
                                .take(1))
                .then(
                        () ->
                                connectionSink.tryEmitNext(
                                        ConnectionStateEvent.disconnected("Network error", null)))
                .expectNextMatches(event -> event.type() == LiveEventType.SESSION_ENDED)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should attempt reconnection for recoverable provider")
    void shouldAttemptReconnectionForRecoverableProvider() {
        Sinks.Many<ConnectionStateEvent> connectionSink =
                Sinks.many().multicast().onBackpressureBuffer();

        LiveSession mockSession2 = mock(LiveSession.class);
        when(mockSession2.isActive()).thenReturn(true);
        when(mockSession2.getProviderName()).thenReturn("test");
        when(mockSession2.send(any(Msg.class))).thenReturn(Mono.empty());
        when(mockSession2.close()).thenReturn(Mono.empty());
        when(mockSession2.connectionStateChanges()).thenReturn(Flux.empty());
        when(mockSession2.receive()).thenReturn(Flux.just(LiveEvent.sessionCreated(null)));

        when(mockModel.connect(any(LiveConfig.class), any()))
                .thenReturn(Mono.just(mockSession))
                .thenReturn(Mono.just(mockSession2));
        when(mockModel.supportsNativeRecovery()).thenReturn(true);
        when(mockSession.receive()).thenReturn(Flux.never());
        when(mockSession.connectionStateChanges()).thenReturn(connectionSink.asFlux());

        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        LiveConfig config = LiveConfig.builder().autoReconnect(true).build();

        // Test that RECONNECTED is emitted after disconnection for recoverable provider
        StepVerifier.create(
                        agent.live(Flux.empty(), config)
                                .filter(event -> event.type() == LiveEventType.RECONNECTED)
                                .take(1))
                .then(
                        () ->
                                connectionSink.tryEmitNext(
                                        ConnectionStateEvent.disconnected("Network error", null)))
                .expectNextMatches(event -> event.type() == LiveEventType.RECONNECTED)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle tool call events")
    void shouldHandleToolCallEvents() {
        when(mockModel.connect(any(LiveConfig.class), any())).thenReturn(Mono.just(mockSession));

        // Create a tool call event
        Msg toolCallMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                io.agentscope.core.message.ToolUseBlock.builder()
                                        .id("call-123")
                                        .name("get_weather")
                                        .input(java.util.Map.of("location", "Beijing"))
                                        .build())
                        .build();

        when(mockSession.receive())
                .thenReturn(
                        Flux.just(
                                LiveEvent.sessionCreated(null),
                                LiveEvent.toolCall(toolCallMsg, false)));

        LiveAgent agent = LiveAgent.builder().name("test-agent").liveModel(mockModel).build();

        // Filter out CONNECTION_STATE events from agent state changes
        StepVerifier.create(
                        agent.live(Flux.empty(), LiveConfig.defaults())
                                .filter(event -> event.type() != LiveEventType.CONNECTION_STATE)
                                .take(2))
                .expectNextMatches(event -> event.type() == LiveEventType.SESSION_CREATED)
                .expectNextMatches(event -> event.type() == LiveEventType.TOOL_CALL)
                .verifyComplete();
    }
}

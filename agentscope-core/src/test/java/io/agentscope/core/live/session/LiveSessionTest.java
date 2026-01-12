/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.live.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.transport.CloseInfo;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@DisplayName("LiveSession Tests")
class LiveSessionTest {

    private LiveSession session;
    private List<Msg> sentMessages;
    private Sinks.Many<LiveEvent> eventSink;
    private AtomicBoolean isOpen;
    private AtomicBoolean isClosed;
    private CloseInfo closeInfo;

    @BeforeEach
    void setUp() {
        sentMessages = new ArrayList<>();
        eventSink = Sinks.many().unicast().onBackpressureBuffer();
        isOpen = new AtomicBoolean(true);
        isClosed = new AtomicBoolean(false);
        closeInfo = new CloseInfo(1000, "normal");

        session =
                new LiveSession(
                        "session-123",
                        "test",
                        // sendFunction
                        msg -> {
                            sentMessages.add(msg);
                            return Mono.empty();
                        },
                        // eventFlux
                        eventSink.asFlux(),
                        // isOpenSupplier
                        isOpen::get,
                        // closeSupplier
                        () -> {
                            isClosed.set(true);
                            isOpen.set(false);
                            return Mono.empty();
                        },
                        // closeInfoSupplier
                        () -> closeInfo,
                        // updateConfigFunction
                        config -> Mono.empty());
    }

    @Test
    @DisplayName("Should return session ID")
    void shouldReturnSessionId() {
        assertEquals("session-123", session.getSessionId());
    }

    @Test
    @DisplayName("Should return provider name")
    void shouldReturnProviderName() {
        assertEquals("test", session.getProviderName());
    }

    @Test
    @DisplayName("Should start in CONNECTING state")
    void shouldStartInConnectingState() {
        assertEquals(ConnectionState.CONNECTING, session.getConnectionState());
    }

    @Test
    @DisplayName("Should not be active when not connected")
    void shouldNotBeActiveWhenNotConnected() {
        assertFalse(session.isActive());
    }

    @Test
    @DisplayName("Should be active after markConnected")
    void shouldBeActiveAfterMarkConnected() {
        session.markConnected();

        assertTrue(session.isActive());
        assertEquals(ConnectionState.CONNECTED, session.getConnectionState());
    }

    @Test
    @DisplayName("Should send message through sendFunction")
    void shouldSendMessageThroughSendFunction() {
        session.markConnected();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        StepVerifier.create(session.send(msg)).verifyComplete();

        assertEquals(1, sentMessages.size());
        assertEquals(msg, sentMessages.get(0));
    }

    @Test
    @DisplayName("Should error when sending to inactive session")
    void shouldErrorWhenSendingToInactiveSession() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        StepVerifier.create(session.send(msg)).expectError(IllegalStateException.class).verify();
    }

    @Test
    @DisplayName("Should receive events from eventFlux")
    void shouldReceiveEventsFromEventFlux() {
        session.markConnected();

        // Emit an event
        eventSink.tryEmitNext(LiveEvent.textDelta(null, false));

        StepVerifier.create(session.receive().take(1))
                .expectNextMatches(event -> event.type() == LiveEventType.TEXT_DELTA)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should emit connection state changes")
    void shouldEmitConnectionStateChanges() {
        StepVerifier.create(session.connectionStateChanges().take(1))
                .then(() -> session.markConnected())
                .expectNextMatches(event -> event.currentState() == ConnectionState.CONNECTED)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should send interrupt control")
    void shouldSendInterruptControl() {
        session.markConnected();

        StepVerifier.create(session.interrupt()).verifyComplete();

        assertEquals(1, sentMessages.size());
        assertEquals(MsgRole.CONTROL, sentMessages.get(0).getRole());
    }

    @Test
    @DisplayName("Should close session")
    void shouldCloseSession() {
        session.markConnected();

        StepVerifier.create(session.close()).verifyComplete();

        assertEquals(ConnectionState.CLOSED, session.getConnectionState());
        assertTrue(isClosed.get());
    }

    @Test
    @DisplayName("Should set and get metadata")
    void shouldSetAndGetMetadata() {
        session.setMetadata("key1", "value1");
        session.setMetadata("key2", "value2");

        Map<String, String> metadata = session.getMetadata();

        assertEquals("value1", metadata.get("key1"));
        assertEquals("value2", metadata.get("key2"));
    }

    @Test
    @DisplayName("Should set and get resumption handle")
    void shouldSetAndGetResumptionHandle() {
        session.setResumptionHandle("test-handle-123");
        assertEquals("test-handle-123", session.getResumptionHandle());
    }

    @Test
    @DisplayName("Should update config when active")
    void shouldUpdateConfigWhenActive() {
        session.markConnected();

        LiveConfig newConfig = LiveConfig.builder().voice("alloy").build();

        StepVerifier.create(session.updateConfig(newConfig)).verifyComplete();
    }

    @Test
    @DisplayName("Should error when updating config on inactive session")
    void shouldErrorWhenUpdatingConfigOnInactiveSession() {
        LiveConfig newConfig = LiveConfig.builder().voice("alloy").build();

        StepVerifier.create(session.updateConfig(newConfig))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("Should send stream of messages")
    void shouldSendStreamOfMessages() {
        session.markConnected();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();
        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("World").build())
                        .build();

        StepVerifier.create(session.send(Flux.just(msg1, msg2))).verifyComplete();

        assertEquals(2, sentMessages.size());
    }

    @Test
    @DisplayName("Should mark disconnected with reason")
    void shouldMarkDisconnectedWithReason() {
        session.markConnected();
        session.markDisconnected("Connection lost", new RuntimeException("error"));

        assertEquals(ConnectionState.DISCONNECTED, session.getConnectionState());
        assertFalse(session.isActive());
    }

    @Test
    @DisplayName("Should emit disconnected state event")
    void shouldEmitDisconnectedStateEvent() {
        session.markConnected();

        StepVerifier.create(session.connectionStateChanges().skip(1).take(1))
                .then(() -> session.markDisconnected("Connection lost", null))
                .expectNextMatches(event -> event.currentState() == ConnectionState.DISCONNECTED)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get close info from supplier")
    void shouldGetCloseInfoFromSupplier() {
        CloseInfo info = session.getCloseInfo();
        assertEquals(1000, info.code());
        assertEquals("normal", info.reason());
    }

    @Test
    @DisplayName("Should not be active when connection is not open")
    void shouldNotBeActiveWhenConnectionNotOpen() {
        session.markConnected();
        assertTrue(session.isActive());

        isOpen.set(false);
        assertFalse(session.isActive());
    }
}

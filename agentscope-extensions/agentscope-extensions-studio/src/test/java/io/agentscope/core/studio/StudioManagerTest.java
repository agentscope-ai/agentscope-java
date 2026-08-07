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
package io.agentscope.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.StreamableAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DisplayName("StudioManager Tests")
class StudioManagerTest {

    @AfterEach
    void tearDown() {
        // Always clean up after each test
        StudioManager.shutdown();
    }

    @Test
    @DisplayName("init() should return a new builder")
    void testInit() {
        StudioManager.Builder builder = StudioManager.init();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("getClient() should return null before initialization")
    void testGetClientBeforeInit() {
        assertNull(StudioManager.getClient());
    }

    @Test
    @DisplayName("getWebSocketClient() should return null before initialization")
    void testGetWebSocketClientBeforeInit() {
        assertNull(StudioManager.getWebSocketClient());
    }

    @Test
    @DisplayName("getConfig() should return null before initialization")
    void testGetConfigBeforeInit() {
        assertNull(StudioManager.getConfig());
    }

    @Test
    @DisplayName("isInitialized() should return false before initialization")
    void testIsInitializedBeforeInit() {
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("shutdown() should be safe to call before initialization")
    void testShutdownBeforeInit() {
        // Should not throw
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertNull(StudioManager.getWebSocketClient());
        assertNull(StudioManager.getConfig());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("shutdown() should clear all state")
    void testShutdownClearsState() {
        // This test doesn't actually initialize (to avoid network calls)
        // Just tests that shutdown resets state
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertNull(StudioManager.getWebSocketClient());
        assertNull(StudioManager.getConfig());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("Builder should support method chaining")
    void testBuilderChaining() {
        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .maxRetries(5)
                        .reconnectAttempts(3);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Multiple init() calls should return independent builders")
    void testMultipleInitCalls() {
        StudioManager.Builder builder1 = StudioManager.init();
        StudioManager.Builder builder2 = StudioManager.init();

        assertNotNull(builder1);
        assertNotNull(builder2);
        // They should be different instances
        assertFalse(builder1 == builder2);
    }

    @Test
    @DisplayName("shutdown() should be idempotent")
    void testShutdownIdempotent() {
        // Multiple shutdown calls should be safe
        StudioManager.shutdown();
        StudioManager.shutdown();
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("streamToStudio() should return error when Studio is not initialized")
    void streamToStudio_WhenNotInitialized_ShouldReturnError() {
        StreamableAgent mockAgent = mock(StreamableAgent.class);
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        StepVerifier.create(StudioManager.streamToStudio(mockAgent, input, null))
                .expectErrorSatisfies(
                        throwable -> {
                            assertTrue(throwable instanceof IllegalStateException);
                            assertEquals("Studio is not initialized", throwable.getMessage());
                        })
                .verify();
    }

    @Test
    @DisplayName("streamToStudio() should use defaults when options is null")
    void streamToStudio_WithNullOptions_ShouldUseDefaults() throws Exception {
        // Setup: Use reflection to set static fields to simulate initialized state
        StudioClient mockClient = mock(StudioClient.class);
        StudioWebSocketClient mockWsClient = mock(StudioWebSocketClient.class);

        setStaticField("client", mockClient);
        setStaticField("wsClient", mockWsClient);
        setStaticField("config", StudioConfig.builder().build());

        StreamableAgent mockAgent = mock(StreamableAgent.class);
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, input, true);

        when(mockAgent.stream(any(Msg.class), any(StreamOptions.class)))
                .thenReturn(Flux.just(event));
        when(mockClient.pushMessage(any(Msg.class))).thenReturn(Flux.empty().then());

        StepVerifier.create(StudioManager.streamToStudio(mockAgent, input, null)).verifyComplete();

        // Verify that stream was called with some StreamOptions (defaults)
        verify(mockAgent, times(1)).stream(any(Msg.class), any(StreamOptions.class));
    }

    @Test
    @DisplayName("streamToStudio() should use provided options when not null")
    void streamToStudio_WithCustomOptions_ShouldUseCustomOptions() throws Exception {
        // Setup: Use reflection to set static fields to simulate initialized state
        StudioClient mockClient = mock(StudioClient.class);
        StudioWebSocketClient mockWsClient = mock(StudioWebSocketClient.class);

        setStaticField("client", mockClient);
        setStaticField("wsClient", mockWsClient);
        setStaticField("config", StudioConfig.builder().build());

        StreamableAgent mockAgent = mock(StreamableAgent.class);
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();
        Event event = new Event(EventType.AGENT_RESULT, input, true);
        StreamOptions customOptions =
                StreamOptions.builder().eventTypes(EventType.REASONING).build();

        when(mockAgent.stream(eq(input), eq(customOptions))).thenReturn(Flux.just(event));
        when(mockClient.pushMessage(any(Msg.class))).thenReturn(Flux.empty().then());

        StepVerifier.create(StudioManager.streamToStudio(mockAgent, input, customOptions))
                .verifyComplete();

        // Verify that stream was called with the custom options
        verify(mockAgent, times(1)).stream(eq(input), eq(customOptions));
    }

    private void setStaticField(String fieldName, Object value) throws Exception {
        Field field = StudioManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}

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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StudioUserAgent Tests")
class StudioUserAgentTest {

    @Test
    @DisplayName("Builder should create agent with minimal config")
    void testBuilderMinimal() {
        StudioUserAgent agent = StudioUserAgent.builder().name("TestUser").build();

        assertNotNull(agent);
        assertEquals("TestUser", agent.getName());
        assertNotNull(agent.getAgentId());
    }

    @Test
    @DisplayName("Builder should support all parameters")
    void testBuilderComplete() {
        StudioUserAgent agent =
                StudioUserAgent.builder()
                        .name("TestUser")
                        .studioClient(null)
                        .webSocketClient(null)
                        .inputTimeout(Duration.ofSeconds(30))
                        .build();

        assertNotNull(agent);
        assertEquals("TestUser", agent.getName());
    }

    @Test
    @DisplayName("Agent with null Studio clients should work")
    void testAgentWithNullClients() {
        StudioUserAgent agent =
                StudioUserAgent.builder()
                        .name("TestUser")
                        .studioClient(null)
                        .webSocketClient(null)
                        .build();

        assertNotNull(agent);
        assertEquals("TestUser", agent.getName());
    }

    @Test
    @DisplayName("Builder should generate ID if not provided")
    void testBuilderAutoGenerateId() {
        StudioUserAgent agent1 = StudioUserAgent.builder().name("User1").build();
        StudioUserAgent agent2 = StudioUserAgent.builder().name("User2").build();

        assertNotNull(agent1.getAgentId());
        assertNotNull(agent2.getAgentId());
        // IDs should be different
        assert !agent1.getAgentId().equals(agent2.getAgentId());
    }

    @Test
    @DisplayName("Builder should support method chaining")
    void testBuilderChaining() {
        StudioUserAgent.Builder builder =
                StudioUserAgent.builder().name("TestUser").inputTimeout(Duration.ofMinutes(5));

        assertNotNull(builder);
        StudioUserAgent agent = builder.build();
        assertNotNull(agent);
    }

    @Test
    @DisplayName("Agent getName should return configured name")
    void testGetName() {
        StudioUserAgent agent = StudioUserAgent.builder().name("MyTestUser").build();
        assertEquals("MyTestUser", agent.getName());
    }

    @Test
    @DisplayName("Default input timeout should be reasonable")
    void testDefaultInputTimeout() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        assertNotNull(agent);
        // Just verify agent is created successfully with default timeout
    }

    @Test
    @DisplayName("Builder should reject null name")
    void testBuilderNullName() {
        try {
            StudioUserAgent.builder().name(null).build();
            assert false : "Should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    @DisplayName("Builder should reject empty name")
    void testBuilderEmptyName() {
        try {
            StudioUserAgent.builder().name("").build();
            assert false : "Should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    @DisplayName("Builder should reject whitespace-only name")
    void testBuilderWhitespaceName() {
        try {
            StudioUserAgent.builder().name("   ").build();
            assert false : "Should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    @DisplayName("observe(Msg) should return empty Mono")
    void testObserveMsg() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        io.agentscope.core.message.Msg msg =
                io.agentscope.core.message.Msg.builder()
                        .name("test")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .content(
                                io.agentscope.core.message.TextBlock.builder().text("test").build())
                        .build();
        reactor.test.StepVerifier.create(agent.observe(msg)).verifyComplete();
    }

    @Test
    @DisplayName("observe(List<Msg>) should return empty Mono")
    void testObserveMsgs() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        reactor.test.StepVerifier.create(agent.observe(java.util.List.of())).verifyComplete();
    }

    @Test
    @DisplayName("interrupt() should be no-op")
    void testInterrupt() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        agent.interrupt(); // Should not throw
    }

    @Test
    @DisplayName("interrupt(Msg) should be no-op")
    void testInterruptWithMsg() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        io.agentscope.core.message.Msg msg =
                io.agentscope.core.message.Msg.builder()
                        .name("test")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .content(
                                io.agentscope.core.message.TextBlock.builder().text("test").build())
                        .build();
        agent.interrupt(msg); // Should not throw
    }

    @Test
    @DisplayName("call() should delegate to call(Msg)")
    void testCallNoArgs() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        // This would require actual System.in input, so we just verify it doesn't throw
        // In a real scenario this would block waiting for input
        assertNotNull(agent.call());
    }

    @Test
    @DisplayName("call(Class) should delegate to call()")
    void testCallWithClass() {
        StudioUserAgent agent = StudioUserAgent.builder().name("User").build();
        assertNotNull(agent.call(String.class));
    }

    @Test
    @DisplayName("call(Msg) should use terminal input when Studio not configured")
    void testCallWithTerminalInput() throws Exception {
        String testInput = "test input";
        java.io.StringReader stringReader = new java.io.StringReader(testInput);
        java.io.BufferedReader mockReader = new java.io.BufferedReader(stringReader);

        StudioUserAgent agent =
                StudioUserAgent.builder().name("TestUser").terminalReader(mockReader).build();

        io.agentscope.core.message.Msg result =
                agent.call((io.agentscope.core.message.Msg) null).block();

        assertNotNull(result);
        assertEquals("TestUser", result.getName());
        assertEquals(io.agentscope.core.message.MsgRole.USER, result.getRole());
        io.agentscope.core.message.TextBlock textBlock =
                (io.agentscope.core.message.TextBlock) result.getContent().get(0);
        assertEquals(testInput, textBlock.getText());
        assertEquals("terminal", result.getMetadata().get("source"));
    }

    @Test
    @DisplayName("call(Msg) should handle empty terminal input")
    void testCallWithEmptyTerminalInput() throws Exception {
        java.io.StringReader stringReader = new java.io.StringReader("   ");
        java.io.BufferedReader mockReader = new java.io.BufferedReader(stringReader);

        StudioUserAgent agent =
                StudioUserAgent.builder().name("TestUser").terminalReader(mockReader).build();

        io.agentscope.core.message.Msg result =
                agent.call((io.agentscope.core.message.Msg) null).block();

        assertNotNull(result);
        io.agentscope.core.message.TextBlock textBlock =
                (io.agentscope.core.message.TextBlock) result.getContent().get(0);
        assertEquals("", textBlock.getText());
    }

    @Test
    @DisplayName("call() with Studio integration should use Studio")
    void testCallWithStudioIntegration() {
        StudioClient mockClient = org.mockito.Mockito.mock(StudioClient.class);
        StudioWebSocketClient mockWsClient = org.mockito.Mockito.mock(StudioWebSocketClient.class);

        String requestId = "test-request-id";
        org.mockito.Mockito.when(
                        mockClient.requestUserInput(
                                org.mockito.Mockito.anyString(),
                                org.mockito.Mockito.anyString(),
                                org.mockito.Mockito.any()))
                .thenReturn(reactor.core.publisher.Mono.just(requestId));

        StudioWebSocketClient.UserInputData inputData =
                new StudioWebSocketClient.UserInputData(
                        java.util.List.of(
                                io.agentscope.core.message.TextBlock.builder()
                                        .text("Studio input")
                                        .build()),
                        null);

        org.mockito.Mockito.when(mockWsClient.waitForInput(requestId))
                .thenReturn(reactor.core.publisher.Mono.just(inputData));

        org.mockito.Mockito.when(mockClient.pushMessage(org.mockito.Mockito.any()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        StudioUserAgent agent =
                StudioUserAgent.builder()
                        .name("TestUser")
                        .studioClient(mockClient)
                        .webSocketClient(mockWsClient)
                        .inputTimeout(Duration.ofSeconds(5))
                        .build();

        io.agentscope.core.message.Msg result =
                agent.call((io.agentscope.core.message.Msg) null).block();

        assertNotNull(result);
        assertEquals("TestUser", result.getName());
        assertEquals(io.agentscope.core.message.MsgRole.USER, result.getRole());
    }

    @Test
    @DisplayName("call() with Studio should fallback to terminal on error")
    void testCallWithStudioFallbackToTerminal() throws Exception {
        StudioClient mockClient = org.mockito.Mockito.mock(StudioClient.class);
        StudioWebSocketClient mockWsClient = org.mockito.Mockito.mock(StudioWebSocketClient.class);

        org.mockito.Mockito.when(
                        mockClient.requestUserInput(
                                org.mockito.Mockito.anyString(),
                                org.mockito.Mockito.anyString(),
                                org.mockito.Mockito.any()))
                .thenReturn(
                        reactor.core.publisher.Mono.error(
                                new RuntimeException("Studio unavailable")));

        java.io.StringReader stringReader = new java.io.StringReader("fallback input");
        java.io.BufferedReader mockReader = new java.io.BufferedReader(stringReader);

        StudioUserAgent agent =
                StudioUserAgent.builder()
                        .name("TestUser")
                        .studioClient(mockClient)
                        .webSocketClient(mockWsClient)
                        .terminalReader(mockReader)
                        .build();

        io.agentscope.core.message.Msg result =
                agent.call((io.agentscope.core.message.Msg) null).block();

        assertNotNull(result);
        io.agentscope.core.message.TextBlock textBlock =
                (io.agentscope.core.message.TextBlock) result.getContent().get(0);
        assertEquals("fallback input", textBlock.getText());
        assertEquals("terminal", result.getMetadata().get("source"));
    }
}

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
package io.agentscope.spring.boot.a2a.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.A2A;
import io.a2a.spec.Message;
import io.a2a.spec.SendStreamingMessageResponse;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link A2aJsonRpcController}.
 *
 * <p>These tests verify the controller's behavior without using Spring Test,
 * relying purely on JUnit 5 and Mockito.
 */
@DisplayName("A2aJsonRpcController Tests")
class A2aJsonRpcControllerTest {

    private A2aJsonRpcController controller;

    @Mock private AgentScopeA2aServer agentScopeA2aServer;

    @Mock private JsonRpcTransportWrapper jsonRpcTransportWrapper;

    private Map<String, String> headers;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new A2aJsonRpcController(agentScopeA2aServer);
        when(agentScopeA2aServer.getTransportWrapper(
                        eq(TransportProtocol.JSONRPC.asString()),
                        eq(JsonRpcTransportWrapper.class)))
                .thenReturn(jsonRpcTransportWrapper);
        headers = Collections.emptyMap();
    }

    @Nested
    @DisplayName("Handle Request Tests")
    class HandleRequestTests {

        @Test
        @DisplayName("Should handle JSON-RPC request and return plain object")
        void shouldHandleJsonRpcRequestAndReturnPlainObject() {
            String requestBody = "{\"method\": \"test\"}";
            String responseBody = "{\"result\": \"success\"}";

            when(jsonRpcTransportWrapper.handleRequest(anyString(), anyMap(), anyMap()))
                    .thenReturn(responseBody);

            Object result = controller.handleRequest(requestBody, headers);

            assertEquals(responseBody, result);

            // Verify interactions
            verify(agentScopeA2aServer)
                    .getTransportWrapper(
                            eq(TransportProtocol.JSONRPC.asString()),
                            eq(JsonRpcTransportWrapper.class));

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<java.util.Map<String, String>> headersCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(jsonRpcTransportWrapper)
                    .handleRequest(bodyCaptor.capture(), headersCaptor.capture(), anyMap());

            assertEquals(requestBody, bodyCaptor.getValue());
            assertTrue(headersCaptor.getValue().isEmpty());
        }

        @Test
        @DisplayName("Should handle JSON-RPC request and return Flux with JSONRPCResponse")
        void shouldHandleJsonRpcRequestAndReturnFluxWithJsonRpcResponse() {
            String requestBody = "{\"method\": \"test\"}";

            Message message = A2A.toAgentMessage("test");
            SendStreamingMessageResponse response = new SendStreamingMessageResponse(1, message);

            when(jsonRpcTransportWrapper.handleRequest(anyString(), anyMap(), anyMap()))
                    .thenReturn(Flux.just(response));

            Object result = controller.handleRequest(requestBody, headers);

            assertTrue(result instanceof Flux);

            @SuppressWarnings("unchecked")
            Flux<Object> fluxResult = (Flux<Object>) result;

            // Collect and verify the flux result
            Mono<Long> countMono = fluxResult.count();
            Long count = countMono.block();
            assertEquals(1L, count);

            // Verify interactions
            verify(agentScopeA2aServer)
                    .getTransportWrapper(
                            eq(TransportProtocol.JSONRPC.asString()),
                            eq(JsonRpcTransportWrapper.class));
        }

        @Test
        @DisplayName("Should handle request with headers")
        void shouldHandleRequestWithHeaders() {
            String requestBody = "{\"method\": \"test\"}";
            String responseBody = "{\"result\": \"success\"}";

            // Mock headers
            Map<String, String> header =
                    Map.of("Content-Type", "application/json", "Authorization", "Bearer token");

            when(jsonRpcTransportWrapper.handleRequest(anyString(), anyMap(), anyMap()))
                    .thenReturn(responseBody);

            Object result = controller.handleRequest(requestBody, header);

            assertEquals(responseBody, result);

            // Verify interactions
            ArgumentCaptor<java.util.Map<String, String>> headersCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(jsonRpcTransportWrapper)
                    .handleRequest(anyString(), headersCaptor.capture(), anyMap());

            java.util.Map<String, String> capturedHeaders = headersCaptor.getValue();
            assertEquals("application/json", capturedHeaders.get("Content-Type"));
            assertEquals("Bearer token", capturedHeaders.get("Authorization"));
        }
    }

    @Nested
    @DisplayName("Get Headers Tests")
    class GetHeadersTests {

        @Test
        @DisplayName("Should extract headers from HttpServletRequest")
        void shouldExtractHeadersFromHttpServletRequest() {
            // This would require using reflection to test private method
            // For now, we test it indirectly through handleRequest
            String requestBody = "{\"method\": \"test\"}";
            String responseBody = "{\"result\": \"success\"}";

            Map<String, String> header = Map.of("Header1", "Value1", "Header2", "Value2");

            when(jsonRpcTransportWrapper.handleRequest(anyString(), anyMap(), anyMap()))
                    .thenReturn(responseBody);

            controller.handleRequest(requestBody, header);

            ArgumentCaptor<java.util.Map<String, String>> headersCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(jsonRpcTransportWrapper)
                    .handleRequest(anyString(), headersCaptor.capture(), anyMap());

            java.util.Map<String, String> capturedHeaders = headersCaptor.getValue();
            assertEquals("Value1", capturedHeaders.get("Header1"));
            assertEquals("Value2", capturedHeaders.get("Header2"));
        }

        @Test
        @DisplayName("Should handle empty headers")
        void shouldHandleEmptyHeaders() {
            String requestBody = "{\"method\": \"test\"}";
            String responseBody = "{\"result\": \"success\"}";

            when(jsonRpcTransportWrapper.handleRequest(anyString(), anyMap(), anyMap()))
                    .thenReturn(responseBody);

            controller.handleRequest(requestBody, headers);

            ArgumentCaptor<java.util.Map<String, String>> headersCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(jsonRpcTransportWrapper)
                    .handleRequest(anyString(), headersCaptor.capture(), anyMap());

            java.util.Map<String, String> capturedHeaders = headersCaptor.getValue();
            assertTrue(capturedHeaders.isEmpty());
        }
    }

    @Nested
    @DisplayName("Get JSON-RPC Handler Tests")
    class GetJsonRpcHandlerTests {

        @Test
        @DisplayName("Should lazily initialize JSON-RPC handler")
        void shouldLazilyInitializeJsonRpcHandler() {
            String requestBody = "{\"method\": \"test\"}";
            String responseBody = "{\"result\": \"success\"}";

            when(jsonRpcTransportWrapper.handleRequest(anyString(), anyMap(), anyMap()))
                    .thenReturn(responseBody);

            // First call should initialize the handler
            Object result1 = controller.handleRequest(requestBody, headers);
            assertEquals(responseBody, result1);

            // Second call should reuse the same handler
            Object result2 = controller.handleRequest(requestBody, headers);
            assertEquals(responseBody, result2);

            // Should only fetch the transport wrapper once
            verify(agentScopeA2aServer)
                    .getTransportWrapper(
                            eq(TransportProtocol.JSONRPC.asString()),
                            eq(JsonRpcTransportWrapper.class));
        }
    }
}

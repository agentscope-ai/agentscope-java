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

package io.agentscope.core.a2a.server.transport.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for JsonRpcTransportWrapper.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Transport type identification</li>
 *   <li>Non-streaming request handling</li>
 *   <li>Streaming request handling</li>
 *   <li>Error handling for various JSON parsing exceptions</li>
 *   <li>Method not found scenarios</li>
 * </ul>
 */
@DisplayName("JsonRpcTransportWrapper Tests")
class JsonRpcTransportWrapperTest {

    private JSONRPCHandler jsonRpcHandler;

    private JsonRpcTransportWrapper transportWrapper;

    @BeforeEach
    void setUp() {
        jsonRpcHandler = mock(JSONRPCHandler.class);
        transportWrapper = new JsonRpcTransportWrapper(jsonRpcHandler);
    }

    @Nested
    @DisplayName("Transport Type Tests")
    class TransportTypeTests {

        @Test
        @DisplayName("Should return correct transport type")
        void testGetTransportType() {
            String transportType = transportWrapper.getTransportType();
            assertEquals(TransportProtocol.JSONRPC.asString(), transportType);
        }
    }

    @Nested
    @DisplayName("Request Handling Tests")
    class RequestHandlingTests {

        @Test
        @DisplayName("Should handle non-streaming request")
        void testHandleNonStreamingRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\"message123\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Hello\"}],\"contextId\":\"context456\",\"taskId\":\"task123\"},\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            SendMessageResponse mockResponse = mock(SendMessageResponse.class);
            when(jsonRpcHandler.onMessageSend(
                            any(SendMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(SendMessageResponse.class, result);
        }

        @Test
        @DisplayName("Should handle streaming request")
        void testHandleStreamingRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"SendStreamingMessage\",\"params\":{\"message\":{\"messageId\":\"message123\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Hello\"}],\"contextId\":\"context456\",\"taskId\":\"task123\"},\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Flow.Publisher<SendStreamingMessageResponse> mockPublisher = mock(Flow.Publisher.class);
            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockPublisher);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(Flux.class, result);
        }

        @Test
        @DisplayName("Should handle GetTaskRequest")
        void testHandleGetTaskRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"GetTask\",\"params\":{\"id\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            GetTaskResponse mockResponse = mock(GetTaskResponse.class);
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(GetTaskResponse.class, result);
        }

        @Test
        @DisplayName("Should handle ListTasksRequest")
        void testHandleListTasksRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"ListTasks\",\"params\":{\"pageSize\":10,\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            ListTasksResponse mockResponse = mock(ListTasksResponse.class);
            when(jsonRpcHandler.onListTasks(
                            any(ListTasksRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(ListTasksResponse.class, result);
        }

        @Test
        @DisplayName("Should handle CancelTaskRequest")
        void testHandleCancelTaskRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"CancelTask\",\"params\":{\"id\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse mockResponse =
                    mock(org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse.class);
            when(jsonRpcHandler.onCancelTask(
                            any(CancelTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(
                    org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse.class, result);
        }

        @Test
        @DisplayName("Should handle GetTaskPushNotificationConfigRequest")
        void testHandleGetTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"GetTaskPushNotificationConfig\",\"params\":{\"id\":\"task123\",\"taskId\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            GetTaskPushNotificationConfigResponse mockResponse =
                    mock(GetTaskPushNotificationConfigResponse.class);
            when(jsonRpcHandler.getPushNotificationConfig(
                            any(GetTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(GetTaskPushNotificationConfigResponse.class, result);
        }

        @Test
        @DisplayName("Should handle CreateTaskPushNotificationConfigRequest")
        void testHandleCreateTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"CreateTaskPushNotificationConfig\",\"params\":{\"id\":\"config123\",\"taskId\":\"task123\",\"url\":\"https://example.com/webhook\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            CreateTaskPushNotificationConfigResponse mockResponse =
                    mock(CreateTaskPushNotificationConfigResponse.class);
            when(jsonRpcHandler.setPushNotificationConfig(
                            any(CreateTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(CreateTaskPushNotificationConfigResponse.class, result);
        }

        @Test
        @DisplayName("Should handle ListTaskPushNotificationConfigsRequest")
        void testHandleListTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"ListTaskPushNotificationConfigs\",\"params\":{\"id\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            ListTaskPushNotificationConfigsResponse mockResponse =
                    mock(ListTaskPushNotificationConfigsResponse.class);
            when(jsonRpcHandler.listPushNotificationConfigs(
                            any(ListTaskPushNotificationConfigsRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(ListTaskPushNotificationConfigsResponse.class, result);
        }

        @Test
        @DisplayName("Should handle DeleteTaskPushNotificationConfigRequest")
        void testHandleDeleteTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"DeleteTaskPushNotificationConfig\",\"params\":{\"id\":\"task123\",\"pushNotificationConfigId\":\"111\",\"taskId\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            DeleteTaskPushNotificationConfigResponse mockResponse =
                    mock(DeleteTaskPushNotificationConfigResponse.class);
            when(jsonRpcHandler.deletePushNotificationConfig(
                            any(DeleteTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(DeleteTaskPushNotificationConfigResponse.class, result);
        }

        @Test
        @DisplayName("Should handle GetExtendedAgentCardRequest")
        void testHandleGetExtendedAgentCardRequest() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"GetExtendedAgentCard\",\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            GetExtendedAgentCardResponse mockResponse = mock(GetExtendedAgentCardResponse.class);
            when(jsonRpcHandler.onGetExtendedCardRequest(
                            any(GetExtendedAgentCardRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(GetExtendedAgentCardResponse.class, result);
        }

        @Test
        @DisplayName("Should handle SubscribeToTaskRequest")
        void testHandleSubscribeToTaskRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Flow.Publisher<SendStreamingMessageResponse> mockPublisher = mock(Flow.Publisher.class);
            when(jsonRpcHandler.onSubscribeToTask(
                            any(SubscribeToTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockPublisher);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(Flux.class, result);
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle JsonParseException")
        void testHandleJsonParseException() throws Exception {
            String body = "{ invalid json ";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Object result = transportWrapper.handleRequest(body, headers, metadata);
            assertNotNull(result);
            assertInstanceOf(A2AErrorResponse.class, result);
            A2AErrorResponse errorResponse = (A2AErrorResponse) result;
            A2AError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(UnsupportedOperationError.class, error);
        }

        @Test
        @DisplayName("Should handle generic exception")
        void testHandleGenericException() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"GetTask\",\"params\":{\"id\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenThrow(new RuntimeException("Test exception"));

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(A2AErrorResponse.class, result);
            A2AErrorResponse errorResponse = (A2AErrorResponse) result;
            A2AError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(InternalError.class, error);
        }

        @Test
        @DisplayName("Should handle invalid params")
        void testHandleInvalidParams() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"GetTask\",\"params\":{\"taskId\":\"task123\",\"tenant\":\"\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(A2AErrorResponse.class, result);
            A2AErrorResponse errorResponse = (A2AErrorResponse) result;
            A2AError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(InternalError.class, error);
        }

        @Test
        @DisplayName("Should handle MethodNotFoundError")
        void testHandleMethodNotFoundError() {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"not/found/method\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();
            Object result = transportWrapper.handleRequest(body, headers, metadata);
            assertNotNull(result);
            assertInstanceOf(A2AErrorResponse.class, result);
            A2AErrorResponse errorResponse = (A2AErrorResponse) result;
            A2AError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(UnsupportedOperationError.class, error);
        }

        @Test
        @DisplayName("Should handle Method is null")
        void testHandleMethodNull() {
            String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();
            Object result = transportWrapper.handleRequest(body, headers, metadata);
            assertNotNull(result);
            assertInstanceOf(A2AErrorResponse.class, result);
            A2AErrorResponse errorResponse = (A2AErrorResponse) result;
            A2AError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(UnsupportedOperationError.class, error);
        }
    }
}

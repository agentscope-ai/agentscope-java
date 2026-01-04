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

import io.a2a.server.ServerCallContext;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.CancelTaskResponse;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigResponse;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskPushNotificationConfigResponse;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.InternalError;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.ListTaskPushNotificationConfigResponse;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SendStreamingMessageResponse;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.SetTaskPushNotificationConfigResponse;
import io.a2a.spec.TaskResubscriptionRequest;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;
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
                    "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{\"message\":{\"messageId\":\"message123\",\"kind\":\"message\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"Hello\"}]},\"taskId\":\"task123\",\"contextId\":\"context456\"},\"id\":\"1\"}";
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
                    "{\"jsonrpc\":\"2.0\",\"method\":\"message/stream\",\"params\":{\"message\":{\"messageId\":\"message123\",\"kind\":\"message\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"Hello\"}]},\"taskId\":\"task123\",\"contextId\":\"context456\"},\"id\":\"1\"}";
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
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/get\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
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
        @DisplayName("Should handle CancelTaskRequest")
        void testHandleCancelTaskRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/cancel\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            CancelTaskResponse mockResponse = mock(CancelTaskResponse.class);
            when(jsonRpcHandler.onCancelTask(
                            any(CancelTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(CancelTaskResponse.class, result);
        }

        @Test
        @DisplayName("Should handle GetTaskPushNotificationConfigRequest")
        void testHandleGetTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/get\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
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
        @DisplayName("Should handle SetTaskPushNotificationConfigRequest")
        void testHandleSetTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/set\",\"params\":{\"taskId\":\"task123\",\"pushNotificationConfig\":{\"url\":\"https://example.com/webhook\"}},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            SetTaskPushNotificationConfigResponse mockResponse =
                    mock(SetTaskPushNotificationConfigResponse.class);
            when(jsonRpcHandler.setPushNotificationConfig(
                            any(SetTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(SetTaskPushNotificationConfigResponse.class, result);
        }

        @Test
        @DisplayName("Should handle ListTaskPushNotificationConfigRequest")
        void testHandleListTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/list\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            ListTaskPushNotificationConfigResponse mockResponse =
                    mock(ListTaskPushNotificationConfigResponse.class);
            when(jsonRpcHandler.listPushNotificationConfig(
                            any(ListTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(mockResponse);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(ListTaskPushNotificationConfigResponse.class, result);
        }

        @Test
        @DisplayName("Should handle DeleteTaskPushNotificationConfigRequest")
        void testHandleDeleteTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/delete\",\"params\":{\"id\":\"task123\",\"pushNotificationConfigId\":\"111\"},\"id\":\"1\"}";
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
        @DisplayName("Should handle TaskResubscriptionRequest")
        void testHandleTaskResubscriptionRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/resubscribe\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Flow.Publisher<SendStreamingMessageResponse> mockPublisher = mock(Flow.Publisher.class);
            when(jsonRpcHandler.onResubscribeToTask(
                            any(TaskResubscriptionRequest.class), any(ServerCallContext.class)))
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
            assertInstanceOf(JSONRPCErrorResponse.class, result);
            JSONRPCErrorResponse errorResponse = (JSONRPCErrorResponse) result;
            JSONRPCError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(JSONParseError.class, error);
        }

        @Test
        @DisplayName("Should handle generic exception")
        void testHandleGenericException() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/get\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            // Mock the handler to throw an exception
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenThrow(new RuntimeException("Test exception"));

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(JSONRPCErrorResponse.class, result);
            JSONRPCErrorResponse errorResponse = (JSONRPCErrorResponse) result;
            JSONRPCError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(InternalError.class, error);
        }

        @Test
        @DisplayName("Should handle invalid params")
        void testHandleInvalidParams() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/get\",\"params\":{\"taskId\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertNotNull(result);
            assertInstanceOf(JSONRPCErrorResponse.class, result);
            JSONRPCErrorResponse errorResponse = (JSONRPCErrorResponse) result;
            JSONRPCError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(InvalidParamsError.class, error);
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
            assertInstanceOf(JSONRPCErrorResponse.class, result);
            JSONRPCErrorResponse errorResponse = (JSONRPCErrorResponse) result;
            JSONRPCError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(MethodNotFoundError.class, error);
        }

        @Test
        @DisplayName("Should handle Method is null")
        void testHandleMethodNull() {
            String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();
            Object result = transportWrapper.handleRequest(body, headers, metadata);
            assertNotNull(result);
            assertInstanceOf(JSONRPCErrorResponse.class, result);
            JSONRPCErrorResponse errorResponse = (JSONRPCErrorResponse) result;
            JSONRPCError error = errorResponse.getError();
            assertNotNull(error);
            assertInstanceOf(InvalidRequestError.class, error);
        }
    }
}

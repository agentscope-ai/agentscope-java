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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for JsonRpcTransportWrapper.
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
            assertEquals(TransportProtocol.JSONRPC.asString(), transportWrapper.getTransportType());
        }
    }

    @Nested
    @DisplayName("Request Handling Tests")
    class RequestHandlingTests {

        @Test
        @DisplayName("Should handle non-streaming SendMessage request")
        void testHandleNonStreamingRequest() {
            when(jsonRpcHandler.onMessageSend(
                            any(SendMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new SendMessageResponse("1", task()));

            Object result =
                    transportWrapper.handleRequest(
                            sendMessageRequestBody("SendMessage"),
                            new HashMap<>(),
                            new HashMap<>());

            String json = assertJsonResult(result);
            assertTrue(json.contains("\"result\""));
            assertTrue(json.contains("\"task\""));
            assertTrue(json.contains("\"task123\""));
        }

        @Test
        @DisplayName("Should bridge streaming SendStreamingMessage publisher to Flux")
        void testHandleStreamingRequest() {
            SendStreamingMessageResponse response =
                    new SendStreamingMessageResponse(
                            "1",
                            new TaskStatusUpdateEvent(
                                    "task123",
                                    new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                                    "context456",
                                    Map.of()));
            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(singleResponsePublisher(response));

            Object result =
                    transportWrapper.handleRequest(
                            sendMessageRequestBody("SendStreamingMessage"),
                            new HashMap<>(),
                            new HashMap<>());

            assertInstanceOf(Flux.class, result);
            @SuppressWarnings("unchecked")
            Flux<String> flux = (Flux<String>) result;
            List<String> events = flux.collectList().block(Duration.ofSeconds(2));
            assertNotNull(events);
            assertEquals(1, events.size());
            assertTrue(events.get(0).contains("\"result\""));
            assertTrue(events.get(0).contains("TASK_STATE_COMPLETED"));
            verify(jsonRpcHandler).validateRequestedTask("task123");
        }

        @Test
        @DisplayName("Should convert streaming publisher errors to JSON-RPC error events")
        void testHandleStreamingPublisherErrorAfterFirstResponse() {
            SendStreamingMessageResponse response =
                    new SendStreamingMessageResponse(
                            "1",
                            new TaskStatusUpdateEvent(
                                    "task123",
                                    new TaskStatus(TaskState.TASK_STATE_WORKING),
                                    "context456",
                                    Map.of()));
            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(errorAfterFirstResponsePublisher(response));

            Object result =
                    transportWrapper.handleRequest(
                            sendMessageRequestBody("SendStreamingMessage"),
                            new HashMap<>(),
                            new HashMap<>());

            assertInstanceOf(Flux.class, result);
            @SuppressWarnings("unchecked")
            Flux<String> flux = (Flux<String>) result;
            List<String> events = flux.collectList().block(Duration.ofSeconds(2));
            assertNotNull(events);
            assertEquals(2, events.size());
            assertTrue(events.get(0).contains("\"result\""));
            assertTrue(events.get(1).contains("\"error\""));
            assertTrue(events.get(1).contains("boom after first response"));
        }

        @Test
        @DisplayName("Should handle GetTask request")
        void testHandleGetTaskRequest() {
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new GetTaskResponse("1", task()));

            Object result =
                    transportWrapper.handleRequest(
                            getTaskRequestBody(), new HashMap<>(), new HashMap<>());

            String json = assertJsonResult(result);
            assertTrue(json.contains("\"result\""));
            assertTrue(json.contains("\"task123\""));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle invalid JSON")
        void testHandleJsonParseException() {
            Object result =
                    transportWrapper.handleRequest(
                            "{ invalid json ", new HashMap<>(), new HashMap<>());

            assertErrorJson(result);
        }

        @Test
        @DisplayName("Should handle generic exception")
        void testHandleGenericException() {
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenThrow(new RuntimeException("Test exception"));

            Object result =
                    transportWrapper.handleRequest(
                            getTaskRequestBody(), new HashMap<>(), new HashMap<>());

            String json = assertErrorJson(result);
            assertTrue(json.contains("\"error\""));
        }

        @Test
        @DisplayName("Should handle invalid params")
        void testHandleInvalidParams() {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"GetTask\",\"params\":{\"taskId\":\"task123\"},\"id\":\"1\"}";

            Object result = transportWrapper.handleRequest(body, new HashMap<>(), new HashMap<>());

            assertErrorJson(result);
        }

        @Test
        @DisplayName("Should handle method not found")
        void testHandleMethodNotFoundError() {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"not/found/method\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";

            Object result = transportWrapper.handleRequest(body, new HashMap<>(), new HashMap<>());

            assertErrorJson(result);
        }

        @Test
        @DisplayName("Should handle missing method")
        void testHandleMethodNull() {
            String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";

            Object result = transportWrapper.handleRequest(body, new HashMap<>(), new HashMap<>());

            assertErrorJson(result);
        }
    }

    private String sendMessageRequestBody(String method) {
        return sendMessageRequestBody(method, Map.of());
    }

    private String sendMessageRequestBody(String method, Map<String, Object> metadata) {
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("Hello"))
                        .messageId("message123")
                        .taskId("task123")
                        .contextId("context456")
                        .metadata(metadata)
                        .build();
        MessageSendParams params = MessageSendParams.builder().message(message).build();
        return JSONRPCUtils.toJsonRPCRequest(
                "1", method, ProtoUtils.ToProto.sendMessageRequest(params));
    }

    private String getTaskRequestBody() {
        return JSONRPCUtils.toJsonRPCRequest(
                "1", "GetTask", ProtoUtils.ToProto.getTaskRequest(new TaskQueryParams("task123")));
    }

    private Task task() {
        return Task.builder()
                .id("task123")
                .contextId("context456")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .build();
    }

    private Flow.Publisher<SendStreamingMessageResponse> singleResponsePublisher(
            SendStreamingMessageResponse response) {
        return subscriber ->
                subscriber.onSubscribe(
                        new Flow.Subscription() {

                            private boolean completed;

                            @Override
                            public void request(long n) {
                                if (completed || n <= 0) {
                                    return;
                                }
                                completed = true;
                                subscriber.onNext(response);
                                subscriber.onComplete();
                            }

                            @Override
                            public void cancel() {
                                completed = true;
                            }
                        });
    }

    private Flow.Publisher<SendStreamingMessageResponse> errorAfterFirstResponsePublisher(
            SendStreamingMessageResponse response) {
        return subscriber ->
                subscriber.onSubscribe(
                        new Flow.Subscription() {

                            private boolean completed;

                            @Override
                            public void request(long n) {
                                if (completed || n <= 0) {
                                    return;
                                }
                                completed = true;
                                subscriber.onNext(response);
                                subscriber.onError(
                                        new RuntimeException("boom after first response"));
                            }

                            @Override
                            public void cancel() {
                                completed = true;
                            }
                        });
    }

    private String assertJsonResult(Object result) {
        assertNotNull(result);
        String json = assertInstanceOf(String.class, result);
        assertTrue(json.contains("\"jsonrpc\""));
        return json;
    }

    private String assertErrorJson(Object result) {
        String json = assertJsonResult(result);
        assertTrue(json.contains("\"error\""));
        return json;
    }
}

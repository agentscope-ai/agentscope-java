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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.A2A;
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
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.ListTaskPushNotificationConfigResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.PushNotificationConfig;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SendStreamingMessageResponse;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.SetTaskPushNotificationConfigResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskPushNotificationConfig;
import io.a2a.spec.TaskResubscriptionRequest;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.a2a.util.Utils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.reactivestreams.FlowAdapters;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

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

            SendMessageResponse response = new SendMessageResponse("1", A2A.toAgentMessage("Hi"));
            when(jsonRpcHandler.onMessageSend(
                            any(SendMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals("message", assertResult(result).path("kind").asText());
        }

        @Test
        @DisplayName("Should handle streaming request")
        void testHandleStreamingRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"message/stream\",\"params\":{\"message\":{\"messageId\":\"message123\",\"kind\":\"message\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"Hello\"}]},\"taskId\":\"task123\",\"contextId\":\"context456\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(streamingResponses());

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertStreamedMessage(result);
        }

        @Test
        @DisplayName("Should handle GetTaskRequest")
        void testHandleGetTaskRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/get\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            GetTaskResponse response = new GetTaskResponse("1", TASK);
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals("task123", assertResult(result).path("id").asText());
        }

        @Test
        @DisplayName("Should handle CancelTaskRequest")
        void testHandleCancelTaskRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/cancel\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            CancelTaskResponse response = new CancelTaskResponse("1", TASK);
            when(jsonRpcHandler.onCancelTask(
                            any(CancelTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals("task123", assertResult(result).path("id").asText());
        }

        @Test
        @DisplayName("Should handle GetTaskPushNotificationConfigRequest")
        void testHandleGetTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/get\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            GetTaskPushNotificationConfigResponse response =
                    new GetTaskPushNotificationConfigResponse("1", PUSH_CONFIG);
            when(jsonRpcHandler.getPushNotificationConfig(
                            any(GetTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals("task123", assertResult(result).path("taskId").asText());
        }

        @Test
        @DisplayName("Should handle SetTaskPushNotificationConfigRequest")
        void testHandleSetTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/set\",\"params\":{\"taskId\":\"task123\",\"pushNotificationConfig\":{\"url\":\"https://example.com/webhook\"}},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            SetTaskPushNotificationConfigResponse response =
                    new SetTaskPushNotificationConfigResponse("1", PUSH_CONFIG);
            when(jsonRpcHandler.setPushNotificationConfig(
                            any(SetTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals("task123", assertResult(result).path("taskId").asText());
        }

        @Test
        @DisplayName("Should handle ListTaskPushNotificationConfigRequest")
        void testHandleListTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/list\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            ListTaskPushNotificationConfigResponse response =
                    new ListTaskPushNotificationConfigResponse("1", List.of(PUSH_CONFIG));
            when(jsonRpcHandler.listPushNotificationConfig(
                            any(ListTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals(1, assertResult(result).size());
        }

        @Test
        @DisplayName("Should handle DeleteTaskPushNotificationConfigRequest")
        void testHandleDeleteTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/pushNotificationConfig/delete\",\"params\":{\"id\":\"task123\",\"pushNotificationConfigId\":\"111\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            DeleteTaskPushNotificationConfigResponse response =
                    new DeleteTaskPushNotificationConfigResponse("1");
            when(jsonRpcHandler.deletePushNotificationConfig(
                            any(DeleteTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(response);

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertResult(result);
        }

        @Test
        @DisplayName("Should handle TaskResubscriptionRequest")
        void testHandleTaskResubscriptionRequest() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/resubscribe\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            when(jsonRpcHandler.onResubscribeToTask(
                            any(TaskResubscriptionRequest.class), any(ServerCallContext.class)))
                    .thenReturn(streamingResponses());

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertStreamedMessage(result);
        }
    }

    @Nested
    @DisplayName("Streaming Backpressure Tests")
    class StreamingBackpressureTests {

        @Test
        @DisplayName("Should show unbuffered streaming can lose burst responses")
        void testUnbufferedStreamingBurstDropsResponse() {
            SendStreamingMessageResponse reasoningResponse =
                    mock(SendStreamingMessageResponse.class);
            SendStreamingMessageResponse textResponse = mock(SendStreamingMessageResponse.class);

            Flux<SendStreamingMessageResponse> unbuffered =
                    Flux.from(
                            FlowAdapters.toPublisher(
                                    burstPublisher(List.of(reasoningResponse, textResponse))));

            AssertionError overflowError =
                    assertThrows(
                            AssertionError.class,
                            () ->
                                    StepVerifier.create(unbuffered, 1)
                                            .expectNext(reasoningResponse)
                                            .verifyComplete());
            assertTrue(
                    String.valueOf(overflowError.getMessage()).contains("request overflow"),
                    "Expected request overflow but got: " + overflowError.getMessage());

            Flux<SendStreamingMessageResponse> buffered =
                    Flux.from(
                                    FlowAdapters.toPublisher(
                                            burstPublisher(
                                                    List.of(reasoningResponse, textResponse))))
                            .onBackpressureBuffer();

            StepVerifier.create(buffered, 1)
                    .expectNext(reasoningResponse)
                    .thenRequest(1)
                    .expectNext(textResponse)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Serialization Failure Tests")
    class SerializationFailureTests {

        @Test
        @DisplayName("Should answer the same request with an internal error")
        void testUnserializableResponseKeepsRequestId() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{\"message\":{\"messageId\":\"message123\",\"kind\":\"message\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"Hello\"}]}},\"id\":\"request-77\"}";
            when(jsonRpcHandler.onMessageSend(
                            any(SendMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new SendMessageResponse("request-77", unserializableMessage()));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            JsonNode response =
                    Utils.OBJECT_MAPPER.readTree(assertInstanceOf(String.class, result));
            assertEquals("request-77", response.path("id").asText());
            assertEquals(INTERNAL_ERROR_CODE, response.path("error").path("code").asInt());
        }

        @Test
        @DisplayName("Should end a stream with an internal error and stop reading responses")
        void testUnserializableStreamedResponseEndsStream() {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"message/stream\",\"params\":{\"message\":{\"messageId\":\"message123\",\"kind\":\"message\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"Hello\"}]}},\"id\":\"1\"}";
            AtomicBoolean cancelled = new AtomicBoolean();
            Flux<SendStreamingMessageResponse> responses =
                    Flux.concat(
                                    Flux.just(
                                            new SendStreamingMessageResponse(
                                                    "1", A2A.toAgentMessage("before")),
                                            new SendStreamingMessageResponse(
                                                    "1", unserializableMessage()),
                                            new SendStreamingMessageResponse(
                                                    "1", A2A.toAgentMessage("after"))),
                                    Flux.never())
                            .doOnCancel(() -> cancelled.set(true));
            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(FlowAdapters.toFlowPublisher(responses));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            StepVerifier.create(assertInstanceOf(Flux.class, result))
                    .assertNext(json -> assertKind("message", json))
                    .assertNext(
                            json -> {
                                JsonNode response = readJson(json);
                                assertEquals("1", response.path("id").asText());
                                assertEquals(
                                        INTERNAL_ERROR_CODE,
                                        response.path("error").path("code").asInt());
                            })
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
            assertTrue(cancelled.get(), "the subscription to the responses should be cancelled");
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
            assertEquals(new JSONParseError().getCode(), errorCode(result));
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

            assertEquals(new InternalError("Test exception").getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should handle invalid params")
        void testHandleInvalidParams() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tasks/get\",\"params\":{\"taskId\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();

            Object result = transportWrapper.handleRequest(body, headers, metadata);

            assertEquals(new InvalidParamsError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should handle MethodNotFoundError")
        void testHandleMethodNotFoundError() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"method\":\"not/found/method\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();
            Object result = transportWrapper.handleRequest(body, headers, metadata);
            assertEquals(new MethodNotFoundError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should handle Method is null")
        void testHandleMethodNull() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";
            Map<String, String> headers = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();
            Object result = transportWrapper.handleRequest(body, headers, metadata);
            assertEquals(new InvalidRequestError().getCode(), errorCode(result));
        }
    }

    private static final Task TASK =
            new Task.Builder()
                    .id("task123")
                    .contextId("context456")
                    .status(new TaskStatus(TaskState.WORKING))
                    .build();

    private static final TaskPushNotificationConfig PUSH_CONFIG =
            new TaskPushNotificationConfig(
                    "task123",
                    new PushNotificationConfig("https://example.com/webhook", null, null, "111"));

    /** Parses a successful JSON-RPC response to request {@code 1} and returns its result. */
    private static JsonNode assertResult(Object result) throws Exception {
        JsonNode response = Utils.OBJECT_MAPPER.readTree(assertInstanceOf(String.class, result));
        assertEquals("1", response.path("id").asText());
        assertFalse(response.has("error"), response.toString());
        return response.path("result");
    }

    private static int errorCode(Object result) throws Exception {
        return Utils.OBJECT_MAPPER
                .readTree(assertInstanceOf(String.class, result))
                .path("error")
                .path("code")
                .asInt();
    }

    private static final int INTERNAL_ERROR_CODE = new InternalError("").getCode();

    private static Message unserializableMessage() {
        return new Message.Builder(A2A.toAgentMessage("unserializable"))
                .metadata(Map.of("value", new Unserializable()))
                .build();
    }

    /** A value Jackson cannot serialize. */
    static final class Unserializable {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }

    private static JsonNode readJson(Object json) {
        try {
            return Utils.OBJECT_MAPPER.readTree(assertInstanceOf(String.class, json));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertKind(String kind, Object json) {
        assertEquals(kind, readJson(json).path("result").path("kind").asText());
    }

    private static Flow.Publisher<SendStreamingMessageResponse> streamingResponses() {
        return burstPublisher(
                List.of(new SendStreamingMessageResponse("1", A2A.toAgentMessage("Hi"))));
    }

    private static void assertStreamedMessage(Object result) {
        StepVerifier.create(assertInstanceOf(Flux.class, result))
                .assertNext(
                        json -> {
                            try {
                                assertEquals("message", assertResult(json).path("kind").asText());
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            }
                        })
                .verifyComplete();
    }

    private static <T> Flow.Publisher<T> burstPublisher(List<T> values) {
        return subscriber ->
                subscriber.onSubscribe(
                        new Flow.Subscription() {
                            private boolean emitted;

                            @Override
                            public void request(long n) {
                                if (emitted) {
                                    return;
                                }
                                emitted = true;
                                for (T value : values) {
                                    subscriber.onNext(value);
                                }
                                subscriber.onComplete();
                            }

                            @Override
                            public void cancel() {}
                        });
    }
}

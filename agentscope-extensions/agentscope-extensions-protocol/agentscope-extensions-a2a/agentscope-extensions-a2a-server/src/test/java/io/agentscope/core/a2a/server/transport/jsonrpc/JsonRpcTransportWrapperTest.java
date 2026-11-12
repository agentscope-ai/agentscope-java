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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
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
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USER_MESSAGE =
            "{\"messageId\":\"message123\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Hello\"}]}";

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
            String body = request("SendMessage", "{\"message\":" + USER_MESSAGE + "}");
            when(jsonRpcHandler.onMessageSend(
                            any(SendMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new SendMessageResponse("1", A2A.toAgentMessage("Hi")));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            JsonNode message = assertResult(result).path("message");
            assertEquals("ROLE_AGENT", message.path("role").asText());
            assertEquals("Hi", message.path("parts").path(0).path("text").asText());
        }

        @Test
        @DisplayName("Should handle streaming request")
        void testHandleStreamingRequest() {
            String body = request("SendStreamingMessage", "{\"message\":" + USER_MESSAGE + "}");
            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(streamingResponses());

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertStreamedMessage(result);
        }

        @Test
        @DisplayName("Should mark the request context with the method and whether it streams")
        void testContextState() {
            String body = request("SendStreamingMessage", "{\"message\":" + USER_MESSAGE + "}");
            ArgumentCaptor<ServerCallContext> context =
                    ArgumentCaptor.forClass(ServerCallContext.class);
            when(jsonRpcHandler.onMessageSendStream(
                            any(SendStreamingMessageRequest.class), context.capture()))
                    .thenReturn(streamingResponses());
            Map<String, String> headers = Map.of("A2A-Version", "1.0");

            transportWrapper.handleRequest(body, headers, Map.of());

            Map<String, Object> state = context.getValue().getState();
            assertEquals("SendStreamingMessage", state.get(JSONRPCContextKeys.METHOD_NAME_KEY));
            assertEquals(true, state.get(A2aServerConstants.ContextKeys.IS_STREAM_KEY));
            assertEquals(headers, state.get(JSONRPCContextKeys.HEADERS_KEY));
        }

        @Test
        @DisplayName("Should pass the requested protocol version, whatever the header case")
        void testRequestedProtocolVersion() {
            String body = request("GetTask", "{\"id\":\"task123\"}");
            ArgumentCaptor<ServerCallContext> context =
                    ArgumentCaptor.forClass(ServerCallContext.class);
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), context.capture()))
                    .thenReturn(new GetTaskResponse("1", TASK));

            transportWrapper.handleRequest(body, Map.of("a2a-version", "1.0"), Map.of());

            assertEquals("1.0", context.getValue().getRequestedProtocolVersion());
        }

        @Test
        @DisplayName("Should handle GetTaskRequest")
        void testHandleGetTaskRequest() throws Exception {
            String body = request("GetTask", "{\"id\":\"task123\",\"historyLength\":10}");
            ArgumentCaptor<GetTaskRequest> request = ArgumentCaptor.forClass(GetTaskRequest.class);
            when(jsonRpcHandler.onGetTask(request.capture(), any(ServerCallContext.class)))
                    .thenReturn(new GetTaskResponse("1", TASK));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("task123", request.getValue().getParams().id());
            assertEquals(10, request.getValue().getParams().historyLength());
            JsonNode task = assertResult(result);
            assertEquals("task123", task.path("id").asText());
            assertEquals("TASK_STATE_WORKING", task.path("status").path("state").asText());
        }

        @Test
        @DisplayName("Should handle CancelTaskRequest")
        void testHandleCancelTaskRequest() throws Exception {
            String body = request("CancelTask", "{\"id\":\"task123\"}");
            when(jsonRpcHandler.onCancelTask(
                            any(CancelTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new CancelTaskResponse("1", TASK));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("task123", assertResult(result).path("id").asText());
        }

        @Test
        @DisplayName("Should reject ListTasks, which would let any caller list every task")
        void testRejectListTasksRequest() throws Exception {
            String body = request("ListTasks", "{\"historyLength\":10}");

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("1", readJson(result).path("id").asText());
            assertEquals(new UnsupportedOperationError().getCode(), errorCode(result));
            verify(jsonRpcHandler, never())
                    .onListTasks(any(ListTasksRequest.class), any(ServerCallContext.class));
        }

        @Test
        @DisplayName("Should handle GetTaskPushNotificationConfigRequest")
        void testHandleGetTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    request(
                            "GetTaskPushNotificationConfig",
                            "{\"taskId\":\"task123\",\"id\":\"111\"}");
            when(jsonRpcHandler.getPushNotificationConfig(
                            any(GetTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(new GetTaskPushNotificationConfigResponse("1", PUSH_CONFIG));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertPushConfig(assertResult(result));
        }

        @Test
        @DisplayName("Should handle CreateTaskPushNotificationConfigRequest")
        void testHandleCreateTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    request(
                            "CreateTaskPushNotificationConfig",
                            "{\"taskId\":\"task123\",\"url\":\"https://example.com/webhook\"}");
            when(jsonRpcHandler.setPushNotificationConfig(
                            any(CreateTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(new CreateTaskPushNotificationConfigResponse("1", PUSH_CONFIG));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertPushConfig(assertResult(result));
        }

        @Test
        @DisplayName("Should handle ListTaskPushNotificationConfigsRequest")
        void testHandleListTaskPushNotificationConfigsRequest() throws Exception {
            String body = request("ListTaskPushNotificationConfigs", "{\"taskId\":\"task123\"}");
            when(jsonRpcHandler.listPushNotificationConfigs(
                            any(ListTaskPushNotificationConfigsRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(
                            new ListTaskPushNotificationConfigsResponse(
                                    "1",
                                    new ListTaskPushNotificationConfigsResult(
                                            List.of(PUSH_CONFIG), null)));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            JsonNode configs = assertResult(result).path("configs");
            assertEquals(1, configs.size());
            assertPushConfig(configs.path(0));
        }

        @Test
        @DisplayName("Should handle DeleteTaskPushNotificationConfigRequest")
        void testHandleDeleteTaskPushNotificationConfigRequest() throws Exception {
            String body =
                    request(
                            "DeleteTaskPushNotificationConfig",
                            "{\"taskId\":\"task123\",\"id\":\"111\"}");
            when(jsonRpcHandler.deletePushNotificationConfig(
                            any(DeleteTaskPushNotificationConfigRequest.class),
                            any(ServerCallContext.class)))
                    .thenReturn(new DeleteTaskPushNotificationConfigResponse("1"));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertTrue(assertResult(result).isEmpty());
        }

        @Test
        @DisplayName("Should handle SubscribeToTaskRequest")
        void testHandleSubscribeToTaskRequest() {
            String body = request("SubscribeToTask", "{\"id\":\"task123\"}");
            when(jsonRpcHandler.onSubscribeToTask(
                            any(SubscribeToTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(streamingResponses());

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertStreamedMessage(result);
        }

        @Test
        @DisplayName("Should handle GetExtendedAgentCardRequest")
        void testHandleGetExtendedAgentCardRequest() throws Exception {
            String body = request("GetExtendedAgentCard", "{}");
            AgentCard card =
                    AgentCard.builder()
                            .name("extended")
                            .description("Extended card")
                            .version("1.0.0")
                            .capabilities(AgentCapabilities.builder().build())
                            .defaultInputModes(List.of("text"))
                            .defaultOutputModes(List.of("text"))
                            .skills(List.of())
                            .supportedInterfaces(
                                    List.of(new AgentInterface("JSONRPC", "http://localhost")))
                            .build();
            when(jsonRpcHandler.onGetExtendedCardRequest(
                            any(GetExtendedAgentCardRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new GetExtendedAgentCardResponse("1", card));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("extended", assertResult(result).path("name").asText());
        }

        @Test
        @DisplayName("Should write the error of an error response")
        void testHandleErrorResponse() throws Exception {
            String body = request("GetTask", "{\"id\":\"task123\"}");
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new GetTaskResponse("1", new TaskNotFoundError()));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            JsonNode response = readJson(result);
            assertEquals("1", response.path("id").asText());
            assertFalse(response.has("result"), response.toString());
            assertEquals(new TaskNotFoundError().getCode(), errorCode(result));
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
        void testUnserializableResponseKeepsRequestId() {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"id\":\"request-77\",\"method\":\"SendMessage\","
                            + "\"params\":{\"message\":"
                            + USER_MESSAGE
                            + "}}";
            when(jsonRpcHandler.onMessageSend(
                            any(SendMessageRequest.class), any(ServerCallContext.class)))
                    .thenReturn(new SendMessageResponse("request-77", unserializableMessage()));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            JsonNode response = readJson(result);
            assertEquals("request-77", response.path("id").asText());
            assertEquals(INTERNAL_ERROR_CODE, response.path("error").path("code").asInt());
        }

        @Test
        @DisplayName("Should end a stream with an internal error and stop reading responses")
        void testUnserializableStreamedResponseEndsStream() {
            String body = request("SendStreamingMessage", "{\"message\":" + USER_MESSAGE + "}");
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
                    .assertNext(json -> assertTrue(readJson(json).path("result").has("message")))
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
            for (String body : List.of("{ invalid json ", "", "   ")) {
                Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

                assertEquals(new JSONParseError().getCode(), errorCode(result), body);
            }
            verifyNoInteractions(jsonRpcHandler);
        }

        @Test
        @DisplayName("Should handle generic exception")
        void testHandleGenericException() throws Exception {
            String body = request("GetTask", "{\"id\":\"task123\"}");
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenThrow(new RuntimeException("Test exception"));

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("1", readJson(result).path("id").asText());
            JsonNode response = readJson(result);
            assertEquals(INTERNAL_ERROR_CODE, response.path("error").path("code").asInt());
            assertEquals("Internal error", response.path("error").path("message").asText());
            assertFalse(response.toString().contains("Test exception"));
        }

        @Test
        @DisplayName("Should answer an A2A error thrown by the handler")
        void testHandleThrownA2aError() throws Exception {
            String body = request("GetTask", "{\"id\":\"task123\"}");
            when(jsonRpcHandler.onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class)))
                    .thenThrow(new TaskNotFoundError());

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("1", readJson(result).path("id").asText());
            assertEquals(new TaskNotFoundError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should answer params of the wrong type as invalid params of the same request")
        void testHandleWrongParamType() throws Exception {
            // The SDK reports this error without the request id.
            String body = request("GetTask", "{\"id\":\"task123\",\"historyLength\":\"many\"}");

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("1", readJson(result).path("id").asText());
            assertEquals(new InvalidParamsError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should answer an invalid request id as an invalid request without id")
        void testHandleInvalidRequestId() throws Exception {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"id\":{\"value\":1},\"method\":\"GetTask\","
                            + "\"params\":{\"id\":\"task123\"}}";

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertFalse(readJson(result).hasNonNull("id"), readJson(result).toString());
            assertEquals(new InvalidRequestError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should answer valid JSON that is not an object as an invalid request")
        void testHandleNonObjectBody() throws Exception {
            for (String body : List.of("[]", "true", "1", "\"text\"", "null")) {
                Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

                assertEquals(new InvalidRequestError().getCode(), errorCode(result), body);
                assertFalse(readJson(result).hasNonNull("id"), body);
            }
            verifyNoInteractions(jsonRpcHandler);
        }

        @Test
        @DisplayName("Should handle invalid params")
        void testHandleInvalidParams() throws Exception {
            String body = request("GetTask", "{\"taskId\":\"task123\"}");

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("1", readJson(result).path("id").asText());
            assertEquals(new InvalidParamsError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should reject the method names of A2A 0.3")
        void testHandleLegacyMethodName() throws Exception {
            String body = request("tasks/get", "{\"id\":\"task123\"}");

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals("1", readJson(result).path("id").asText());
            assertEquals(new MethodNotFoundError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should handle MethodNotFoundError")
        void testHandleMethodNotFoundError() throws Exception {
            String body = request("not/found/method", "{\"id\":\"task123\"}");

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals(new MethodNotFoundError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should handle Method is null")
        void testHandleMethodNull() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"id\":\"task123\"},\"id\":\"1\"}";

            Object result = transportWrapper.handleRequest(body, Map.of(), Map.of());

            assertEquals(new InvalidRequestError().getCode(), errorCode(result));
        }

        @Test
        @DisplayName("Should not call the handler for a request it cannot parse")
        void testUnparsedRequestIsNotHandled() {
            transportWrapper.handleRequest("{ invalid json ", new HashMap<>(), new HashMap<>());

            verify(jsonRpcHandler, never())
                    .onGetTask(any(GetTaskRequest.class), any(ServerCallContext.class));
        }
    }

    private static final Task TASK =
            Task.builder()
                    .id("task123")
                    .contextId("context456")
                    .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                    .build();

    private static final TaskPushNotificationConfig PUSH_CONFIG =
            new TaskPushNotificationConfig(
                    "111", "task123", "https://example.com/webhook", null, null, null);

    private static String request(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\""
                + method
                + "\",\"params\":"
                + params
                + "}";
    }

    /** Parses a successful JSON-RPC response to request {@code 1} and returns its result. */
    private static JsonNode assertResult(Object result) {
        JsonNode response = readJson(result);
        assertEquals("1", response.path("id").asText());
        assertFalse(response.has("error"), response.toString());
        assertTrue(response.has("result"), response.toString());
        return response.path("result");
    }

    private static void assertPushConfig(JsonNode config) {
        assertEquals("111", config.path("id").asText());
        assertEquals("task123", config.path("taskId").asText());
        assertEquals("https://example.com/webhook", config.path("url").asText());
    }

    private static int errorCode(Object result) {
        return readJson(result).path("error").path("code").asInt();
    }

    private static final int INTERNAL_ERROR_CODE = new InternalError("").getCode();

    /** A message the A2A SDK fails to write. */
    private static Message unserializableMessage() {
        return mock(
                Message.class,
                invocation -> {
                    throw new IllegalStateException("not serializable");
                });
    }

    private static JsonNode readJson(Object json) {
        try {
            return MAPPER.readTree(assertInstanceOf(String.class, json));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Flow.Publisher<SendStreamingMessageResponse> streamingResponses() {
        return burstPublisher(
                List.of(new SendStreamingMessageResponse("1", A2A.toAgentMessage("Hi"))));
    }

    private static void assertStreamedMessage(Object result) {
        StepVerifier.create(assertInstanceOf(Flux.class, result))
                .assertNext(
                        json ->
                                assertEquals(
                                        "Hi",
                                        assertResult(json)
                                                .path("message")
                                                .path("parts")
                                                .path(0)
                                                .path("text")
                                                .asText()))
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

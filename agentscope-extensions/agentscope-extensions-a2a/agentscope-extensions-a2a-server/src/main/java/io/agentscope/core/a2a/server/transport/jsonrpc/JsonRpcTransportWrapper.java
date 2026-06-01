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

package io.agentscope.core.a2a.server.transport.jsonrpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.reactivestreams.FlowAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class JsonRpcTransportWrapper implements TransportWrapper<String, Object> {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcTransportWrapper.class);

    private final JSONRPCHandler jsonRpcHandler;

    public JsonRpcTransportWrapper(JSONRPCHandler jsonrpcHandler) {
        this.jsonRpcHandler = jsonrpcHandler;
    }

    @Override
    public String getTransportType() {
        return TransportProtocol.JSONRPC.asString();
    }

    @Override
    public Object handleRequest(
            String body, Map<String, String> headers, Map<String, Object> metadata) {
        ServerCallContext context = buildServerCallContext(headers, metadata);
        boolean streaming = isStreamingRequest(body, context);
        context.getState().put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, streaming);
        Object result;
        try {
            if (streaming) {
                result = handleStreamRequest(body, context);
                log.info("Handling streaming request, returning SSE Flux");
            } else {
                result = handleNonStreamRequest(body, context);
                log.info("Handling non-streaming request, returning JSON response");
            }
        } catch (JsonProcessingException e) {
            log.error("JSON parsing error: ", e);
            result = handleError(e);
        } catch (Throwable t) {
            log.error("Handle JSON-RPC request error:", t);
            Object requestId = extractIdFromBody(body);
            result =
                    new A2AErrorResponse(
                            requestId != null ? requestId : "error",
                            new InternalError(t.getMessage()));
        }
        return result;
    }

    private ServerCallContext buildServerCallContext(
            Map<String, String> headers, Map<String, Object> metadata) {
        Map<String, Object> state = new HashMap<>();
        Map<String, String> requestHeaders = headers == null ? Map.of() : headers;
        state.put(JSONRPCContextKeys.HEADERS_KEY, requestHeaders);
        String requestedProtocolVersion =
                getHeaderIgnoreCase(requestHeaders, A2AHeaders.A2A_VERSION);
        Set<String> requestedExtensions =
                A2AExtensions.getRequestedExtensions(
                        getHeaderValuesIgnoreCase(requestHeaders, A2AHeaders.A2A_EXTENSIONS));
        return new ServerCallContext(null, state, requestedExtensions, requestedProtocolVersion);
    }

    private String getHeaderIgnoreCase(Map<String, String> headers, String headerName) {
        String directValue = headers.get(headerName);
        if (directValue != null) {
            return directValue;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<String> getHeaderValuesIgnoreCase(Map<String, String> headers, String headerName) {
        String value = getHeaderIgnoreCase(headers, headerName);
        return value == null ? List.of() : List.of(value);
    }

    private boolean isStreamingRequest(String requestBody, ServerCallContext context) {
        try {
            JsonObject node = JsonParser.parseString(requestBody).getAsJsonObject();
            String methodName = node.has("method") ? node.get("method").getAsString() : null;
            if (methodName != null) {
                context.getState().put(JSONRPCContextKeys.METHOD_NAME_KEY, methodName);
                return A2AMethods.SEND_STREAMING_MESSAGE_METHOD.equals(methodName)
                        || A2AMethods.SUBSCRIBE_TO_TASK_METHOD.equals(methodName);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Object extractIdFromBody(String body) {
        try {
            JsonObject node = JsonParser.parseString(body).getAsJsonObject();
            if (node.has("id") && !node.get("id").isJsonNull()) {
                var idElement = node.get("id");
                if (idElement.isJsonPrimitive()) {
                    if (idElement.getAsJsonPrimitive().isString()) {
                        return idElement.getAsString();
                    }
                    return idElement.getAsNumber();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Flux<? extends A2AResponse<?>> handleStreamRequest(
            String body, ServerCallContext context) throws JsonProcessingException {
        String method = (String) context.getState().get(JSONRPCContextKeys.METHOD_NAME_KEY);
        Flow.Publisher<? extends A2AResponse<?>> publisher;
        if (A2AMethods.SEND_STREAMING_MESSAGE_METHOD.equals(method)) {
            SendStreamingMessageRequest req =
                    JsonUtil.fromJson(body, SendStreamingMessageRequest.class);
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        } else if (A2AMethods.SUBSCRIBE_TO_TASK_METHOD.equals(method)) {
            SubscribeToTaskRequest req = JsonUtil.fromJson(body, SubscribeToTaskRequest.class);
            publisher = jsonRpcHandler.onSubscribeToTask(req, context);
        } else {
            return Flux.just(new A2AErrorResponse("error", new UnsupportedOperationError()));
        }

        return Flux.from(FlowAdapters.toPublisher(publisher));
    }

    private A2AResponse<?> handleNonStreamRequest(String body, ServerCallContext context)
            throws JsonProcessingException {
        String method = (String) context.getState().get(JSONRPCContextKeys.METHOD_NAME_KEY);
        if (A2AMethods.GET_TASK_METHOD.equals(method)) {
            return jsonRpcHandler.onGetTask(JsonUtil.fromJson(body, GetTaskRequest.class), context);
        } else if (A2AMethods.LIST_TASK_METHOD.equals(method)) {
            return jsonRpcHandler.onListTasks(
                    JsonUtil.fromJson(body, ListTasksRequest.class), context);
        } else if (A2AMethods.SEND_MESSAGE_METHOD.equals(method)) {
            return jsonRpcHandler.onMessageSend(
                    JsonUtil.fromJson(body, SendMessageRequest.class), context);
        } else if (A2AMethods.CANCEL_TASK_METHOD.equals(method)) {
            return jsonRpcHandler.onCancelTask(
                    JsonUtil.fromJson(body, CancelTaskRequest.class), context);
        } else if (A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD.equals(method)) {
            return jsonRpcHandler.getPushNotificationConfig(
                    JsonUtil.fromJson(body, GetTaskPushNotificationConfigRequest.class), context);
        } else if (A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD.equals(method)) {
            return jsonRpcHandler.setPushNotificationConfig(
                    JsonUtil.fromJson(body, CreateTaskPushNotificationConfigRequest.class),
                    context);
        } else if (A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD.equals(method)) {
            return jsonRpcHandler.listPushNotificationConfigs(
                    JsonUtil.fromJson(body, ListTaskPushNotificationConfigsRequest.class), context);
        } else if (A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD.equals(method)) {
            return jsonRpcHandler.deletePushNotificationConfig(
                    JsonUtil.fromJson(body, DeleteTaskPushNotificationConfigRequest.class),
                    context);
        } else if (A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD.equals(method)) {
            return jsonRpcHandler.onGetExtendedCardRequest(
                    JsonUtil.fromJson(body, GetExtendedAgentCardRequest.class), context);
        } else {
            return new A2AErrorResponse("error", new UnsupportedOperationError());
        }
    }

    private A2AErrorResponse handleError(JsonProcessingException exception) {
        Object id = null;
        A2AError a2aError;
        if (exception instanceof MethodNotFoundJsonMappingException err) {
            id = err.getId();
            a2aError = new MethodNotFoundError();
        } else if (exception instanceof InvalidParamsJsonMappingException err) {
            id = err.getId();
            a2aError = new InvalidParamsError();
        } else if (exception instanceof IdJsonMappingException err) {
            id = err.getId();
            a2aError = new InvalidRequestError();
        } else {
            a2aError = new JSONParseError(exception.getMessage());
        }
        return new A2AErrorResponse(id != null ? id : "error", a2aError);
    }
}

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

import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
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
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;

/**
 * Wrapper for JSON-RPC transport requests.
 */
public class JsonRpcTransportWrapper implements TransportWrapper<String, Object> {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcTransportWrapper.class);

    private static final String STREAMING_BACKPRESSURE_BUFFER_SIZE_PROPERTY =
            "agentscope.a2a.streaming.backpressure-buffer-size";

    private static final int DEFAULT_STREAMING_BACKPRESSURE_BUFFER_SIZE = 8192;

    private static final int STREAMING_BACKPRESSURE_BUFFER_SIZE =
            Integer.getInteger(
                    STREAMING_BACKPRESSURE_BUFFER_SIZE_PROPERTY,
                    DEFAULT_STREAMING_BACKPRESSURE_BUFFER_SIZE);

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
        try {
            String tenant = stringValue(metadata.get(JSONRPCContextKeys.TENANT_KEY));
            A2ARequest<?> request = JSONRPCUtils.parseRequestBody(body, tenant);
            ServerCallContext context = buildServerCallContext(headers, metadata);
            context.getState().put(JSONRPCContextKeys.METHOD_NAME_KEY, request.getMethod());
            if (request instanceof NonStreamingJSONRPCRequest<?> nonStreamingRequest) {
                context.getState().put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, Boolean.FALSE);
                log.info("Handling non-streaming JSON-RPC request: {}", request.getMethod());
                return serializeResponse(processNonStreamingRequest(nonStreamingRequest, context));
            }
            context.getState().put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, Boolean.TRUE);
            log.info("Handling streaming JSON-RPC request: {}", request.getMethod());
            return processStreamingRequest(request, context)
                    .map(this::serializeResponse)
                    .onErrorResume(
                            t -> {
                                log.error(
                                        "Streaming JSON-RPC request failed, method={}, id={}",
                                        request.getMethod(),
                                        request.getId(),
                                        t);
                                return Flux.just(
                                        serializeResponse(
                                                new A2AErrorResponse(
                                                        request.getId(),
                                                        new InternalError(errorMessage(t)))));
                            });
        } catch (A2AError e) {
            return serializeResponse(new A2AErrorResponse(e));
        } catch (InvalidParamsJsonMappingException e) {
            return serializeResponse(
                    new A2AErrorResponse(
                            e.getId(), new InvalidParamsError(null, e.getMessage(), null)));
        } catch (MethodNotFoundJsonMappingException e) {
            return serializeResponse(
                    new A2AErrorResponse(
                            e.getId(), new MethodNotFoundError(null, e.getMessage(), null)));
        } catch (IdJsonMappingException e) {
            return serializeResponse(
                    new A2AErrorResponse(
                            e.getId(), new InvalidRequestError(null, e.getMessage(), null)));
        } catch (JsonMappingException e) {
            return serializeResponse(
                    new A2AErrorResponse(new InvalidRequestError(null, e.getMessage(), null)));
        } catch (JsonProcessingException | com.google.gson.JsonSyntaxException e) {
            return serializeResponse(new A2AErrorResponse(new JSONParseError(e.getMessage())));
        } catch (Throwable t) {
            log.error("Handle JSON-RPC request error:", t);
            return serializeResponse(new A2AErrorResponse(new InternalError(t.getMessage())));
        }
    }

    private ServerCallContext buildServerCallContext(
            Map<String, String> headers, Map<String, Object> metadata) {
        Map<String, Object> state = new HashMap<>();
        state.put(JSONRPCContextKeys.HEADERS_KEY, headers);
        state.put(
                JSONRPCContextKeys.TENANT_KEY,
                stringValue(metadata.get(JSONRPCContextKeys.TENANT_KEY)));
        state.put(ServerCallContext.TRANSPORT_KEY, TransportProtocol.JSONRPC);
        String requestedVersion = getHeader(headers, A2AHeaders.A2A_VERSION);
        Set<String> requestedExtensions =
                A2AExtensions.getRequestedExtensions(
                        List.of(stringValue(getHeader(headers, A2AHeaders.A2A_EXTENSIONS))));
        return new ServerCallContext(
                UnauthenticatedUser.INSTANCE, state, requestedExtensions, requestedVersion);
    }

    private A2AResponse<?> processNonStreamingRequest(
            NonStreamingJSONRPCRequest<?> request, ServerCallContext context) {
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        }
        if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        }
        if (request instanceof ListTasksRequest req) {
            return jsonRpcHandler.onListTasks(req, context);
        }
        if (request instanceof CreateTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        }
        if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        }
        if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        }
        if (request instanceof ListTaskPushNotificationConfigsRequest req) {
            return jsonRpcHandler.listPushNotificationConfigs(req, context);
        }
        if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        }
        if (request instanceof GetExtendedAgentCardRequest req) {
            return jsonRpcHandler.onGetExtendedCardRequest(req, context);
        }
        return new A2AErrorResponse(request.getId(), new UnsupportedOperationError());
    }

    private Flux<? extends A2AResponse<?>> processStreamingRequest(
            A2ARequest<?> request, ServerCallContext context) throws A2AError {
        if (request instanceof SendStreamingMessageRequest req) {
            jsonRpcHandler.validateRequestedTask(req.getParams().message().taskId());
        } else if (request instanceof SubscribeToTaskRequest req) {
            jsonRpcHandler.validateRequestedTask(req.getParams().id());
        }
        try {
            Flow.Publisher<? extends A2AResponse<?>> publisher;
            if (request instanceof SendStreamingMessageRequest req) {
                publisher = jsonRpcHandler.onMessageSendStream(req, context);
            } else if (request instanceof SubscribeToTaskRequest req) {
                publisher = jsonRpcHandler.onSubscribeToTask(req, context);
            } else {
                return Flux.just(
                        new A2AErrorResponse(request.getId(), new UnsupportedOperationError()));
            }
            return applyStreamingBackpressureBuffer(
                    JdkFlowAdapter.flowPublisherToFlux(publisher),
                    request.getMethod(),
                    request.getId());
        } catch (A2AError error) {
            return Flux.just(new A2AErrorResponse(request.getId(), error));
        }
    }

    private Flux<? extends A2AResponse<?>> applyStreamingBackpressureBuffer(
            Flux<? extends A2AResponse<?>> stream, String method, Object requestId) {
        if (STREAMING_BACKPRESSURE_BUFFER_SIZE <= 0) {
            return stream.onBackpressureBuffer();
        }
        return stream.onBackpressureBuffer(
                STREAMING_BACKPRESSURE_BUFFER_SIZE,
                response ->
                        log.error(
                                "JsonRpcTransportWrapper.stream backpressure buffer overflow:"
                                        + " method={}, requestId={}, bufferSize={}, dropped={}",
                                method,
                                requestId,
                                STREAMING_BACKPRESSURE_BUFFER_SIZE,
                                summarizeResponse(response)),
                BufferOverflowStrategy.ERROR);
    }

    private String summarizeResponse(A2AResponse<?> response) {
        if (response == null) {
            return "responseType=null";
        }
        return "responseType=" + response.getClass().getName();
    }

    private String serializeResponse(A2AResponse<?> response) {
        if (response instanceof A2AErrorResponse error) {
            return JSONRPCUtils.toJsonRPCErrorResponse(error.getId(), error.getError());
        }
        if (response.getError() != null) {
            return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
        }
        return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), convertToProto(response));
    }

    private com.google.protobuf.MessageOrBuilder convertToProto(A2AResponse<?> response) {
        if (response instanceof GetTaskResponse r) {
            return ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof CancelTaskResponse r) {
            return ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof SendMessageResponse r) {
            return ProtoUtils.ToProto.taskOrMessage(r.getResult());
        } else if (response instanceof ListTasksResponse r) {
            return ProtoUtils.ToProto.listTasksResult(r.getResult());
        } else if (response instanceof CreateTaskPushNotificationConfigResponse r) {
            return ProtoUtils.ToProto.createTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof GetTaskPushNotificationConfigResponse r) {
            return ProtoUtils.ToProto.getTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof ListTaskPushNotificationConfigsResponse r) {
            return ProtoUtils.ToProto.listTaskPushNotificationConfigsResponse(r.getResult());
        } else if (response instanceof DeleteTaskPushNotificationConfigResponse) {
            return com.google.protobuf.Empty.getDefaultInstance();
        } else if (response instanceof GetExtendedAgentCardResponse r) {
            return ProtoUtils.ToProto.getExtendedCardResponse(r.getResult());
        } else if (response instanceof SendStreamingMessageResponse r) {
            return ProtoUtils.ToProto.taskOrMessageStream(r.getResult());
        }
        throw new IllegalArgumentException(
                "Unknown response type: " + response.getClass().getName());
    }

    private String getHeader(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        String value = headers.get(name);
        if (value != null) {
            return value;
        }
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String errorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown streaming error";
        }
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            return throwable.getMessage();
        }
        return throwable.getClass().getName();
    }
}

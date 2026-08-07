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

import io.agentscope.core.a2a.server.auth.A2aAuthErrorCodes;
import io.agentscope.core.a2a.server.auth.A2aAuthException;
import io.agentscope.core.a2a.server.auth.A2aAuthRequest;
import io.agentscope.core.a2a.server.auth.A2aAuthResolver;
import io.agentscope.core.a2a.server.auth.A2aAuthentication;
import io.agentscope.core.a2a.server.auth.A2aIdentity;
import io.agentscope.core.a2a.server.auth.A2aPrincipal;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final A2aAuthResolver authResolver;

    public JsonRpcTransportWrapper(JSONRPCHandler jsonrpcHandler) {
        this(jsonrpcHandler, A2aAuthResolver.anonymous());
    }

    public JsonRpcTransportWrapper(JSONRPCHandler jsonrpcHandler, A2aAuthResolver authResolver) {
        this.jsonRpcHandler = jsonrpcHandler;
        this.authResolver = authResolver == null ? A2aAuthResolver.anonymous() : authResolver;
    }

    @Override
    public String getTransportType() {
        return TransportProtocol.JSONRPC.asString();
    }

    @Override
    public Object handleRequest(
            String body, Map<String, String> headers, Map<String, Object> metadata) {
        PreparedRequest prepared = prepareRequest(body, headers, metadata);
        if (!prepared.isReady()) {
            return prepared.getProtocolErrorResponse();
        }
        A2aAuthentication authentication = authResolver.resolve(prepared.toAuthRequest());
        return handlePreparedRequest(prepared, authentication);
    }

    /**
     * Parses a JSON-RPC request without authenticating or dispatching it.
     *
     * <p>Protocol errors remain HTTP 200 JSON-RPC responses. Authentication must happen only
     * after this method returns a ready request.
     */
    public PreparedRequest prepareRequest(
            String body, Map<String, String> headers, Map<String, Object> metadata) {
        try {
            Map<String, String> safeHeaders = immutableStringMap(headers);
            Map<String, Object> safeMetadata = immutableObjectMap(metadata);
            String tenant = stringValue(safeMetadata.get(JSONRPCContextKeys.TENANT_KEY));
            A2ARequest<?> request = JSONRPCUtils.parseRequestBody(body, tenant);
            return PreparedRequest.ready(
                    request, safeHeaders, safeMetadata, extractRequestMetadata(request));
        } catch (A2AError e) {
            return PreparedRequest.failed(serializeResponse(new A2AErrorResponse(e)));
        } catch (InvalidParamsJsonMappingException e) {
            return PreparedRequest.failed(
                    serializeResponse(
                            new A2AErrorResponse(
                                    e.getId(),
                                    new InvalidParamsError(null, e.getMessage(), null))));
        } catch (MethodNotFoundJsonMappingException e) {
            return PreparedRequest.failed(
                    serializeResponse(
                            new A2AErrorResponse(
                                    e.getId(),
                                    new MethodNotFoundError(null, e.getMessage(), null))));
        } catch (IdJsonMappingException e) {
            return PreparedRequest.failed(
                    serializeResponse(
                            new A2AErrorResponse(
                                    e.getId(),
                                    new InvalidRequestError(null, e.getMessage(), null))));
        } catch (JsonMappingException e) {
            return PreparedRequest.failed(
                    serializeResponse(
                            new A2AErrorResponse(
                                    new InvalidRequestError(null, e.getMessage(), null))));
        } catch (JsonProcessingException | com.google.gson.JsonSyntaxException e) {
            return PreparedRequest.failed(
                    serializeResponse(new A2AErrorResponse(new JSONParseError(e.getMessage()))));
        } catch (Throwable t) {
            log.error("Prepare JSON-RPC request error:", t);
            return PreparedRequest.failed(
                    serializeResponse(new A2AErrorResponse(new InternalError(errorMessage(t)))));
        }
    }

    /**
     * Binds an already authenticated caller and dispatches a prepared request.
     *
     * <p>Authentication and user-binding failures happen before the business dispatch catch
     * and before a streaming response is created.
     */
    public Object handlePreparedRequest(
            PreparedRequest prepared, A2aAuthentication authentication) {
        if (prepared == null || !prepared.isReady()) {
            throw new IllegalArgumentException("prepared request is required");
        }
        BoundUser boundUser = bindUser(prepared, authentication);
        ServerCallContext context = buildServerCallContext(prepared, boundUser);
        return dispatch(prepared.request, context);
    }

    private Object dispatch(A2ARequest<?> request, ServerCallContext context) {
        try {
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
                                A2AError requestError = findA2AError(t);
                                if (requestError == null) {
                                    log.error(
                                            "Streaming JSON-RPC request failed, method={}, id={}",
                                            request.getMethod(),
                                            request.getId(),
                                            t);
                                } else {
                                    log.debug(
                                            "Streaming JSON-RPC request rejected, method={}, id={},"
                                                    + " code={}",
                                            request.getMethod(),
                                            request.getId(),
                                            requestError.getCode());
                                }
                                return Flux.just(
                                        serializeResponse(
                                                new A2AErrorResponse(
                                                        request.getId(),
                                                        requestError != null
                                                                ? requestError
                                                                : new InternalError(
                                                                        errorMessage(t)))));
                            });
        } catch (A2AError e) {
            return serializeResponse(new A2AErrorResponse(e));
        } catch (Throwable t) {
            log.error("Dispatch JSON-RPC request error:", t);
            return serializeResponse(new A2AErrorResponse(new InternalError(errorMessage(t))));
        }
    }

    private ServerCallContext buildServerCallContext(
            PreparedRequest prepared, BoundUser boundUser) {
        Map<String, Object> state = new HashMap<>();
        state.put(JSONRPCContextKeys.HEADERS_KEY, prepared.headers);
        state.put(
                JSONRPCContextKeys.TENANT_KEY,
                stringValue(prepared.transportMetadata.get(JSONRPCContextKeys.TENANT_KEY)));
        state.put(ServerCallContext.TRANSPORT_KEY, TransportProtocol.JSONRPC);
        state.put(A2aServerConstants.ContextKeys.PRINCIPAL_KEY, boundUser.principal);
        if (boundUser.effectiveUserId != null) {
            state.put(
                    A2aServerConstants.ContextKeys.EFFECTIVE_USER_ID_KEY,
                    boundUser.effectiveUserId);
        }
        if (boundUser.identity != null) {
            state.put(A2aServerConstants.ContextKeys.IDENTITY_KEY, boundUser.identity);
        }
        String requestedVersion = getHeader(prepared.headers, A2AHeaders.A2A_VERSION);
        Set<String> requestedExtensions =
                A2AExtensions.getRequestedExtensions(
                        List.of(
                                stringValue(
                                        getHeader(prepared.headers, A2AHeaders.A2A_EXTENSIONS))));
        return new ServerCallContext(
                boundUser.principal, state, requestedExtensions, requestedVersion);
    }

    private BoundUser bindUser(PreparedRequest prepared, A2aAuthentication authentication) {
        if (authentication == null) {
            throw new A2aAuthException(503, A2aAuthErrorCodes.AUTH_UNAVAILABLE);
        }
        A2aPrincipal principal = authentication.getPrincipal();
        String requestedUserId = trimToNull(prepared.requestMetadata.get("userId"));
        A2aIdentity identity = authentication.toIdentity();
        return identity == null
                ? new BoundUser(principal, requestedUserId, null)
                : new BoundUser(principal, identity.userId(), identity);
    }

    private Map<String, Object> extractRequestMetadata(A2ARequest<?> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (request instanceof SendMessageRequest req) {
            mergeMessageMetadata(result, req.getParams().metadata(), req.getParams().message());
        } else if (request instanceof SendStreamingMessageRequest req) {
            mergeMessageMetadata(result, req.getParams().metadata(), req.getParams().message());
        }
        return immutableObjectMap(result);
    }

    private void mergeMessageMetadata(
            Map<String, Object> target,
            Map<String, Object> requestMetadata,
            org.a2aproject.sdk.spec.Message message) {
        if (requestMetadata != null) {
            target.putAll(requestMetadata);
        }
        if (message != null && message.metadata() != null) {
            target.putAll(message.metadata());
        }
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
        try {
            if (request instanceof SendStreamingMessageRequest req) {
                jsonRpcHandler.validateRequestedTask(req.getParams().message().taskId());
            } else if (request instanceof SubscribeToTaskRequest req) {
                jsonRpcHandler.validateRequestedTask(req.getParams().id());
            }
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
        } catch (Throwable failure) {
            return Flux.error(failure);
        }
    }

    private A2AError findA2AError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof A2AError a2aError) {
                return a2aError;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return null;
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

    private Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record BoundUser(
            A2aPrincipal principal, String effectiveUserId, A2aIdentity identity) {}

    /** Parsed JSON-RPC request that is safe to authenticate before dispatch. */
    public static final class PreparedRequest {

        private final A2ARequest<?> request;
        private final Map<String, String> headers;
        private final Map<String, Object> transportMetadata;
        private final Map<String, Object> requestMetadata;
        private final String protocolErrorResponse;

        private PreparedRequest(
                A2ARequest<?> request,
                Map<String, String> headers,
                Map<String, Object> transportMetadata,
                Map<String, Object> requestMetadata,
                String protocolErrorResponse) {
            this.request = request;
            this.headers = headers;
            this.transportMetadata = transportMetadata;
            this.requestMetadata = requestMetadata;
            this.protocolErrorResponse = protocolErrorResponse;
        }

        private static PreparedRequest ready(
                A2ARequest<?> request,
                Map<String, String> headers,
                Map<String, Object> transportMetadata,
                Map<String, Object> requestMetadata) {
            return new PreparedRequest(request, headers, transportMetadata, requestMetadata, null);
        }

        private static PreparedRequest failed(String protocolErrorResponse) {
            return new PreparedRequest(null, Map.of(), Map.of(), Map.of(), protocolErrorResponse);
        }

        public boolean isReady() {
            return request != null;
        }

        public String getProtocolErrorResponse() {
            return protocolErrorResponse;
        }

        public A2aAuthRequest toAuthRequest() {
            if (!isReady()) {
                throw new IllegalStateException("protocol-error request cannot be authenticated");
            }
            return new A2aAuthRequest(
                    TransportProtocol.JSONRPC.asString(),
                    request.getMethod(),
                    headers,
                    transportMetadata,
                    requestMetadata);
        }
    }
}

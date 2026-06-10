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

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.server.ServerCallContext;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.IdJsonMappingException;
import io.a2a.spec.InternalError;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidParamsJsonMappingException;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCRequest;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.MethodNotFoundJsonMappingException;
import io.a2a.spec.NonStreamingJSONRPCRequest;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.StreamingJSONRPCRequest;
import io.a2a.spec.TaskResubscriptionRequest;
import io.a2a.spec.TransportProtocol;
import io.a2a.spec.UnsupportedOperationError;
import io.a2a.transport.jsonrpc.context.JSONRPCContextKeys;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.a2a.util.Utils;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Flow;
import org.reactivestreams.FlowAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * The Wrapper for JSON-RPC transport request.
 *
 * <p> wrapper all JSON-RPC request pre-handle logic, developer should get string body and some headers and metadata
 * from request.
 * <p> The pre-handle logic with:
 * <ul>
 *     <li>deserialize request body to JSON-RPC request.</li>
 *     <li>judge whether the request is streaming request.</li>
 *     <li>build {@link io.a2a.server.ServerCallContext} from JSON-RPC method, headers and all metadata.</li>
 *     <li>handle error and exception for JSON-RPC request handle.</li>
 * </ul>
 */
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

    /**
     * Do handle for JSON-RPC Request, including streaming and non-streaming request.
     *
     * @param body     JSON-RPC request body string
     * @param headers  JSON-RPC request headers map
     * @param metadata Other JSON-RPC request metadata from request or developer.
     * @return Two type according to the request whether streaming request: If streaming request, return {@link Flux}
     * with {@link JSONRPCResponse}, otherwise only single {@link JSONRPCResponse}. When handle with error or
     * exceptions, will return {@link JSONRPCErrorResponse}.
     */
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
            result = new JSONRPCErrorResponse(new InternalError(t.getMessage()));
        }
        return result;
    }

    private ServerCallContext buildServerCallContext(
            Map<String, String> headers, Map<String, Object> metadata) {
        Map<String, Object> state = new HashMap<>();
        state.put(JSONRPCContextKeys.HEADERS_KEY, headers);
        // TODO add user when support authenticate
        return new ServerCallContext(null, state, new HashSet<>());
    }

    private boolean isStreamingRequest(String requestBody, ServerCallContext context) {
        try {
            JsonNode node = Utils.OBJECT_MAPPER.readTree(requestBody);
            JsonNode method = node != null ? node.get("method") : null;
            String methodName = method != null ? method.asText() : null;
            if (methodName != null) {
                context.getState().put(JSONRPCContextKeys.METHOD_NAME_KEY, methodName);
                return SendStreamingMessageRequest.METHOD.equals(methodName)
                        || TaskResubscriptionRequest.METHOD.equals(methodName);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Flux<? extends JSONRPCResponse<?>> handleStreamRequest(
            String body, ServerCallContext context) throws JsonProcessingException {
        StreamingJSONRPCRequest<?> request =
                Utils.OBJECT_MAPPER.readValue(body, StreamingJSONRPCRequest.class);
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher;
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        } else if (request instanceof TaskResubscriptionRequest req) {
            publisher = jsonRpcHandler.onResubscribeToTask(req, context);
        } else {
            return Flux.just(generateErrorResponse(request, new UnsupportedOperationError()));
        }

        return Flux.from(FlowAdapters.toPublisher(publisher))
                .delaySubscription(Duration.ofMillis(10));
    }

    private JSONRPCResponse<?> handleNonStreamRequest(String body, ServerCallContext context)
            throws JsonProcessingException {
        NonStreamingJSONRPCRequest<?> request =
                Utils.OBJECT_MAPPER.readValue(body, NonStreamingJSONRPCRequest.class);
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        } else if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        } else if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        } else if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        } else if (request instanceof SetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        } else if (request instanceof ListTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.listPushNotificationConfig(req, context);
        } else if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        } else {
            return generateErrorResponse(request, new UnsupportedOperationError());
        }
    }

    private JSONRPCErrorResponse handleError(JsonProcessingException exception) {
        Object id = null;
        JSONRPCError jsonRpcError = null;
        if (exception instanceof JsonParseException) {
            jsonRpcError = new JSONParseError(exception.getMessage());
        } else if (exception instanceof MethodNotFoundJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new MethodNotFoundError();
        } else if (exception instanceof InvalidParamsJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidParamsError();
        } else if (exception instanceof IdJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidRequestError();
        } else {
            jsonRpcError = new InvalidRequestError();
        }
        return new JSONRPCErrorResponse(id, jsonRpcError);
    }

    private JSONRPCErrorResponse generateErrorResponse(
            JSONRPCRequest<?> request, JSONRPCError error) {
        return new JSONRPCErrorResponse(request.getId(), error);
    }
}

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
package io.agentscope.extensions.rocketmq.a2a.server;

import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildLitePushConsumer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildMessageForResponse;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildProducer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildPushConsumer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.toJsonString;

import com.alibaba.fastjson.JSON;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCResponse;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.agentscope.extensions.rocketmq.a2a.config.RocketMQA2aConfig;
import io.quarkus.vertx.web.ReactiveRoutes.ServerSentEvent;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant;
import org.apache.rocketmq.a2a.common.model.RocketMQRequest;
import org.apache.rocketmq.a2a.common.model.RocketMQResponse;
import org.apache.rocketmq.a2a.common.model.RocketMQResponse.Builder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * RocketMQ-based A2A (Agent-to-Agent) server implementation.
 * Handles JSON-RPC requests via RocketMQ messaging and supports streaming responses.
 */
public class RocketMQA2aServer {
    private static final Logger log = LoggerFactory.getLogger(RocketMQA2aServer.class);

    /**
     * The network endpoint of the RocketMQ service.
     */
    private String rocketMQEndpoint;

    /**
     * The namespace used for logical isolation of RocketMQ resources.
     */
    private String rocketMQNamespace;

    /**
     * The standard RocketMQ business topic bound to the Agent, used for receiving task requests and other information.
     */
    private String bizTopic;

    /**
     * The CID used to subscribe to the standard business topic bound to the Agent.
     */
    private String bizConsumerGroup;

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    private String accessKey;

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    private String secretKey;

    /**
     * The lightweight topic used to receive asynchronous replies.
     */
    private String workAgentResponseTopic;

    /**
     * The consumer group ID used when subscribing to the {@link #workAgentResponseTopic}.
     */
    private String workAgentResponseGroupId;

    /**
     * The RocketMQ Producer used to send response results.
     */
    private Producer producer;

    /**
     * The standard PushConsumer used to receive request messages.
     */
    private PushConsumer pushConsumer;

    /**
     * The consumer for receiving request messages during point-to-point interactions from clients to server.
     */
    private LitePushConsumer litePushConsumer;

    /**
     * Dynamically generated topic for this server instance to receive direct (point-to-point) requests.
     */
    private String serverLiteTopic;

    /**
     * Used to send SSE data streams.
     */
    private FluxSseSupport fluxSseSupport;

    /**
     * The JSONRPCHandler in the A2A protocol.
     */
    private final JsonRpcTransportWrapper jsonRpcHandler;

    /**
     * Thread pool for handling streaming responses.
     */
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    6,
                    6,
                    60L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(100_000),
                    new ThreadFactoryBuilder().setNameFormat("rocketmq-a2a-sse-pool-%d").build(),
                    new CallerRunsPolicy());

    /**
     * The underlying A2A server for request handling.
     */
    private final AgentScopeA2aServer agentScopeA2aServer;

    /**
     * Constructs a RocketMQ A2A server with the given configuration.
     *
     * @param agentScopeA2aServer the underlying A2A server.
     * @param rocketMQA2aConfig the RocketMQ configuration.
     */
    public RocketMQA2aServer(
            AgentScopeA2aServer agentScopeA2aServer, RocketMQA2aConfig rocketMQA2aConfig) {
        this.agentScopeA2aServer = agentScopeA2aServer;
        this.jsonRpcHandler =
                agentScopeA2aServer.getTransportWrapper(
                        RocketMQA2AConstant.ROCKETMQ_PROTOCOL, JsonRpcTransportWrapper.class);
        this.rocketMQEndpoint = rocketMQA2aConfig.getRocketMQEndpoint();
        this.rocketMQNamespace = rocketMQA2aConfig.getRocketMQNamespace();
        this.bizTopic = rocketMQA2aConfig.getBizTopic();
        this.bizConsumerGroup = rocketMQA2aConfig.getBizConsumerGroup();
        this.accessKey = rocketMQA2aConfig.getAccessKey();
        this.secretKey = rocketMQA2aConfig.getSecretKey();
        this.workAgentResponseTopic = rocketMQA2aConfig.getWorkAgentResponseTopic();
        this.workAgentResponseGroupId = rocketMQA2aConfig.getWorkAgentResponseGroupId();
        init();
    }

    /**
     * Initializes RocketMQ producers, consumers and the server lite topic.
     */
    public void init() {
        try {
            checkConfigParam();
            this.producer =
                    buildProducer(rocketMQNamespace, rocketMQEndpoint, accessKey, secretKey);
            this.pushConsumer =
                    buildPushConsumer(
                            rocketMQEndpoint,
                            rocketMQNamespace,
                            accessKey,
                            secretKey,
                            bizConsumerGroup,
                            bizTopic,
                            buildMessageListener());
            this.fluxSseSupport = new FluxSseSupport(this.producer);
            this.litePushConsumer =
                    buildLitePushConsumer(
                            rocketMQEndpoint,
                            rocketMQNamespace,
                            accessKey,
                            secretKey,
                            workAgentResponseTopic,
                            workAgentResponseGroupId,
                            buildMessageListener());
            // Init serverLiteTopic
            this.serverLiteTopic = UUID.randomUUID().toString();
            this.litePushConsumer.subscribeLite(serverLiteTopic);
            log.info(
                    "RocketMQA2aServer init successfully, bizTopic: [{}], bizConsumerGroup:"
                            + " [{}], workAgentResponseTopic: [{}], workAgentResponseGroupID: [{}],"
                            + " serverLiteTopic: [{}]",
                    bizTopic,
                    bizConsumerGroup,
                    workAgentResponseGroupId,
                    workAgentResponseTopic,
                    serverLiteTopic);
        } catch (Exception e) {
            log.error("RocketMQA2aServer error", e);
        }
    }

    /**
     * Builds a message listener that processes incoming RocketMQ messages as A2A JSON-RPC requests.
     *
     * @return a configured {@link MessageListener}.
     */
    private MessageListener buildMessageListener() {
        return messageView -> {
            CompletableFuture<Boolean> completableFuture = null;
            try {
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                RocketMQRequest request =
                        JSON.parseObject(
                                new String(result, StandardCharsets.UTF_8), RocketMQRequest.class);
                if (null == request) {
                    return ConsumeResult.SUCCESS;
                }
                Object resultObject =
                        jsonRpcHandler.handleRequest(
                                request.getRequestBody(), request.getRequestHeader(), Map.of());
                JSONRPCResponse<?> nonStreamingResponse = null;
                JSONRPCErrorResponse error = null;
                Flux<? extends JSONRPCResponse<?>> streamingResponse = null;
                boolean streaming = false;
                if (resultObject instanceof JSONRPCErrorResponse) {
                    error = (JSONRPCErrorResponse) resultObject;
                } else if (resultObject instanceof JSONRPCResponse<?>) {
                    nonStreamingResponse = (JSONRPCResponse<?>) resultObject;
                } else if (resultObject instanceof Flux) {
                    streaming = true;
                    completableFuture = new CompletableFuture<>();
                    streamingResponse = (Flux<? extends JSONRPCResponse<?>>) resultObject;
                } else {
                    log.warn(
                            "RocketMQA2aServer resultObject is not instanceof"
                                    + " JSONRPCErrorResponse or JSONRPCResponse or Flux");
                }
                processResponse(
                        request,
                        error,
                        streaming,
                        nonStreamingResponse,
                        streamingResponse,
                        completableFuture);
            } catch (Exception e) {
                log.error("RocketMQA2aServer error", e);
                return ConsumeResult.FAILURE;
            }
            return processCompletableFuture(completableFuture);
        };
    }

    /**
     * Processes the JSON-RPC response and sends it back via RocketMQ.
     *
     * @param request the original request.
     * @param error error response if any.
     * @param streaming whether this is a streaming response.
     * @param nonStreamingResponse non-streaming response object.
     * @param streamingResponse streaming response flux.
     * @param completableFuture future for tracking completion.
     * @throws ClientException if sending fails.
     */
    private void processResponse(
            RocketMQRequest request,
            JSONRPCErrorResponse error,
            boolean streaming,
            JSONRPCResponse<?> nonStreamingResponse,
            Flux<? extends JSONRPCResponse<?>> streamingResponse,
            CompletableFuture completableFuture)
            throws ClientException {
        RocketMQResponse response = null;
        if (error != null) {
            response = buildErrorResponse(error);
        } else if (!streaming) {
            response = buildSuccessResponse(nonStreamingResponse);
        } else {
            final Flux<? extends JSONRPCResponse<?>> finalStreamingResponse = streamingResponse;
            executor.execute(
                    () -> {
                        fluxSseSupport.subscribeObjectRocketMQ(
                                finalStreamingResponse.map(i -> (Object) i),
                                request.getWorkAgentResponseTopic(),
                                request.getLiteTopic(),
                                request.getRequestId(),
                                completableFuture);
                    });
        }
        if (null != response) {
            SendReceipt send =
                    this.producer.send(
                            buildMessageForResponse(
                                    request.getWorkAgentResponseTopic(),
                                    request.getLiteTopic(),
                                    response));
            log.debug(
                    "RocketMQA2aServer send nonStreamingResponse successfully, msgId: [{}],"
                            + " response: [{}]",
                    send.getMessageId(),
                    JSON.toJSONString(response));
        }
    }

    /**
     * Constructs a RocketMQ response for failed JSON-RPC calls.
     *
     * @param error the JSON-RPC error details to include in the response body.
     * @return a fully built {@link RocketMQResponse} representing an error result.
     */
    private RocketMQResponse buildErrorResponse(JSONRPCErrorResponse error) {
        return buildBaseResponse().end(true).stream(false)
                .responseBody(toJsonString(JSON.toJSONString(error)))
                .build();
    }

    /**
     * Constructs a success response for non-streaming JSON-RPC operations.
     *
     * @param nonStreamingResponse the successful JSON-RPC response object to serialize.
     * @return a fully built {@link RocketMQResponse} representing a successful result.
     */
    private RocketMQResponse buildSuccessResponse(JSONRPCResponse<?> nonStreamingResponse) {
        return buildBaseResponse().end(true).stream(false)
                .responseBody(toJsonString(nonStreamingResponse))
                .build();
    }

    /**
     * Creates a pre-populated builder with common fields from the message context.
     *
     * @return a configured {@link RocketMQResponse.Builder} ready for additional settings.
     */
    private RocketMQResponse.Builder buildBaseResponse() {
        Builder builder = RocketMQResponse.builder();
        builder.serverLiteTopic(serverLiteTopic)
                .serverWorkAgentResponseTopic(workAgentResponseTopic);
        return builder;
    }

    /**
     * Waits for the completion of a CompletableFuture with timeout,
     * and maps the result to a {@link ConsumeResult} for message consumption acknowledgment.
     *
     * @param completableFuture the future to wait on.
     */
    private ConsumeResult processCompletableFuture(CompletableFuture<Boolean> completableFuture) {
        if (null == completableFuture) {
            return ConsumeResult.SUCCESS;
        }
        try {
            return Boolean.TRUE.equals(completableFuture.get(15, TimeUnit.MINUTES))
                    ? ConsumeResult.SUCCESS
                    : ConsumeResult.FAILURE;
        } catch (Exception e) {
            log.error("RocketMQA2aServer processCompletableFuture error", e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * Helper class for handling Server-Sent Events (SSE) streaming via RocketMQ.
     */
    private static class FluxSseSupport {
        private final Producer producer;

        private FluxSseSupport(Producer producer) {
            this.producer = producer;
        }

        /**
         * Subscribes to a Flux and sends events as RocketMQ messages.
         *
         * @param multi the flux of events.
         * @param workAgentResponseTopic the response topic.
         * @param liteTopic the lite topic for direct messaging.
         * @param requestId the message ID.
         * @param completableFuture future to complete when streaming ends.
         */
        public void subscribeObjectRocketMQ(
                Flux<Object> multi,
                String workAgentResponseTopic,
                String liteTopic,
                String requestId,
                CompletableFuture<Boolean> completableFuture) {
            if (null == multi
                    || StringUtils.isEmpty(workAgentResponseTopic)
                    || StringUtils.isEmpty(liteTopic)
                    || StringUtils.isEmpty(requestId)) {
                log.warn(
                        "subscribeObjectRocketMQ param error, multi: {}, workAgentResponseTopic:"
                                + " {}, liteTopic: {}, requestId: {}",
                        multi,
                        workAgentResponseTopic,
                        liteTopic,
                        requestId);
                completableFuture.complete(false);
                return;
            }
            AtomicLong count = new AtomicLong();
            Flux<Buffer> map =
                    multi.map(
                            new Function<Object, Buffer>() {
                                @Override
                                public Buffer apply(Object o) {
                                    if (o instanceof ServerSentEvent<?> ev) {
                                        String id =
                                                ev.id() != -1
                                                        ? String.valueOf(ev.id())
                                                        : String.valueOf(count.getAndIncrement());
                                        return Buffer.buffer(
                                                "data: " + ev.data() + "\nid: " + id + "\n\n");
                                    }
                                    return Buffer.buffer(
                                            "data: "
                                                    + toJsonString(o)
                                                    + "\nid: "
                                                    + count.getAndIncrement()
                                                    + "\n\n");
                                }
                            });
            writeRocketMQ(map, workAgentResponseTopic, liteTopic, requestId, completableFuture);
        }

        /**
         * Writes Flux data to RocketMQ as a stream of messages.
         *
         * @param flux the flux of buffer data.
         * @param workAgentResponseTopic the response topic.
         * @param liteTopic the lite topic.
         * @param requestId the message ID.
         * @param completableFuture future to complete on success or error.
         */
        private void writeRocketMQ(
                Flux<Buffer> flux,
                String workAgentResponseTopic,
                String liteTopic,
                String requestId,
                CompletableFuture<Boolean> completableFuture) {
            flux.subscribe(
                    // next
                    event -> {
                        try {
                            producer.send(
                                    buildMessageForResponse(
                                            workAgentResponseTopic,
                                            liteTopic,
                                            RocketMQResponse.builder()
                                                    .responseBody(event.toString())
                                                    .requestId(requestId)
                                                    .stream(true)
                                                    .end(false)
                                                    .build()));
                        } catch (ClientException error) {
                            log.error("writeRocketMQ send stream error: {}", error.getMessage());
                        }
                    },
                    // error
                    error -> {
                        log.error("writeRocketMQ send stream error: {}", error.getMessage());
                        completableFuture.complete(false);
                    },
                    // complete
                    () -> {
                        try {
                            producer.send(
                                    buildMessageForResponse(
                                            workAgentResponseTopic,
                                            liteTopic,
                                            RocketMQResponse.builder()
                                                    .responseBody(null)
                                                    .requestId(requestId)
                                                    .stream(true)
                                                    .end(true)
                                                    .build()));
                        } catch (ClientException e) {
                            log.error("writeRocketMQ send stream error: {}", e.getMessage());
                        }
                        completableFuture.complete(true);
                    });
        }
    }

    /**
     * Validates required configuration parameters for initializing RocketMQA2aServer.
     * <p>All parameters are mandatory. If any is {@code null} or empty, an {@link IllegalArgumentException}
     * is thrown with detailed information about which field(s) failed validation.
     */
    private void checkConfigParam() {
        Map<String, String> configParams = new LinkedHashMap<>();
        configParams.put("rocketMQEndpoint", rocketMQEndpoint);
        configParams.put("bizTopic", bizTopic);
        configParams.put("bizConsumerGroup", bizConsumerGroup);
        configParams.put("workAgentResponseTopic", workAgentResponseTopic);
        configParams.put("workAgentResponseGroupID", workAgentResponseGroupId);
        List<String> missingParams = new ArrayList<>();
        for (Map.Entry<String, String> entry : configParams.entrySet()) {
            if (StringUtils.isEmpty(entry.getValue())) {
                String paramName = entry.getKey();
                log.error("RocketMQA2aServer checkConfigParam [{}] is empty", paramName);
                missingParams.add(paramName);
            }
        }
        if (!missingParams.isEmpty()) {
            throw new IllegalArgumentException(
                    "RocketMQA2aServer init failed, missing required params: " + missingParams);
        }
    }
}

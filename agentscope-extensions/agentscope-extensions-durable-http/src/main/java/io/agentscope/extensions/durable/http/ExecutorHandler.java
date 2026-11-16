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

package io.agentscope.extensions.durable.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol.Endpoint;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.Executor;
import java.io.IOException;
import java.util.Objects;

public final class ExecutorHandler implements HttpHandler {
    private final Executor executor;
    private final SessionBackendHandler.Authorizer authorizer;

    public ExecutorHandler(Executor executor, SessionBackendHandler.Authorizer authorizer) {
        this.executor = Objects.requireNonNull(executor);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                var envelope = DurableHttp.request(exchange, Endpoint.EXECUTOR);
                String op = envelope.path("operation").asText();
                var payload = envelope.path("payload");
                var request =
                        payload.has("request")
                                ? DurableJson.convert(
                                        payload.get("request"), ExecutionRequest.class)
                                : null;
                authorizer.authorize(exchange, op, request == null ? null : request.session());
                String handle =
                        payload.path("handle").isTextual() ? payload.path("handle").asText() : null;
                Object result =
                        switch (op) {
                            case "capabilities" -> executor.capabilities();
                            case "output" ->
                                    executor.output(
                                            Objects.requireNonNull(request),
                                            payload.path("offset").asLong());
                            case "start" ->
                                    executor.startExecution(Objects.requireNonNull(request));
                            case "query" -> executor.query(Objects.requireNonNull(request), handle);
                            case "cancel" ->
                                    executor.cancel(Objects.requireNonNull(request), handle);
                            default ->
                                    throw new DurableException(
                                            DurableException.Code.INVALID,
                                            "Unknown executor operation");
                        };
                DurableHttp.result(exchange, result);
            } catch (RuntimeException error) {
                DurableHttp.error(exchange, error);
            }
        }
    }
}

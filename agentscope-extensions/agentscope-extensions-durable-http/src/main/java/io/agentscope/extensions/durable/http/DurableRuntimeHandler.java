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
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import java.io.IOException;
import java.util.Objects;

/** Restricted public operations. No claim/commit, native state or global discovery routes. */
public final class DurableRuntimeHandler implements HttpHandler {
    @FunctionalInterface
    public interface Authorizer {
        /** Namespace is explicit for listings; fork checks both source and target sessions. */
        void authorize(
                HttpExchange exchange, String operation, String namespace, SessionKey session);
    }

    private final DurableRuntime runtime;
    private final Authorizer authorizer;

    public DurableRuntimeHandler(DurableRuntime runtime, Authorizer authorizer) {
        this.runtime = Objects.requireNonNull(runtime);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                var envelope = DurableHttp.request(exchange, DurableProtocol.Endpoint.USER);
                var request = DurableJson.convert(envelope, DurableProtocol.Request.class);
                var p = request.payload();
                SessionKey key =
                        p.has("session")
                                ? DurableJson.convert(p.get("session"), SessionKey.class)
                                : null;
                String namespace =
                        key == null
                                ? p.has("namespace") ? p.path("namespace").asText() : null
                                : key.namespace();
                authorizer.authorize(exchange, request.operation(), namespace, key);
                if ("fork".equals(request.operation()))
                    authorizer.authorize(
                            exchange,
                            "forkTarget",
                            namespace,
                            DurableJson.convert(
                                    p.path("target").path("session"), SessionKey.class));
                DurableHttp.result(exchange, runtime.invoke(request));
            } catch (RuntimeException error) {
                DurableHttp.error(exchange, error);
            }
        }
    }
}

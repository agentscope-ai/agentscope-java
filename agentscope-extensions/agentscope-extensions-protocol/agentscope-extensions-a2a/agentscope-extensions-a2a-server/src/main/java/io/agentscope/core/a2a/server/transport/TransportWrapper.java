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

package io.agentscope.core.a2a.server.transport;

import java.util.Map;

/**
 * The interface for different A2A transports, such as `JSON-RPC`, `GRPC`, `REST` and other custom transports to
 * separate the access requests and handle requests.
 *
 * <p>Developers should get request body and headers from request by endpoint(Controller, Route, StreamObserver and so
 * on), then call target implementation of this interface to actual handle target transport.
 */
public interface TransportWrapper<T, R> {

    /**
     * Get wrapper transport type name.
     *
     * @return name of transport type
     */
    String getTransportType();

    /**
     * Do handle for target transport request, including streaming and non-streaming request.
     *
     * @param body     transport request body
     * @param headers  transport request headers map
     * @param metadata Other transport request metadata from request or developer
     * @return Different response for different transport request
     */
    R handleRequest(T body, Map<String, String> headers, Map<String, Object> metadata);
}

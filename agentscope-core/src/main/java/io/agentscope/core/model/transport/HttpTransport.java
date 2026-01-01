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
package io.agentscope.core.model.transport;

import reactor.core.publisher.Flux;

/**
 * HTTP transport layer interface for making HTTP requests.
 *
 * <p>This interface abstracts the actual HTTP client implementation, allowing for different
 * implementations (OkHttp, JDK HttpClient, etc.) to be used interchangeably.
 *
 * <p>The transport layer is responsible for:
 * <ul>
 *   <li>Executing synchronous HTTP requests</li>
 *   <li>Handling streaming (SSE) responses</li>
 *   <li>Managing connection lifecycle</li>
 * </ul>
 */
public interface HttpTransport {

    /**
     * Execute a synchronous HTTP request.
     *
     * @param request the HTTP request to execute
     * @return the HTTP response
     * @throws HttpTransportException if the request fails
     */
    HttpResponse execute(HttpRequest request) throws HttpTransportException;

    /**
     * Execute a streaming HTTP request (Server-Sent Events).
     *
     * <p>This method returns a Flux that emits each SSE data line as a String.
     * The implementation should handle parsing the SSE format and extracting
     * the data content from each event.
     *
     * @param request the HTTP request to execute
     * @return a Flux emitting SSE data lines as strings
     */
    Flux<String> stream(HttpRequest request);

    /**
     * Close the transport and release any resources.
     *
     * <p>After calling this method, the transport should not be used.
     */
    void close();
}

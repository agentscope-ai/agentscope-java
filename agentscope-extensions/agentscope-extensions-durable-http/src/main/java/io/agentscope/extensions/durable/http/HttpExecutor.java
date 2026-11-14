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

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol.Endpoint;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.Executor;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Direct remote Executor transport, usable with a Sandbox endpoint or optional Worker host. */
public final class HttpExecutor implements Executor {
    private final DurableHttp transport;
    private volatile Capabilities capabilities;

    public HttpExecutor(URI endpoint) {
        this(new DurableHttp(endpoint));
    }

    public HttpExecutor(DurableHttp transport) {
        this.transport = transport;
    }

    @Override
    public Capabilities capabilities() {
        var cached = capabilities;
        if (cached == null)
            capabilities =
                    cached =
                            DurableJson.convert(
                                    transport.call(Endpoint.EXECUTOR, "capabilities", Map.of()),
                                    Capabilities.class);
        return cached;
    }

    private ExecutionResult call(String operation, ExecutionRequest request, String handle) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("request", request);
        payload.put("handle", handle);
        return DurableJson.convert(
                transport.call(Endpoint.EXECUTOR, operation, payload), ExecutionResult.class);
    }

    @Override
    public ExecutionResult startExecution(ExecutionRequest request) {
        return call("start", request, null);
    }

    @Override
    public OutputPage output(ExecutionRequest request, long offset) {
        return DurableJson.convert(
                transport.call(
                        Endpoint.EXECUTOR, "output", Map.of("request", request, "offset", offset)),
                OutputPage.class);
    }

    @Override
    public ExecutionResult query(ExecutionRequest request, String handle) {
        return call("query", request, handle);
    }

    @Override
    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        return call("cancel", request, handle);
    }
}

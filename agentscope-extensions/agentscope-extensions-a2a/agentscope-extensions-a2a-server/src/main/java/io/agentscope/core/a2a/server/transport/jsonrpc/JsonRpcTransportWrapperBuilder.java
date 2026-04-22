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

import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.agentscope.core.a2a.server.transport.TransportWrapperBuilder;
import java.util.concurrent.Executor;

/**
 * Builder implementation for {@link JsonRpcTransportWrapper}.
 */
public class JsonRpcTransportWrapperBuilder
        implements TransportWrapperBuilder<JsonRpcTransportWrapper> {

    @Override
    public String getTransportType() {
        return TransportProtocol.JSONRPC.asString();
    }

    @Override
    public JsonRpcTransportWrapper build(
            AgentCard agentCard,
            RequestHandler requestHandler,
            Executor executor,
            AgentCard extendedAgentCard) {
        // TODO: after support of extended agent card after support authenticated.
        JSONRPCHandler jsonrpcHandler =
                new JSONRPCHandler(agentCard, null, requestHandler, executor);
        return new JsonRpcTransportWrapper(jsonrpcHandler);
    }
}

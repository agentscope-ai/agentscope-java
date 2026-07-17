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

package io.agentscope.spring.boot.a2a.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import java.util.Map;
import java.util.logging.Logger;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/")
public class A2aJsonRpcController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    Logger logger = Logger.getLogger(A2aJsonRpcController.class.getName());

    private final AgentScopeA2aServer agentScopeA2aServer;

    private JsonRpcTransportWrapper jsonRpcHandler;

    public A2aJsonRpcController(AgentScopeA2aServer agentScopeA2aServer) {
        this.agentScopeA2aServer = agentScopeA2aServer;
    }

    @PostMapping(
            value = "",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @ResponseBody
    public Object handleRequest(
            @RequestBody String body, @RequestHeader Map<String, String> header) {
        Object result = getJsonRpcHandler().handleRequest(body, header, Map.of());
        if (result instanceof Flux<?> fluxResult) {
            return fluxResult.map(this::convertToSse);
        } else {
            return result;
        }
    }

    private JsonRpcTransportWrapper getJsonRpcHandler() {
        if (jsonRpcHandler == null) {
            jsonRpcHandler =
                    agentScopeA2aServer.getTransportWrapper(
                            TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
        }
        return jsonRpcHandler;
    }

    private ServerSentEvent<String> convertToSse(Object response) {
        try {
            if (response instanceof String data) {
                return ServerSentEvent.<String>builder().data(data).event("jsonrpc").build();
            }
            if (response instanceof A2AResponse<?> a2aResponse) {
                String data = OBJECT_MAPPER.writeValueAsString(a2aResponse);
                ServerSentEvent.Builder<String> builder =
                        ServerSentEvent.<String>builder().data(data).event("jsonrpc");
                if (a2aResponse.getId() != null) {
                    builder.id(a2aResponse.getId().toString());
                }
                return builder.build();
            }
            return ServerSentEvent.<String>builder()
                    .data(OBJECT_MAPPER.writeValueAsString(response))
                    .event("jsonrpc")
                    .build();
        } catch (Exception e) {
            logger.severe("Error converting response to SSE: " + e.getMessage());
            return ServerSentEvent.<String>builder()
                    .data("{\"error\":\"Internal conversion error\"}")
                    .event("error")
                    .build();
        }
    }
}

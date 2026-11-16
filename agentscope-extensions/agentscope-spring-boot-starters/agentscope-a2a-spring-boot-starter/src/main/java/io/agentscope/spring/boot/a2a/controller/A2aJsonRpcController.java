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

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import java.util.Map;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
        // The transport wrapper returns JSON: a string, or a Flux of strings for streaming
        // requests.
        Object result = getJsonRpcHandler().handleRequest(body, header, Map.of());
        if (result instanceof Flux<?> fluxResult) {
            return fluxResult.map(
                    json ->
                            ServerSentEvent.<String>builder()
                                    .data((String) json)
                                    .event("jsonrpc")
                                    .build());
        }
        // Set the type explicitly: the String converter could also write it as an event stream.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }

    private JsonRpcTransportWrapper getJsonRpcHandler() {
        if (jsonRpcHandler == null) {
            jsonRpcHandler =
                    agentScopeA2aServer.getTransportWrapper(
                            TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
        }
        return jsonRpcHandler;
    }
}
